package cc.ginpika.minis.agent.workspace;

import cc.ginpika.minis.ai.terminal.ExecTarget;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.config.TerminalProps;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作空间：把命令行终端模块的「可执行环境」当作 agent 的工作空间来用，并额外提供文件面——
 * 目录浏览、文本预览与下载。工作空间是<b>基于 terminal 的扩展</b>：环境定义与凭证仍由
 * {@code term_target}（命令行终端设置）维护，本类不新增持久化，只在执行契约 {@link ExecTarget}
 * 之上组装只读的文件操作。
 *
 * <p><b>为什么能覆盖 docker 与 ssh</b>：{@code ExecTarget} 已经把两套协议收成「命令进去、输出出来」，
 * 文件操作只需拼出正确的命令行（{@code ls} / {@code head} / {@code base64}），不必分别对接
 * Docker Engine 的文件 API 与 SFTP——两条实现的路由、认证、超时护栏都是同一套。
 *
 * <p><b>路径沙箱</b>：所有路径都是相对工作空间起点目录（目标的 workdir，未配则由远端默认目录决定）
 * 的沙箱路径，由目录项名字逐级拼成；{@link #normalize} 拒绝 {@code ..} 与绝对路径。
 * 命令参数一律经 {@link ExecTarget#quote} 单引号包裹，目录项名字里的空格、引号、分号都不会被 shell 二次解释。
 *
 * <p><b>与模型护栏的关系</b>：这里的命令<b>不</b>过目标的白/黑名单——那是约束模型的，而工作空间页是
 * 人操作自己的环境（与设置页「人工试跑」同一条执行权）。超时与输出上限仍按 {@link TerminalProps} 兜底。
 */
@Component
public class WorkspaceService {

    /** 文本预览的单次读取上限（字节）：超出只给前半段并标注截断 */
    private static final int PREVIEW_MAX_BYTES = 200_000;

    /** 下载体积上限（字节）：base64 中转与内存占用都要可控 */
    private static final long DOWNLOAD_MAX_BYTES = 8L * 1024 * 1024;

    /** 下载时 stdout 的上限（字符）：base64 为 ASCII，4/3 膨胀后再留余量 */
    private static final int DOWNLOAD_MAX_CHARS = (int) ((DOWNLOAD_MAX_BYTES + 2) / 3 * 4) + 64;

    /** 列目录时 stdout 的上限（字符）：超大目录也只收前一段 */
    private static final int LIST_MAX_CHARS = 500_000;

    /** {@code ls -l} 一行：[类型][权限] 链接数 属主 属组 大小 月 日 时间/年 名字… */
    private static final Pattern LS_LINE = Pattern.compile(
            "^([-dlcbps])([rwxsStT-]{9})[.+@]?\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+"
                    + "(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(.*)$");

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final TerminalStore store;
    private final TerminalProps props;
    /** 实现种类 → 执行实现（与 TerminalService 同一聚合方式，由 Spring 注入全部 ExecTarget） */
    private final Map<String, ExecTarget> impls;

    public WorkspaceService(TerminalStore store, TerminalProps props, List<ExecTarget> targets) {
        this.store = store;
        this.props = props;
        Map<String, ExecTarget> byKind = new LinkedHashMap<>();
        for (ExecTarget target : targets) {
            byKind.put(target.kind(), target);
        }
        this.impls = Map.copyOf(byKind);
    }

    // ---------- 文件面 ----------

    /** 列出某个工作空间里某个目录的内容（path 相对起点目录，"." 表示起点本身） */
    public WorkspaceDtos.DirView list(String workspaceId, String path) {
        TerminalStore.TargetRow target = require(workspaceId);
        String rel = normalize(path);
        ExecTarget.ExecOutcome out = exec(target, "ls -la " + ExecTarget.quote("./" + rel),
                LIST_MAX_CHARS);
        if (out.exitCode() != ExecTarget.NO_EXIT_CODE && out.exitCode() != 0) {
            throw badRequest(failMessage(out, rel));
        }
        List<WorkspaceDtos.FileEntry> entries = new ArrayList<>();
        String stdout = out.stdout() == null ? "" : out.stdout();
        for (String line : stdout.split("\n")) {
            Matcher m = LS_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            /* 符号链接的 `名字 -> 目标` 只取名字：本页按名字逐级下钻，目标是另一回事 */
            String name = m.group(10);
            int arrow = name.indexOf(" -> ");
            if (arrow >= 0) {
                name = name.substring(0, arrow);
            }
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                continue;
            }
            entries.add(new WorkspaceDtos.FileEntry(name, "d".equals(m.group(1)), parseLong(m.group(6)),
                    m.group(7) + " " + m.group(8) + " " + m.group(9), "l".equals(m.group(1))));
        }
        /* 目录在前，其余按名字（不区分大小写）排序：与常见文件管理器的阅读顺序一致 */
        entries.sort(Comparator.comparing(WorkspaceDtos.FileEntry::dir).reversed()
                .thenComparing(WorkspaceDtos.FileEntry::name, String.CASE_INSENSITIVE_ORDER));
        return new WorkspaceDtos.DirView(rel, entries);
    }

    /**
     * 预览一个文本文件：一条命令同时取回「字节数 + 截断后的正文」（{@code wc -c} 输出首行，
     * {@code head -c} 输出其后的正文），省掉一次往返。二进制文件只回类型与大小、不回正文。
     */
    public WorkspaceDtos.FileView read(String workspaceId, String path) {
        TerminalStore.TargetRow target = require(workspaceId);
        String rel = requireFile(path);
        String arg = ExecTarget.quote("./" + rel);
        String command = "wc -c < " + arg + " && head -c " + PREVIEW_MAX_BYTES + " " + arg;
        ExecTarget.ExecOutcome out = exec(target, command, PREVIEW_MAX_BYTES + 256);
        if (out.exitCode() != ExecTarget.NO_EXIT_CODE && out.exitCode() != 0) {
            throw badRequest(failMessage(out, rel));
        }
        String stdout = out.stdout() == null ? "" : out.stdout();
        int nl = stdout.indexOf('\n');
        long size = parseLong(nl < 0 ? stdout : stdout.substring(0, nl));
        String content = nl < 0 ? "" : stdout.substring(nl + 1);
        boolean truncated = size > PREVIEW_MAX_BYTES;
        boolean binary = isBinary(content);
        return new WorkspaceDtos.FileView(rel, fileName(rel), size, truncated, binary,
                binary ? null : content);
    }

    /** 下载一个文件：走 base64 中转（二进制安全），超上限直接拒绝而不是给半截文件 */
    public WorkspaceDtos.Download download(String workspaceId, String path) {
        TerminalStore.TargetRow target = require(workspaceId);
        String rel = requireFile(path);
        ExecTarget.ExecOutcome out = exec(target,
                "base64 < " + ExecTarget.quote("./" + rel) + " | tr -d '\\n'", DOWNLOAD_MAX_CHARS);
        if (out.exitCode() != ExecTarget.NO_EXIT_CODE && out.exitCode() != 0) {
            throw badRequest(failMessage(out, rel));
        }
        if (out.truncated()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件过大（下载上限 " + (DOWNLOAD_MAX_BYTES / 1024 / 1024) + " MB）");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(out.stdout() == null ? "" : out.stdout().trim());
            return new WorkspaceDtos.Download(fileName(rel), bytes);
        } catch (IllegalArgumentException e) {
            throw badRequest("文件内容解码失败（可能不是普通文件）：" + rel);
        }
    }

    // ---------- 执行 ----------

    private TerminalStore.TargetRow require(String workspaceId) {
        return store.targetById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "工作空间不存在：" + workspaceId));
    }

    /** 在目标环境里执行一条只读命令（起点目录用目标自带的 workdir） */
    private ExecTarget.ExecOutcome exec(TerminalStore.TargetRow target, String command,
                                        int maxOutputChars) {
        ExecTarget impl = impls.get(target.kind());
        if (impl == null) {
            throw badRequest("未知的目标类型：" + target.kind());
        }
        ExecTarget.ExecOptions options = new ExecTarget.ExecOptions(target.workdir(),
                Math.max(1_000L, props.getDefaultTimeoutMs()), maxOutputChars);
        try {
            return impl.run(target, command, options);
        } catch (RuntimeException e) {
            log.warn("工作空间文件操作失败（{} {}）：{}", target.kind(), target.name(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "无法连接工作空间「" + target.name() + "」：" + e.getMessage());
        }
    }

    // ---------- 路径与文本 ----------

    /**
     * 相对起点目录的沙箱路径：拒绝绝对路径与 {@code ..}（工作空间页只按目录项名字逐级下钻，
     * 不接受跨越起点的路径），并把 {@code .} / 空段折叠掉；空串归一为 "."（起点自身）。
     */
    private static String normalize(String path) {
        if (path == null) {
            return ".";
        }
        String p = path.trim();
        if (p.indexOf('\0') >= 0 || p.indexOf('\n') >= 0) {
            throw badRequest("路径含非法字符");
        }
        if (p.startsWith("/")) {
            throw badRequest("只支持相对工作空间起点的路径（不接受绝对路径）");
        }
        List<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw badRequest("路径不允许向上跳出工作空间起点");
            }
            parts.add(seg);
        }
        return parts.isEmpty() ? "." : String.join("/", parts);
    }

    /** 预览 / 下载要求指向一个具体文件（起点目录本身不可预览） */
    private static String requireFile(String path) {
        String rel = normalize(path);
        if (rel.equals(".")) {
            throw badRequest("请选择要预览的文件");
        }
        return rel;
    }

    /** 取路径最后一段作为展示名 / 下载文件名 */
    private static String fileName(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? rel : rel.substring(slash + 1);
    }

    /**
     * 二进制判定：含 NUL 直接判定；否则看 UTF-8 解码后的替换字符（\uFFFD）密度——
     * 少量可能只是个别坏字节，占比高才当成二进制。
     */
    private static boolean isBinary(String content) {
        if (content.indexOf('\0') >= 0) {
            return true;
        }
        if (content.isEmpty()) {
            return false;
        }
        int bad = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\uFFFD') {
                bad++;
            }
        }
        return bad > 16 && bad * 100 > content.length();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 失败原因：优先 stderr（命令的真实抱怨），其次 stdout，最后给一句兜底 */
    private static String failMessage(ExecTarget.ExecOutcome out, String rel) {
        String err = out.stderr() == null ? "" : out.stderr().strip();
        if (!err.isEmpty()) {
            return err;
        }
        String so = out.stdout() == null ? "" : out.stdout().strip();
        if (!so.isEmpty()) {
            return so;
        }
        return "无法访问路径：" + rel;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
