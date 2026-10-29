package cc.ginpika.minis.ai.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 记忆体系 v5 的词汇表与纯函数（memory-v5 §3 / §4.5）。
 *
 * <p>把「归属 / 领域 / 流转层 / 状态 / 保留级别 / 来源类型 / 证据等级」这些字符串常量与它们的
 * 轻量派生规则（scope→domain/layer 缺省映射、正文规范化、指纹计算）集中一处，避免散落成魔法字符串。
 * 不持有任何状态，故全是静态成员。
 */
public final class MemoryVocabulary {

    private MemoryVocabulary() {
    }

    // ---------- owner：归属者（决定读写权限） ----------
    public static final String OWNER_USER = "USER";
    public static final String OWNER_PROJECT = "PROJECT";
    public static final String OWNER_AGENT = "AGENT";
    public static final String OWNER_TASK = "TASK";

    // ---------- domain：这条信息描述什么 ----------
    public static final String DOMAIN_USER_PROFILE = "USER_PROFILE";
    public static final String DOMAIN_PROJECT = "PROJECT";
    public static final String DOMAIN_AGENT = "AGENT";
    public static final String DOMAIN_TASK = "TASK_EPISODIC";

    // ---------- layer：流转层（决定可见范围与生命周期） ----------
    public static final String LAYER_L1 = "L1";  // 共享权威
    public static final String LAYER_L2 = "L2";  // Agent 私有
    public static final String LAYER_L3 = "L3";  // 任务/会话临时
    public static final String LAYER_L4 = "L4";  // 交接/知识（发布）

    // ---------- status ----------
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUPERSEDED = "superseded";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CONFLICTED = "conflicted";
    public static final String STATUS_REVOKED = "revoked";
    public static final String STATUS_ARCHIVED = "archived";

    // ---------- retention：保护级别（与 status 分离） ----------
    public static final String RETENTION_PINNED = "pinned";
    public static final String RETENTION_LONG = "long";
    public static final String RETENTION_NORMAL = "normal";
    public static final String RETENTION_EPHEMERAL = "ephemeral";

    // ---------- source_kind：role-play 隔离依据 ----------
    public static final String SRC_REAL = "real";
    public static final String SRC_FICTION = "fiction";
    public static final String SRC_PROJECT_PREF = "project_pref";
    public static final String SRC_INFERRED = "inferred";

    // ---------- evidence_level：画像升级闸门 ----------
    public static final String EVIDENCE_EXPLICIT = "explicit";
    public static final String EVIDENCE_REPEATED = "repeated";
    public static final String EVIDENCE_INFERRED = "inferred";

    // ---------- scope：域内细分（兼容旧四值 + 新增） ----------
    public static final String SCOPE_DIRECTIVE = "directive";
    public static final String SCOPE_PROFILE = "profile";
    public static final String SCOPE_PROJECT = "project";
    public static final String SCOPE_EPISODE = "episode";
    public static final String SCOPE_FACT = "fact";
    public static final String SCOPE_DECISION = "decision";
    public static final String SCOPE_METHOD = "method";
    public static final String SCOPE_OPEN_ISSUE = "open_issue";
    public static final String SCOPE_CHECKPOINT = "checkpoint";
    public static final String SCOPE_HANDOFF = "handoff";
    public static final String SCOPE_KNOWLEDGE = "knowledge";

    /** 全部合法 scope（顺序即展示顺序） */
    public static final List<String> SCOPES = List.of(
            SCOPE_DIRECTIVE, SCOPE_PROFILE, SCOPE_PROJECT, SCOPE_EPISODE,
            SCOPE_FACT, SCOPE_DECISION, SCOPE_METHOD, SCOPE_OPEN_ISSUE,
            SCOPE_CHECKPOINT, SCOPE_HANDOFF, SCOPE_KNOWLEDGE);

