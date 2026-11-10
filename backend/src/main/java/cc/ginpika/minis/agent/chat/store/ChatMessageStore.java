package cc.ginpika.minis.agent.chat.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** chat_message（AI 会话消息正本，按行序 = id 序） */
@Repository
public class ChatMessageStore {

    /** 消息形态：子代理结论气泡（前端只渲染标题+摘要，点开看全文；元信息见 message.meta） */
    public static final String KIND_SUBAGENT = "subagent";
    public static final String KIND_SUBAGENT_BATCH = "subagent_batch";
    public static final String KIND_AGENT_MESSAGE = "agent_message";
    public static final String KIND_AGENT_MESSAGE_SENT = "agent_message_sent";
    public static final String KIND_STEER = "steer";
    public static final String KIND_STEER_REPLY = "steer_reply";
    public static final String STEER_TURN_PREFIX = "chat-turn:";

    public record MessageRow(Long id, String sessionId, String role, String content, String reasoning,
                             String trace, String toolRounds, String kind, String meta,
                             Long createdAt) {
    }

    /** 跨会话的一条消息（带会话名，供潜意识注入器回溯「最近对人说过什么」） */
    public record RecentRow(String sessionTitle, String content, long createdAt) {
    }

    public record SubagentReportRow(long id, String meta, boolean retracted) {
    }

    private final JdbcTemplate jdbc;

