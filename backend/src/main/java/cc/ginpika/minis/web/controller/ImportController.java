package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.ai.knowledge.dto.DocumentImportDtos;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoService;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 文档导入：粘贴文本直接保存为 markdown 文章（data/article/{url}.md + documents 索引行）。门禁同 AdminController（写操作）。 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}$");
    private static final Pattern HEADLINE = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern LINK = Pattern.compile("\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern MARKUP = Pattern.compile("[#*`>_|~-]");
    private static final Pattern FM_TITLE_YAML = Pattern.compile("^title:\\s*(.*)$");
    private static final Pattern FM_TITLE_TOML = Pattern.compile("^title\\s*=\\s*(.*)$");
    private static final int TITLE_MAX = 80;
    private static final int SUMMARY_MAX = 120;

    private final DocumentStore documents;
    private final SsoProps ssoProps;
    private final SsoService ssoService;

    public ImportController(DocumentStore documents, SsoProps ssoProps, SsoService ssoService) {
        this.documents = documents;
        this.ssoProps = ssoProps;
        this.ssoService = ssoService;
    }

    @PostMapping
    public DocumentImportDtos.ImportResult importArticle(HttpServletRequest request,
                                                 @RequestBody DocumentImportDtos.ImportRequest req) {
        requireAdmin(request);
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 为空");
        }
        String title = req.title() != null && !req.title().isBlank()
                ? truncate(req.title().trim(), TITLE_MAX)
                : extractTitle(content);
        String url = uniqueUrl(slugOf(title));
        documents.saveArticle(url, title, summarize(content), content);
        return new DocumentImportDtos.ImportResult(url, title);
    }

    // ---------- 私有 ----------

    /** 正文起始行：跳过 --- / +++ 包围的 front-matter 头部块（未闭合视为无 front-matter） */
    private static int bodyStart(String[] lines) {
        if (lines.length == 0) {
            return 0;
        }
        String fence = lines[0].trim();
        if (!fence.equals("---") && !fence.equals("+++")) {
            return 0;
        }
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals(fence)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** front-matter 块内的 title 行值（剥离包裹引号）；无 front-matter / 未写 title 返回 null */
    private static String frontMatterTitle(String[] lines) {
        int start = bodyStart(lines);
        if (start == 0) {
            return null;
        }
        Pattern p = lines[0].trim().equals("+++") ? FM_TITLE_TOML : FM_TITLE_YAML;
        for (int i = 1; i < start - 1; i++) {
            Matcher m = p.matcher(lines[i].trim());
            if (m.matches()) {
                String v = m.group(1).trim();
                boolean quoted = v.length() >= 2
                        && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
                return quoted ? v.substring(1, v.length() - 1) : v;
            }
        }
        return null;
    }

    /** 标题：优先 front-matter 的 title；否则首个 # 标题行；再否则首个非空行（后两者跳过 front-matter） */
    private static String extractTitle(String content) {
        String[] lines = content.split("\n");
        String fm = frontMatterTitle(lines);
        if (fm != null && !fm.isBlank()) {
            return truncate(fm.trim(), TITLE_MAX);
        }
        int start = bodyStart(lines);
        for (int i = start; i < lines.length; i++) {
            Matcher m = HEADLINE.matcher(lines[i].trim());
            if (m.matches()) {
                return truncate(m.group(1).trim(), TITLE_MAX);
            }
        }
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return truncate(lines[i].trim(), TITLE_MAX);
            }
        }
        return "未命名文档";
    }

    /** 摘要：剥离 front-matter 与 markdown 标记后的前 SUMMARY_MAX 字 */
    private static String summarize(String content) {
        String[] lines = content.split("\n");
        String body = String.join("\n", Arrays.copyOfRange(lines, bodyStart(lines), lines.length));
        String plain = LINK.matcher(body).replaceAll("$1");
        plain = MARKUP.matcher(plain).replaceAll("");
        return truncate(plain.replaceAll("\\s+", " ").trim(), SUMMARY_MAX);
    }

    /** slug：标题转小写 slug；无法转出合法 slug（如纯中文）时用时间戳兜底 */
    private static String slugOf(String title) {
        String slug = title.toLowerCase()
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
        if (!SLUG.matcher(slug).matches()) {
            slug = "article-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        }
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    /** 已存在同 url 文章时追加序号，避免覆盖 */
    private String uniqueUrl(String base) {
        String url = base;
        for (int i = 2; documents.articleMeta(url) != null; i++) {
            url = truncate(base, 76) + "-" + i;
        }
        return url;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!ssoService.isEnabled()) {
            return; // dev 直通
        }
        SsoUser user = (SsoUser) request.getAttribute(SsoInterceptor.ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        boolean allowed = "admin".equalsIgnoreCase(user.getRole())
                || ssoProps.getAdminUsernames().contains(user.getUsername());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }
}