    /** 画像类 scope（唯一允许 owner=USER） */
    public static final List<String> PROFILE_SCOPES = List.of(SCOPE_PROFILE, SCOPE_DIRECTIVE);

    /**
     * 受保护条目（memory-v6 §2.3 · 不变量 I10）：淘汰、去重、语义取代**跳过**；本轮注入仍受预算约束。
     * 判据 = {@code pinned} / 正式画像（{@code owner=USER}）/ 未完成事项（{@code open_issue}）
     * / 全局规则与指令（{@code directive}，安全类，必须恒定注入且实时生效）。
     */
    public static boolean isProtected(String owner, String scope, boolean pinned, String retention) {
        return pinned
                || RETENTION_PINNED.equals(retention)
                || OWNER_USER.equals(owner)
                || SCOPE_OPEN_ISSUE.equals(scope)
                || SCOPE_DIRECTIVE.equals(scope);
    }

    // ---------- source（写入来源，沿用旧三值） ----------
    public static final String SRC_LABEL_AUTO = "auto";
    public static final String SRC_LABEL_EXPLICIT = "explicit";
    public static final String SRC_LABEL_MANUAL = "manual";

    /** 缺省 domain：由 scope 推断（真正的归属仍由写入上下文决定，这里只是兜底） */
    public static String defaultDomain(String scope) {
        return switch (scope == null ? "" : scope) {
            case SCOPE_DIRECTIVE, SCOPE_PROFILE -> DOMAIN_USER_PROFILE;
            case SCOPE_PROJECT, SCOPE_FACT, SCOPE_DECISION -> DOMAIN_PROJECT;
            case SCOPE_CHECKPOINT -> DOMAIN_TASK;
            default -> DOMAIN_AGENT;
        };
    }

    /** 缺省 layer：由 scope 推断（兜底） */
    public static String defaultLayer(String scope) {
        return switch (scope == null ? "" : scope) {
            case SCOPE_DIRECTIVE, SCOPE_PROFILE, SCOPE_PROJECT, SCOPE_FACT, SCOPE_DECISION -> LAYER_L1;
            case SCOPE_CHECKPOINT, SCOPE_EPISODE -> LAYER_L3;
            case SCOPE_HANDOFF, SCOPE_KNOWLEDGE -> LAYER_L4;
            default -> LAYER_L2;
        };
    }

    /** 正文规范化：去首尾空白 + 折叠连续空白（指纹与去重的依据） */
    public static String normalize(String body) {
        if (body == null) {
            return "";
        }
        return body.trim().replaceAll("\\s+", " ");
    }

    /**
     * 幂等指纹：{@code sha1(owner|project_id|agent_id|domain|scope|normalize(body))}。
     * 空值以空串参与，保证同内容同归属得到同指纹（memory-v5 §4.5）。
     */
    public static String fingerprint(String owner, String projectId, String agentId,
                                     String domain, String scope, String body) {
        String raw = nz(owner) + "|" + nz(projectId) + "|" + nz(agentId) + "|"
                + nz(domain) + "|" + nz(scope) + "|" + normalize(body);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            /* SHA-1 一定存在；真出问题退化为正文哈希，保证非空 */
            return Integer.toHexString(raw.hashCode());
        }
    }

    public static String fingerprint(String owner, String projectId, String agentId, String domain,
                                     String scope, String body, String layer, String sessionId, Long taskId) {
        String value = "L3".equals(layer) ? body + "|L3|" + nz(sessionId) + "|" + taskId : body;
        return fingerprint(owner, projectId, agentId, domain, scope, value);
    }

    public static String retentionKey(cc.ginpika.minis.ai.memory.store.MemoryItemStore.ItemRow it) {
        return nz(it.owner()) + "|" + nz(it.projectId()) + "|" + nz(it.agentId()) + "|"
                + nz(it.domain()) + "|" + nz(it.layer())
                + ("L3".equals(it.layer()) ? "|" + nz(it.sessionId()) + "|" + it.taskId() : "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