    public ChatMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落一条消息，返回自增 id（回退某条提问时前端要用它定位删除区间）；
     *  reasoning（思维链纯文本）/trace（思考分段 JSON）/toolRounds（工具轮原生消息 JSON）
     *  仅 assistant 有；kind/meta 标识子代理投递或同轮补充消息。流式对话也可用 meta 关联同一轮的首尾。 */
    public long insert(String sessionId, String role, String content, String reasoning, String trace,
                       String toolRounds, String kind, String meta) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message(session_id, role, content, reasoning, trace, tool_rounds, "
                            + "kind, meta, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.setString(4, reasoning);
            ps.setString(5, trace);
            ps.setString(6, toolRounds);
            ps.setString(7, kind);
            ps.setString(8, meta);
            ps.setLong(9, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    public List<MessageRow> listBySession(String sessionId) {
        return jdbc.query(
                "SELECT id, session_id, role, content, reasoning, trace, tool_rounds, kind, meta, "
                        + "created_at FROM chat_message WHERE session_id = ? ORDER BY id",
                ChatMessageStore::map, sessionId);
    }

    /** 批次合成只读取已投递报告的元信息。 */
    public SubagentReportRow subagentReport(String sessionId, long execId) {
        List<SubagentReportRow> rows = jdbc.query("""
                SELECT id, meta, EXISTS (
                    SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'chat_message:' || m.id
                ) AS retracted
                FROM chat_message m WHERE session_id = ? AND kind = ?
                    AND json_extract(CASE WHEN json_valid(meta) THEN meta END, '$.execId') = ?
                ORDER BY id DESC LIMIT 1
                """, (rs, n) -> new SubagentReportRow(rs.getLong("id"), rs.getString("meta"),
                        rs.getBoolean("retracted")),
                sessionId, KIND_SUBAGENT, execId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 首页侧栏预览：只读取首页可见会话的最后一条正文，不加载转录，也不改未读状态。 */
    public Map<String, String> latestPreviews() {
        // 与 HubSessionStore.listAll 的可见范围一致；现有 (session_id, id) 索引可倒序定位末条消息。
        return jdbc.query(
                "SELECT s.id AS session_id, SUBSTR(m.content, 1, 220) AS preview "
                        + "FROM hub_session s JOIN chat_message m ON m.id = ("
                        + "SELECT candidate.id FROM chat_message candidate WHERE candidate.session_id = s.id "
                        + "AND candidate.role IN ('user', 'assistant') "
                        + "AND candidate.content IS NOT NULL AND TRIM(candidate.content) <> '' "
                        + "ORDER BY candidate.id DESC LIMIT 1) WHERE s.mode = 'ai'",
                rs -> {
                    Map<String, String> previews = new LinkedHashMap<>();
                    while (rs.next()) {
                        previews.put(rs.getString("session_id"), rs.getString("preview"));
                    }
                    return previews;
                });
    }

    public long observedAt(String sessionId, long id) {
        var times = jdbc.query("SELECT created_at FROM chat_message WHERE session_id=? AND id=? AND role='user'",
                (rs, n) -> rs.getLong(1), sessionId, id);
        return times.isEmpty() ? 0L : times.getFirst();
    }

    /**
     * 写入某条消息的「执行留痕」（chat_message.context 列）：首页会话用它存该 Turn 每一轮
     * LLM 调用的请求体 + 响应体（JSON 数组）。圆桌那条流另有其义（发言当时的请求体），
     * 二者写入者不同（首页会话 vs 圆桌），行也不重叠。
     */
    public void updateContext(long id, String context) {
        jdbc.update("UPDATE chat_message SET context = ? WHERE id = ?", context, id);
    }

    /** 读某会话某条消息的执行留痕原文（context 列）；不存在或为空返回 null（sessionId 一并校验，防跨会话读取） */
    public String contextOf(String sessionId, long id) {
        List<String> rows = jdbc.query(
                "SELECT context FROM chat_message WHERE id = ? AND session_id = ?",
                (rs, n) -> rs.getString("context"), id, sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 把 fromSession 中 id ≤ upToId 的消息整行复制进 toSession（「分支」用）：
     * 正文 / 思维链 / 分段 / 工具往返 / 形态元信息 / 执行留痕一并照搬，时间戳原样。
     * Agent 消息发送标签只属于发出它的会话。
     * 按 id 升序插入，新行的自增 id 随之保持同一先后（读侧按 id 排序即得原顺序）。
     *
     * <p>每一行副本同时落一条 {@code chat_message_origin} 映射（R2）：指回原始会话与原始消息行，
     * 并记录复制时原始会话的授权代号。原会话随后改绑、回退或撤回源消息时，副本退出模型可读范围，
     * 用户转录不受影响。
     *
     * @return 复制的行数
     */
    public int copyUpTo(String fromSession, String toSession, long upToId) {
        List<CopyRow> rows = jdbc.query(
                "SELECT id, role, content, reasoning, trace, tool_rounds, kind, meta, topic_id, "
                        + "peer_id, peer_name, context, event_seq, event_kind, created_at "
                        + "FROM chat_message WHERE session_id = ? AND id <= ? "
                        + "AND (kind IS NULL OR kind <> ?) AND (event_kind IS NULL OR event_kind <> ?) ORDER BY id",
                (rs, n) -> new CopyRow(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("reasoning"), rs.getString("trace"), rs.getString("tool_rounds"),
                        rs.getString("kind"), rs.getString("meta"), rs.getString("topic_id"),
                        rs.getString("peer_id"), rs.getString("peer_name"), rs.getString("context"),
                        (Long) rs.getObject("event_seq"), rs.getString("event_kind"),
                        rs.getLong("created_at")),
                fromSession, upToId, KIND_AGENT_MESSAGE_SENT, KIND_AGENT_MESSAGE_SENT);
        if (rows.isEmpty()) {
            return 0;
        }
        long revision = jdbc.queryForObject(
                "SELECT COALESCE(context_scope_revision, 0) FROM hub_session WHERE id = ?",
                Long.class, fromSession);
        Map<Long, Long> idMapping = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        int copied = 0;
        for (CopyRow row : rows) {
            long newId = insertCopy(toSession, row);
            if (newId > 0) {
                jdbc.update("INSERT INTO chat_message_origin(message_id, origin_session_id, "
                                + "origin_message_id, origin_scope_revision, created_at) VALUES(?,?,?,?,?)",
                        newId, fromSession, row.id(), revision, now);
                idMapping.put(row.id(), newId);
                copied++;
            }
        }
        copyDependencies(fromSession, toSession, idMapping, now);
        return copied;
    }

    /**
     * 派生内容的依赖随副本一起迁移（R3）：message 引用改指本会话的对应副本（副本失效时依赖随之失效），
     * artifact/subagent 引用保持原样——它们在分支会话里本就不可用，依赖因此判失效，派生副本退出模型范围。
     */
    private void copyDependencies(String fromSession, String toSession, Map<Long, Long> idMapping, long now) {
        if (idMapping.isEmpty()) {
            return;
        }
        List<String[]> deps = jdbc.query(
                "SELECT d.message_id, d.ref FROM chat_message_dependency d "
                        + "JOIN chat_message m ON m.id = d.message_id "
                        + "WHERE m.session_id = ? ORDER BY d.message_id, d.ref",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, fromSession);
        for (String[] dep : deps) {
            Long target = idMapping.get(Long.parseLong(dep[0]));
            if (target == null) {
                continue;
            }
            String ref = dep[1];
            if (ref != null && ref.startsWith("message:")) {
                int end = ref.indexOf(':', "message:".length());
                try {
                    Long original = Long.valueOf(end < 0
                            ? ref.substring("message:".length())
                            : ref.substring("message:".length(), end));
                    Long mapped = idMapping.get(original);
                    if (mapped != null) {
                        ref = "message:" + mapped + (end < 0 ? "" : ref.substring(end));
                    }
                } catch (NumberFormatException ignored) {
                    // 非 message:<id> 形态的引用保持原样
                }
            }
            jdbc.update("INSERT OR IGNORE INTO chat_message_dependency(message_id, ref, created_at) "
                    + "VALUES(?,?,?)", target, ref, now);
        }
    }

    /** 为一条派生消息记录持久依赖（R3）：任一来源失效，该消息退出模型请求与回读。 */
    public void insertDependencies(long messageId, java.util.Collection<String> refs) {
        if (messageId <= 0 || refs == null || refs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String ref : refs.stream().filter(r -> r != null && !r.isBlank()).distinct().toList()) {
            jdbc.update("INSERT OR IGNORE INTO chat_message_dependency(message_id, ref, created_at) "
                    + "VALUES(?,?,?)", messageId, ref, now);
        }
    }

    private long insertCopy(String toSession, CopyRow row) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message(session_id, role, content, reasoning, trace, tool_rounds, "
                            + "kind, meta, topic_id, peer_id, peer_name, context, event_seq, event_kind, "
                            + "created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, toSession);
            ps.setString(2, row.role());
            ps.setString(3, row.content());
            ps.setString(4, row.reasoning());
            ps.setString(5, row.trace());
            ps.setString(6, row.toolRounds());
            ps.setString(7, row.kind());
            ps.setString(8, row.meta());
            ps.setString(9, row.topicId());
            ps.setString(10, row.peerId());
            ps.setString(11, row.peerName());
            ps.setString(12, row.context());
            if (row.eventSeq() == null) {
                ps.setNull(13, java.sql.Types.INTEGER);
            } else {
                ps.setLong(13, row.eventSeq());
            }
            ps.setString(14, row.eventKind());
            ps.setLong(15, row.createdAt());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 整行副本的载体（copyUpTo 用；含所有被复制的列） */
    private record CopyRow(long id, String role, String content, String reasoning, String trace,
                           String toolRounds, String kind, String meta, String topicId, String peerId,
                           String peerName, String context, Long eventSeq, String eventKind,
                           long createdAt) {
    }

    /** 删除 fromId（含）及其后的消息：会话回退到这条之前，返回删除行数 */
    public int deleteFrom(String sessionId, long fromId) {
        return jdbc.update(
                "DELETE FROM chat_message WHERE session_id = ? AND id >= ?", sessionId, fromId);
    }

    /** 删除某会话的全部消息（删除会话前清理，避免残留正本行）；返回删除行数 */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
    }

    /**
     * 跨会话最近投进聊天的若干条回复（assistant，按时间倒序）：取会话名 + 正文，
     * 供潜意识子代理知道「最近已经对人说过什么」，免得重复说同一件事。
     */
    public List<RecentRow> latestAssistantMessages(int limit) {
        return jdbc.query(
                "SELECT COALESCE(s.title, '') AS title, m.content AS content, "
                        + "COALESCE(m.created_at, 0) AS created_at "
                        + "FROM chat_message m JOIN hub_session s ON s.id = m.session_id "
                        + "WHERE m.role = 'assistant' AND m.content IS NOT NULL "
                        + "AND TRIM(m.content) <> '' ORDER BY m.id DESC LIMIT ?",
                (rs, n) -> new RecentRow(rs.getString("title"), rs.getString("content"),
                        rs.getLong("created_at")), limit);
    }

    private static MessageRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("reasoning"),
                rs.getString("trace"),
                rs.getString("tool_rounds"),
                rs.getString("kind"),
                rs.getString("meta"),
                (Long) rs.getObject("created_at"));
    }
}
