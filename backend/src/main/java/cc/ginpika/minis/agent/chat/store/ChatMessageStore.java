package cc.ginpika.minis.agent.chat.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** chat_message（AI 会话消息正本，按行序 = id 序） */
@Repository
public class ChatMessageStore {

    /** 消息形态：子代理结论气泡（前端只渲染标题+摘要，点开看全文；元信息见 message.meta） */
    public static final String KIND_SUBAGENT = "subagent";

    public record MessageRow(Long id, String sessionId, String role, String content, String reasoning,
                             String trace, String toolRounds, String kind, String meta,
                             Long createdAt) {
    }

    /** 跨会话的一条消息（带会话名，供潜意识注入器回溯「最近对人说过什么」） */
    public record RecentRow(String sessionTitle, String content, long createdAt) {
    }

    private final JdbcTemplate jdbc;

    public ChatMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落一条消息，返回自增 id（回退某条提问时前端要用它定位删除区间）；
     *  reasoning（思维链纯文本）/trace（思考分段 JSON）/toolRounds（工具轮原生消息 JSON）
     *  仅 assistant 有；kind/meta（消息形态与元信息）仅子代理结论气泡有，普通对话传 null */
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
