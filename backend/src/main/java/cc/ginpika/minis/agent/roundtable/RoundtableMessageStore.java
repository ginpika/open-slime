package cc.ginpika.minis.agent.roundtable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 圆桌消息的读写口——<b>底表是 {@code chat_message}</b>：消息已归一，与首页对话共用一张表。
 *
 * <p>role 有三种：{@code user}=人说的话，{@code peer}=某个成员说的话（peer_id / peer_name
 * 指明是谁，并冗余存一份展示名——成员被删后历史仍然可读），{@code event}=状态机的一条动作
 * （谁转沉默、谁提议结束……见 {@link #insertEvent}）。topic_id 把这批往来串成一次话题。
 *
 * <p>圆桌独有的那几列（topic_id / peer_id / peer_name / context / event_seq）是 chat_message 上
 * 的新增列，首页对话一律为 NULL；会话与消息都已归一。
 */
@Repository
public class RoundtableMessageStore {

    /** 角色：人说的话 */
    public static final String ROLE_USER = "user";
    /** 角色：某个成员说的话 */
    public static final String ROLE_PEER = "peer";
    /** 角色：状态机的一条动作（内容是一句自述式文案，不是某个人说的话） */
    public static final String ROLE_EVENT = "event";

    /**
     * 系统提示的一种（{@link #ROLE_EVENT} 的 event_kind）：全桌已收到某位成员的受阻 / 报告信号、
     * 正在重新处理。前端据此渲染成一条醒目的高亮提示条——让用户一眼看到「其他成员已经在接手」。
     */
    public static final String KIND_RALLY = "rally";

    public record MessageRow(long id, String sessionId, String topicId, String role, String peerId,
                             String peerName, String content, String reasoning, String trace,
                             String toolRounds, String context, String eventKind, Long createdAt) {
    }

    private static final String COLS =
            "id, session_id, topic_id, role, peer_id, peer_name, content, reasoning, trace, "
                    + "tool_rounds, context, event_kind, created_at";

    private final JdbcTemplate jdbc;

    public RoundtableMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ActivityRow(long id, String role, String peerId, String peerName,
                              String content, String kind, long createdAt) {}

    /** 画布只需要最近动态的摘要，不读取完整历史、思考或请求上下文。 */
    public List<ActivityRow> recentActivity(String sessionId, int limit) {
        return jdbc.query("SELECT id, role, peer_id, peer_name, substr(content,1,280) AS content, "
                        + "event_kind, created_at FROM chat_message WHERE session_id = ? "
                        + "ORDER BY id DESC LIMIT ?",
                (rs, n) -> new ActivityRow(rs.getLong("id"), rs.getString("role"),
                        rs.getString("peer_id"), rs.getString("peer_name"), rs.getString("content"),
                        rs.getString("event_kind"), rs.getLong("created_at")), sessionId, limit);
    }

    /**
     * 落一条消息，返回自增 id（成员发言的 id 会被事件表按 message_id 引用）。
     *
     * <p>context 为该次发言组装出的请求体（仅成员发言有，人的话传 null）——页面点开一条发言
     * 回看时用它。
     */
    public long insert(String sessionId, String topicId, String role, String peerId,
                       String peerName, String content, String reasoning, String trace,
                       String toolRounds, String context) {
        return insert(sessionId, topicId, role, peerId, peerName, content, reasoning, trace, toolRounds, context, null);
    }

    public long insert(String sessionId, String topicId, String role, String peerId,
                       String peerName, String content, String reasoning, String trace,
                       String toolRounds, String context, String eventKind) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message(session_id, topic_id, role, peer_id, peer_name, "
                            + "content, reasoning, trace, tool_rounds, context, created_at, event_kind) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, topicId);
            ps.setString(3, role);
            ps.setString(4, peerId);
            ps.setString(5, peerName);
            ps.setString(6, content);
            ps.setString(7, reasoning);
            ps.setString(8, trace);
            ps.setString(9, toolRounds);
            ps.setString(10, context);
            ps.setLong(11, System.currentTimeMillis());
            ps.setString(12, eventKind);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    public boolean promoteWorkInstruction(String session,long messageId) {
        return jdbc.update("UPDATE chat_message SET event_kind=NULL WHERE session_id=? AND id=? AND role='user' AND event_kind='conversation' "
                        +"AND id=(SELECT max(id) FROM chat_message WHERE session_id=? AND role='user')",
                session,messageId,session)==1;
    }

    /**
     * 落一条状态机动作行（{@link #ROLE_EVENT}）。
     *
     * <p>eventSeq 是这条动作在话题事件流（roundtable_topic_event）里的序号：既是它与事件的对应关系，
     * 也是「已经补写到哪一条」的水位线（见 {@code RoundtableTopicService#mirrorEvents}）——
     * 于是补写可以重复执行而不重不漏。
     *
     * <p>eventKind 是那条事件的种类（{@code joined} / {@code silent} / {@code turn_end} …）：
     * <b>文案不是协议</b>——前端据此分类与渲染，不再去匹配中文（文案改一个字，界面就静默错位）。
     */
    public long insertEvent(String sessionId, String topicId, int eventSeq, String peerId,
                            String peerName, String content, String eventKind) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message(session_id, topic_id, role, peer_id, peer_name, "
                            + "content, event_seq, event_kind, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, topicId);
            ps.setString(3, ROLE_EVENT);
            ps.setString(4, peerId);
            ps.setString(5, peerName);
            ps.setString(6, content);
            ps.setInt(7, eventSeq);
            ps.setString(8, eventKind);
            ps.setLong(9, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /**
     * 落一条「系统提示」行：{@link #ROLE_EVENT}，但<b>不属于状态机跃迁序列</b>——不写 event_seq
     * （那是对补写水位线的），只带 event_kind 供前端分类渲染。
     *
     * <p>用途：需要让用户明确看见的圆桌级提示，如「全桌已收到受阻报告、其他成员开始处理」。
     * 它照常进公开记录（模型侧读成 {@code <event>}），所以成员下一轮也看得见。
     */
    public long insertNotice(String sessionId, String topicId, String content, String kind) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO chat_message(session_id, topic_id, role, content, event_kind, created_at) "
                            + "VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, topicId);
            ps.setString(3, ROLE_EVENT);
            ps.setString(4, content);
            ps.setString(5, kind);
            ps.setLong(6, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 某话题已补写的最大事件序号（还没有动作行时返回 0）：动作行补写的水位线 */
    public int maxEventSeq(String topicId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(event_seq), 0) FROM chat_message WHERE topic_id = ? AND role = ?",
                Integer.class, topicId, ROLE_EVENT);
        return max == null ? 0 : max;
    }

    /** 某会话的全部消息（按行序 = 时间序）：共享话题历史的来源 */
    public List<MessageRow> bySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM chat_message WHERE session_id = ? ORDER BY id",
                RoundtableMessageStore::map, sessionId);
    }

    // Project records are independent of conversational history and keep their stable message IDs.
    private static final String REWIND_FILTER = "session_id=? AND id>=? AND COALESCE(event_kind,'') "
            + "NOT IN ('task_context','issue_context','issue_open','issue_resolved')";

    public List<MessageRow> rewindRows(String sessionId, long fromId) {
        return jdbc.query("SELECT " + COLS + " FROM chat_message WHERE " + REWIND_FILTER + " ORDER BY id",
                RoundtableMessageStore::map, sessionId, fromId);
    }

    public int rewind(String sessionId, long fromId) {
        return jdbc.update("DELETE FROM chat_message WHERE " + REWIND_FILTER, sessionId, fromId);
    }

    /** 某话题内的全部消息 */
    public List<MessageRow> byTopic(String topicId) {
        return jdbc.query("SELECT " + COLS + " FROM chat_message WHERE topic_id = ? ORDER BY id",
                RoundtableMessageStore::map, topicId);
    }

    /** 某会话最后一条消息（判断上一话题是否已经有往来） */
    public Optional<MessageRow> lastBySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM chat_message WHERE session_id = ? "
                        + "ORDER BY id DESC LIMIT 1", RoundtableMessageStore::map, sessionId)
                .stream().findFirst();
    }

    /** 按 id 取一条消息（点开一条发言回看它当时的原始上下文） */
    public Optional<MessageRow> message(long id) {
        return jdbc.query("SELECT " + COLS + " FROM chat_message WHERE id = ?", RoundtableMessageStore::map, id)
                .stream().findFirst();
    }

    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM chat_message WHERE session_id = ?", sessionId);
    }

    private static MessageRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(rs.getLong("id"), rs.getString("session_id"),
                rs.getString("topic_id"), rs.getString("role"), rs.getString("peer_id"),
                rs.getString("peer_name"), rs.getString("content"), rs.getString("reasoning"),
                rs.getString("trace"), rs.getString("tool_rounds"), rs.getString("context"),
                rs.getString("event_kind"), RoundtableRows.nullableLong(rs, "created_at"));
    }
}
