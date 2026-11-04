package cc.ginpika.minis.ai.computer.terminal;

import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.terminal.ListTerminalsTool;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import cc.ginpika.minis.ai.core.util.LlmJson;
import cc.ginpika.minis.ai.computer.terminal.dto.TerminalDtos;
import cc.ginpika.minis.ai.computer.terminal.exec.DockerExecTarget;
import cc.ginpika.minis.ai.computer.terminal.exec.SshExecTarget;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import cc.ginpika.minis.config.TerminalProps;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 命令行终端的入口：模型侧的一次工具调用、设置页的目标维护与试跑，都从这里进出。
 *
 * <p>它自己不做协议（docker / ssh 各由 {@link ExecTarget} 的实现负责），只做三件事：
 * <ol>
 *   <li><b>选目标</b>：按名取库里的目标，逐目标开关打开才放行；调用方还可再叠一层「授权名单」——
 *       多智能体成员只在自己绑定的工作空间里选（见 {@link #run(JsonNode, String, Set)}）</li>
 *   <li><b>上护栏</b>：白/黑名单、超时、输出上限——命令还没出门就先判一次</li>
 *   <li><b>留痕</b>：无论执行、被拒还是失败，都往 {@code term_command} 记一行</li>
 * </ol>
 *
 * <p>和 {@code KnowledgeToolbox} 在知识库里扮演的角色一样：工具面唯一的默认分发点，
 * 由会话与心跳两处转交过来（见 {@code ChatToolDispatcher#runTool} / {@code HeartbeatService}）。
 *
 * <p><b>这是全项目权限最高的一件工具</b>：它执行的不是检索而是任意命令。因此逐目标默认关、
 * 白黑名单可选而超时与输出上限恒在——把「模型能碰什么」交给配置，
 * 而不是交给模型的自觉。
 */
@Component
public class TerminalService {

    /** 审计来源：会话侧发起 */
    public static final String SOURCE_CHAT = "chat";
    /** 审计来源：心跳侧发起 */
    public static final String SOURCE_HEARTBEAT = "heartbeat";
    /** 审计来源：设置页手动试跑 */
    public static final String SOURCE_MANUAL = "manual";

    /** 命令超时的下界：比这更短的值一律抬到这里，免得「配成 0」变成一执行就杀 */
    private static final long MIN_TIMEOUT_MS = 1_000L;

    /** 输出上限的下界（字符）：太小会把有用信息也截掉 */
    private static final int MIN_OUTPUT_CHARS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    private final TerminalProps props;
    private final TerminalStore store;
    private final ObjectMapper om;
    /** 实现种类 → 执行实现（由 Spring 注入全部 ExecTarget，与工具侧按 surface 聚合同一个套路） */
    private final Map<String, ExecTarget> impls;

    public TerminalService(TerminalProps props, TerminalStore store, ObjectMapper om,
                           List<ExecTarget> targets) {
        this.props = props;
        this.store = store;
        this.om = om;
        Map<String, ExecTarget> byKind = new LinkedHashMap<>();
        for (ExecTarget target : targets) {
            byKind.put(target.kind(), target);
        }
        this.impls = Map.copyOf(byKind);
    }

    // ---------- 模型侧：一次工具调用 ----------

    /**
     * 是不是本模块的工具。
     *
     * <p>工具名以各自的工具类为准，这里只做「归哪个模块执行」的归口：
     * 会话与心跳两处分发都用它判一次，新增终端工具时只改这里一处（而不是散在各调用点）。
     */
    public static boolean handles(String toolName) {
        return RunCommandTool.NAME.equals(toolName) || ListTerminalsTool.NAME.equals(toolName);
    }

    /** 执行一次终端工具调用（默认按会话来源记审计） */
    public ToolResult run(JsonNode call) {
        return run(call, SOURCE_CHAT);
    }

    /** 执行一次终端工具调用，返回给模型的 tool 消息内容（不限目标，会话与心跳走这条） */
    public ToolResult run(JsonNode call, String source) {
        return run(call, source, null);
    }

    /**
     * 执行一次终端工具调用，返回给模型的 tool 消息内容。
     *
     * <p>{@code source} 只用于审计（谁发起的），不影响行为：
     * 会话与心跳调的是同一套本领，能碰什么由目标配置决定。
     *
     * <p>{@code allowedTargetIds} 是<b>调用方的授权边界</b>：非空时，只能看见与只能执行这几个
     * {@code term_target.id}；{@code null} = 不限（首页对话与心跳主会话那条路，也就是现状）。
     * 多智能体成员用它把「这个智能体能碰哪几台」收在它自己绑定的工作空间里。
     */
    public ToolResult run(JsonNode call, String source, Set<String> allowedTargetIds) {
        return runScoped(call,source,allowedTargetIds,null,null);
    }

    /** A caller-selected project directory is an execution start location, not a shell filesystem sandbox. */
    public ToolResult runScoped(JsonNode call, String source, Set<String> allowedTargetIds, String targetId, String directory) {
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("");
        JsonNode parsed = LlmJson.readTree(args);
        String content = switch (name) {
            case RunCommandTool.NAME -> runCommand(parsed, source, allowedTargetIds,targetId,directory);
            case ListTerminalsTool.NAME -> targetId==null ? listTargetsText(allowedTargetIds) : scopedTargetText(allowedTargetIds,targetId,directory);
            default -> "未知工具: " + name;
        };
        /* writes()=false：命令不改知识库，故不进写库清单；它的留痕在 term_command 自己那张表里 */
        return new ToolResult(name, args, content, false);
    }

    private String scopedTargetText(Set<String> allowed,String id,String directory) {
        if(allowed==null || !allowed.contains(id)) return "项目工作空间不在当前成员获准的工作空间内，请用户调整成员配置。";
        return store.targetById(id).filter(TerminalStore.TargetRow::enabled)
                .map(t -> "本项目唯一工作空间："+t.name()+" ["+t.kind()+"]；项目工作目录："+directory+"。不得切换到其他项目。")
                .orElse("项目工作空间未启用或已删除，请用户检查绑定。");
    }

    /** 可用目标清单（不限目标的那份，给首页对话与心跳用） */
    public String listTargetsText() {
        return listTargetsText(null);
    }

    /** 可用目标清单（给模型看的那份文本，list_terminals 的正文）；{@code allowedTargetIds} 非空时只列授权的目标 */
    public String listTargetsText(Set<String> allowedTargetIds) {
        List<TerminalStore.TargetRow> rows = enabledTargets(allowedTargetIds);
        if (rows.isEmpty()) {
            return allowedTargetIds == null || allowedTargetIds.isEmpty()
                    ? "当前没有启用的工作空间。请在工作空间页添加并启用一个（docker 容器或 SSH 服务器）。"
                    : "你没有可用的工作空间：绑定的目标已被删除或未启用。";
        }
        StringBuilder sb = new StringBuilder("可用工作空间（run_command 的 target 取下列名字）：");
        for (TerminalStore.TargetRow row : rows) {
            sb.append("\n- ").append(row.name()).append(" [").append(row.kind()).append("] ")
                    .append(describe(row));
        }
        return sb.toString();
    }

    // ---------- 模型侧：执行 ----------

    private String runCommand(JsonNode args, String source, Set<String> allowedTargetIds,String scopedTarget,String directory) {
        String targetName = args.path("target").asText("").trim();
        if (targetName.isEmpty()) {
            return "参数缺少 target：先调用 " + ListTerminalsTool.NAME + " 看看有哪些目标。";
        }
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            return "参数缺少 command";
        }
        TerminalStore.TargetRow target = store.targetByName(targetName).orElse(null);
        if (target == null) {
            return "没有名为「" + targetName + "」的工作空间。\n" + (scopedTarget==null ? listTargetsText(allowedTargetIds) : scopedTargetText(allowedTargetIds,scopedTarget,directory));
        }
        if(scopedTarget!=null && !scopedTarget.equals(target.id())) return "此目标不是本项目绑定的工作空间，未执行。";
        /* 授权边界先于一切：不在名单里的目标连「启用没启用」都不该透露，也不该被执行 */
        if (allowedTargetIds != null && !allowedTargetIds.contains(target.id())) {
            return "工作空间「" + targetName + "」不在你被授权的工作空间内，未执行。\n"
                    + (scopedTarget==null ? listTargetsText(allowedTargetIds) : scopedTargetText(allowedTargetIds,scopedTarget,directory));
        }
        if (!target.enabled()) {
            return "工作空间「" + targetName + "」未启用（逐目标开关），未执行。\n"
                    + (scopedTarget==null ? listTargetsText(allowedTargetIds) : scopedTargetText(allowedTargetIds,scopedTarget,directory));
        }
        ExecTarget impl = impls.get(target.kind());
        if (impl == null) {
            return "未知的目标类型：" + target.kind() + "（只支持 docker / ssh）";
        }
        ExecTarget.ExecOptions options = options(target);
        if(scopedTarget!=null) {
            if(directory==null || directory.isBlank()) return "项目工作目录未设置，未执行。";
            options=new ExecTarget.ExecOptions(directory,options.timeoutMs(),options.maxOutputChars());
        }

        String rejected = guard(target, command);
        if (rejected != null) {
            audit(target, command, options.workdir(),
                    new ExecTarget.ExecOutcome(ExecTarget.NO_EXIT_CODE, "", rejected, 0L, false, false),
                    source);
            return rejected;
        }
        try {
            String executable=scopedTarget==null ? command : "test \"$(pwd -P)\" = "+ExecTarget.quote(directory)+" || exit 73; "+command;
            ExecTarget.ExecOutcome outcome = impl.run(target, executable, options);
            audit(target, command, options.workdir(), outcome, source);
            return render(target, command, outcome);
        } catch (RuntimeException e) {
            log.warn("终端命令执行失败（{} {}）：{}", target.kind(), target.name(), e.getMessage());
            audit(target, command, options.workdir(),
                    new ExecTarget.ExecOutcome(ExecTarget.NO_EXIT_CODE, "",
                            String.valueOf(e.getMessage()), 0L, false, false),
                    source);
            return "命令执行失败：" + e.getMessage();
        }
    }

    /**
     * 白/黑名单判定：命中黑名单或未命中非空白名单都拒。
     *
     * <p>以命令<b>原文</b>做子串匹配（不解析 shell），所以它能挡住的是「明显不该做的事」，
     * 挡不住处心积虑的绕过——这是护栏不是沙箱。真正的防线是「哪些目标开着」以及进程隔离。
     *
     * @return 拒绝理由；放行返回 null
     */
    public String commandRejection(String targetId,String command) {
        var target=store.targetById(targetId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"工作空间不存在"));
        if(!target.enabled()){return "工作空间未启用";}
        return guard(target,command);
    }
    public ExecTarget.ExecOptions executionOptions(String targetId) {
        return options(store.targetById(targetId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"工作空间不存在")));
    }
    private String guard(TerminalStore.TargetRow target, String command) {
        List<String> deny = read(target.denyPatterns());
        for (String rule : deny) {
            Pattern p = compile(rule, target.name());
            if (p.matcher(command).find()) {
                return "命令被黑名单规则拒绝（/" + rule + "/），未执行。若确有必要，请调整该目标的规则。";
            }
        }
        List<String> allow = read(target.allowPatterns());
        if (!allow.isEmpty()
                && allow.stream().noneMatch(rule -> compile(rule, target.name()).matcher(command).find())) {
            return "命令未命中白名单（该目标只允许：" + String.join(" / ", allow) + "），未执行。";
        }
        return null;
    }

    /** 正则非法时拒绝执行（fail closed）：护栏配错了宁可不动，也不能当成「没规则」放行 */
    private static Pattern compile(String regex, String targetName) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "目标「" + targetName + "」的规则正则非法：/" + regex + "/");
        }
    }

    /** 目标配置 + 全局默认 → 本次执行的实际护栏值（逐目标值一律夹取到全局上限内） */
    private ExecTarget.ExecOptions options(TerminalStore.TargetRow target) {
        long timeout = target.timeoutMs() == null ? props.getDefaultTimeoutMs() : target.timeoutMs();
        timeout = Math.max(MIN_TIMEOUT_MS, Math.min(timeout, props.getMaxTimeoutMs()));
        int maxOutput = target.maxOutput() == null ? props.getMaxOutputChars() : target.maxOutput();
        maxOutput = Math.max(MIN_OUTPUT_CHARS, Math.min(maxOutput, props.getMaxOutputCharsCap()));
        return new ExecTarget.ExecOptions(target.workdir(), timeout, maxOutput);
    }

    /** 回给模型的结果文本：退出码、耗时、两路输出，以及「截断了 / 超时了」这类必须如实交代的事实 */
    private static String render(TerminalStore.TargetRow target, String command,
                                 ExecTarget.ExecOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标 ").append(target.name()).append(" [").append(target.kind()).append("]\n")
                .append("$ ").append(command).append('\n');
        sb.append("exit=").append(outcome.exitCode() == ExecTarget.NO_EXIT_CODE
                ? "?" : String.valueOf(outcome.exitCode()));
        sb.append(" 用时=").append(outcome.durationMs()).append("ms");
        if (outcome.timedOut()) {
            sb.append("（已超时并终止）");
        }
        if (outcome.truncated()) {
            sb.append("（输出已按上限截断）");
        }
        String out = outcome.stdout() == null ? "" : outcome.stdout().strip();
        String err = outcome.stderr() == null ? "" : outcome.stderr().strip();
        sb.append("\n--- stdout ---\n").append(out.isEmpty() ? "（无输出）" : out);
        if (!err.isEmpty()) {
            sb.append("\n--- stderr ---\n").append(err);
        }
        return sb.toString();
    }

    // ---------- 设置页：目标维护 ----------

    public TerminalDtos.TerminalView view() {
        return new TerminalDtos.TerminalView(store.targets().stream().map(this::toView).toList());
    }

    public TerminalDtos.TargetView create(TerminalDtos.TargetRequest req) {
        TerminalStore.TargetRow row = fromRequest(req, null);
        if (store.nameTaken(row.name(), null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标名已存在：" + row.name());
        }
        return toView(store.save(row));
    }

    public TerminalDtos.TargetView update(String id, TerminalDtos.TargetRequest req) {
        TerminalStore.TargetRow existing = store.targetById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在: " + id));
        TerminalStore.TargetRow row = fromRequest(req, existing.id());
        if (store.nameTaken(row.name(), id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标名已存在：" + row.name());
        }
        return toView(store.save(row));
    }

    public void delete(String id) {
        if (store.delete(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在: " + id);
        }
    }

    /** 连通性测试：不打洞、不起容器，只验证「连得上、能执行」 */
    public TerminalDtos.ProbeView probe(String id) {
        TerminalStore.TargetRow target = require(id);
        ExecTarget impl = impls.get(target.kind());
        if (impl == null) {
            return new TerminalDtos.ProbeView(false, 0L, null, "未知的目标类型：" + target.kind());
        }
        long started = System.currentTimeMillis();
        try {
            impl.probe(target);
            return new TerminalDtos.ProbeView(true, System.currentTimeMillis() - started,
                    describe(target), null);
        } catch (RuntimeException e) {
            return new TerminalDtos.ProbeView(false, System.currentTimeMillis() - started, null,
                    e.getMessage());
        }
    }

    /** 设置页手动试跑：不经过模型，但仍走同一套护栏与审计（source=manual） */
    public TerminalDtos.CommandView runManual(String id, String command) {
        TerminalStore.TargetRow target = require(id);
        if (command == null || command.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command 不能为空");
        }
        ExecTarget impl = impls.get(target.kind());
        if (impl == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的目标类型：" + target.kind());
        }
        ExecTarget.ExecOptions options = options(target);
        String rejected = guard(target, command);
        ExecTarget.ExecOutcome outcome;
        if (rejected != null) {
            outcome = new ExecTarget.ExecOutcome(ExecTarget.NO_EXIT_CODE, "", rejected, 0L, false, false);
        } else {
            try {
                outcome = impl.run(target, command, options);
            } catch (RuntimeException e) {
                outcome = new ExecTarget.ExecOutcome(ExecTarget.NO_EXIT_CODE, "",
                        String.valueOf(e.getMessage()), 0L, false, false);
            }
        }
        return toView(audit(target, command, options.workdir(), outcome, SOURCE_MANUAL));
    }

    public List<TerminalDtos.CommandView> commands(int limit) {
        return store.recentCommands(Math.max(1, Math.min(limit, 200))).stream()
                .map(TerminalService::toView)
                .toList();
    }

    // ---------- 内部 ----------

    /** 启用的目标；{@code allowedTargetIds} 非空时再按授权名单过滤（null = 不限） */
    private List<TerminalStore.TargetRow> enabledTargets(Set<String> allowedTargetIds) {
        List<TerminalStore.TargetRow> out = new ArrayList<>();
        for (TerminalStore.TargetRow row : store.targets()) {
            if (row.enabled() && (allowedTargetIds == null || allowedTargetIds.contains(row.id()))) {
                out.add(row);
            }
        }
        return out;
    }

    private TerminalStore.TargetRow require(String id) {
        return store.targetById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在: " + id));
    }

    /** 记一行审计（并顺带裁剪旧的）；返回落库前的那条，便于设置页试跑直接回显 */
    private TerminalStore.CommandRow audit(TerminalStore.TargetRow target, String command,
                                           String workdir, ExecTarget.ExecOutcome outcome,
                                           String source) {
        TerminalStore.CommandRow row = new TerminalStore.CommandRow(null, target.id(), target.name(),
                command, workdir, outcome.exitCode() == ExecTarget.NO_EXIT_CODE
                ? null : outcome.exitCode(), outcome.durationMs(), outcome.stdout(), outcome.stderr(),
                outcome.truncated(), outcome.timedOut(), outcome.ok(), source,
                System.currentTimeMillis());
        try {
            store.insertCommand(row);
            store.trimCommands(Math.max(1, props.getAuditKeep()));
        } catch (RuntimeException e) {
            /* 审计写不进去不该连累这次执行本身 */
            log.warn("终端命令审计落库失败：{}", e.getMessage());
        }
        return row;
    }

    /** 请求 → 行：校验 + 归一（只保留该 kind 用得到的字段，避免切换类型后残留脏值） */
    private TerminalStore.TargetRow fromRequest(TerminalDtos.TargetRequest req, String id) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        /* kind 用实现注册表校验：以后加实现时这里自动认得，不必再改一处 switch */
        String kind = trim(req.kind());
        if (!impls.containsKey(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind 只能是 " + String.join(" / ", impls.keySet()));
        }
        boolean isDocker = DockerExecTarget.KIND.equals(kind);
        String name = trim(req.name());
        if (name.isEmpty() && isDocker && id == null) {
            name = "docker-" + UUID.randomUUID().toString().substring(0, 8);
        }
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 不能为空");
        }
        String image = blankToNull(req.image());
        String container = blankToNull(req.container());
        String hostWorkdir = isDocker ? blankToNull(req.hostWorkdir()) : null;
        String workdir = blankToNull(req.workdir());
        List<String> allow = clean(req.allowPatterns());
        List<String> deny = clean(req.denyPatterns());
        for (String rule : allow) {
            requireValidRegex(rule);
        }
        for (String rule : deny) {
            requireValidRegex(rule);
        }
        if (req.timeoutMs() != null && req.timeoutMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timeoutMs 需为正数");
        }
        if (req.maxOutput() != null && req.maxOutput() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxOutput 需为正数");
        }
        String sshAuth = SshExecTarget.AUTH_KEY.equals(trim(req.sshAuth()))
                ? SshExecTarget.AUTH_KEY : SshExecTarget.AUTH_PASSWORD;
        String hostKeyCheck = SshExecTarget.HOST_KEY_ACCEPT_ALL.equals(trim(req.hostKeyCheck()))
                ? SshExecTarget.HOST_KEY_ACCEPT_ALL : SshExecTarget.HOST_KEY_KNOWN;
        if (isDocker) {
            if (container == null && image == null) image = "ubuntu:24.04";
            if (hostWorkdir != null && container != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "已有容器不能追加目录绑定，请使用容器已有的挂载，或从镜像新建工作空间");
            }
            if (hostWorkdir != null && !(hostWorkdir.startsWith("/")
                    || hostWorkdir.matches("^[A-Za-z]:[\\\\/].*") || hostWorkdir.startsWith("\\\\"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "宿主机目录必须是绝对路径");
            }
            if (container == null && workdir == null) workdir = "/workspace";
            if (hostWorkdir != null && (!workdir.startsWith("/")
                    || List.of(workdir.split("/")).stream().allMatch(part -> part.isEmpty() || part.equals("."))
                    || workdir.contains("\\") || List.of(workdir.split("/")).contains(".."))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绑定路径须为容器内绝对路径，不能为根目录或包含 ..");
            }
        } else {
            if (trim(req.sshHost()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ssh 目标需指定 sshHost");
            }
            if (SshExecTarget.AUTH_KEY.equals(sshAuth) && trim(req.sshPrivateKey()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "私钥认证需提供 sshPrivateKey");
            }
            if (SshExecTarget.AUTH_PASSWORD.equals(sshAuth) && trim(req.sshPassword()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码认证需提供 sshPassword");
            }
        }
        return new TerminalStore.TargetRow(id, name, kind, Boolean.TRUE.equals(req.enabled()),
                workdir, req.timeoutMs(), req.maxOutput(),
                write(allow), write(deny),
                isDocker ? blankToNull(req.dockerHost()) : null,
                isDocker && container == null ? image : null,
                isDocker ? container : null,
                isDocker ? null : blankToNull(req.sshHost()),
                isDocker ? null : req.sshPort(),
                isDocker ? null : blankToNull(req.sshUser()),
                isDocker ? null : sshAuth,
                isDocker ? null : blankToNull(req.sshPassword()),
                isDocker ? null : blankToNull(req.sshPrivateKey()),
                isDocker ? null : blankToNull(req.sshPassphrase()),
                isDocker ? null : hostKeyCheck,
                blankToNull(req.note()),
                hostWorkdir,
                0L, 0L);
    }

    private static void requireValidRegex(String regex) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则正则非法：/" + regex + "/");
        }
    }

    /** 给模型看的一句话：这台环境是什么、起点在哪 */
    private static String describe(TerminalStore.TargetRow row) {
        StringBuilder sb = new StringBuilder();
        if (DockerExecTarget.KIND.equals(row.kind())) {
            sb.append(row.container() != null ? "容器 " + row.container() : "镜像 " + row.image());
        } else {
            sb.append(nullTo(row.sshUser(), "root")).append('@').append(row.sshHost())
                    .append(':').append(row.sshPort() == null ? 22 : row.sshPort());
        }
        if (row.workdir() != null) {
            sb.append("｜工作目录 ").append(row.workdir());
        }
        if (row.note() != null) {
            sb.append("｜").append(row.note());
        }
        return sb.toString();
    }

    // ---------- 映射与序列化 ----------

    /** 目标行 → 视图（规则列要解 JSON，故非静态） */
    private TerminalDtos.TargetView toView(TerminalStore.TargetRow row) {
        return new TerminalDtos.TargetView(row.id(), row.name(), row.kind(), row.enabled(),
                row.workdir(), row.timeoutMs(), row.maxOutput(), read(row.allowPatterns()),
                read(row.denyPatterns()), row.dockerHost(), row.image(), row.container(),
                row.sshHost(), row.sshPort(), row.sshUser(), row.sshAuth(), row.sshPassword(),
                row.sshPrivateKey(), row.sshPassphrase(), row.hostKeyCheck(), row.note(), row.hostWorkdir());
    }

    private static TerminalDtos.CommandView toView(TerminalStore.CommandRow row) {
        return new TerminalDtos.CommandView(row.id() == null ? 0L : row.id(), row.targetName(),
                row.command(), row.workdir(), row.exitCode(), row.durationMs(), row.stdout(),
                row.stderr(), row.truncated(), row.timedOut(), row.ok(), row.source(),
                row.createdAt());
    }

    /** JSON 数组 → 规则清单；空/坏数据按「没有规则」处理（与 heartbeat 的 names() 同一个兜底） */
    private List<String> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("终端目标规则解析失败（按无规则处理）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 空清单落 null：让「没规则」与「规则为空」在库里是同一种状态 */
    private String write(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(list);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则序列化失败: " + e.getMessage());
        }
    }

    private static List<String> clean(List<String> list) {
        if (list == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : list) {
            String t = trim(s);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }

    private static String nullTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
