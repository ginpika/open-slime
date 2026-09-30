package cc.ginpika.minis.agent.multiagent.store;

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
 * ma_message（多智能体消息：人在圆桌上说的话 + 各成员的发言 + 状态机动作）。
 *
 * <p>与 {@code chat_message} 同构（reasoning / trace / tool_rounds 一样留痕），但不共用表：
 * 多智能体有自己的会话体系，混进 chat_message 会让两侧的查询都要记得带过滤条件。
 *
 * <p>role 有三种：{@code user}=人说的话，{@code peer}=某个成员说的话（peer_id / peer_name
 * 指明是谁，并冗余存一份展示名——成员被删后历史仍然可读），{@code event}=状态机的一条动作
 * （谁转沉默、谁提议结束……见 {@link #insertEvent}）。topic_id 把这批往来串成一次话题。
 */
@Repository
public class MaMessageStore {

    /** 角色：人说的话 */
    public static final String ROLE_USER = "user";
    /** 角色：某个成员说的话 */
    public static final String ROLE_PEER = "peer";
    /** 角色：状态机的一条动作（内容是一句自述式文案，不是某个人说的话） */
    public static final String ROLE_EVENT = "event";

    public record MessageRow(long id, String sessionId, String topicId, String role, String peerId,
                             String peerName, String content, String reasoning, String trace,
                             String toolRounds, String context, Long createdAt) {
    }

    private static final String COLS =
            "id, session_id, topic_id, role, peer_id, peer_name, content, reasoning, trace, "
                    + "tool_rounds, context, created_at";

    private final JdbcTemplate jdbc;

    public MaMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ma_message(session_id, topic_id, role, peer_id, peer_name, "
                            + "content, reasoning, trace, tool_rounds, context, created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
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
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /**
     * 落一条状态机动作行（{@link #ROLE_EVENT}）。
     *
     * <p>eventSeq 是这条动作在话题事件流（ma_topic_event）里的序号：既是它与事件的对应关系，
     * 也是「已经补写到哪一条」的水位线（见 {@code MaTopicService#mirrorEvents}）——
     * 于是补写可以重复执行而不重不漏。
     */
    public long insertEvent(String sessionId, String topicId, int eventSeq, String peerId,
                            String peerName, String content) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ma_message(session_id, topic_id, role, peer_id, peer_name, "
                            + "content, event_seq, created_at) VALUES(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, topicId);
            ps.setString(3, ROLE_EVENT);
            ps.setString(4, peerId);
            ps.setString(5, peerName);
            ps.setString(6, content);
            ps.setInt(7, eventSeq);
            ps.setLong(8, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 某话题已补写的最大事件序号（还没有动作行时返回 0）：动作行补写的水位线 */
    public int maxEventSeq(String topicId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(event_seq), 0) FROM ma_message WHERE topic_id = ? AND role = ?",
                Integer.class, topicId, ROLE_EVENT);
        return max == null ? 0 : max;
    }

    /** 某会话的全部消息（按行序 = 时间序）：共享话题历史的来源 */
    public List<MessageRow> bySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_message WHERE session_id = ? ORDER BY id",
                MaMessageStore::map, sessionId);
    }

    /** 某话题内的全部消息 */
    public List<MessageRow> byTopic(String topicId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_message WHERE topic_id = ? ORDER BY id",
                MaMessageStore::map, topicId);
    }

    /** 某会话最后一条消息（判断上一话题是否已经有往来） */
    public Optional<MessageRow> lastBySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_message WHERE session_id = ? "
                        + "ORDER BY id DESC LIMIT 1", MaMessageStore::map, sessionId)
                .stream().findFirst();
    }

    /** 按 id 取一条消息（点开一条发言回看它当时的原始上下文） */
    public Optional<MessageRow> message(long id) {
        return jdbc.query("SELECT " + COLS + " FROM ma_message WHERE id = ?", MaMessageStore::map, id)
                .stream().findFirst();
    }

    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM ma_message WHERE session_id = ?", sessionId);
    }

    private static MessageRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(rs.getLong("id"), rs.getString("session_id"),
                rs.getString("topic_id"), rs.getString("role"), rs.getString("peer_id"),
                rs.getString("peer_name"), rs.getString("content"), rs.getString("reasoning"),
                rs.getString("trace"), rs.getString("tool_rounds"), rs.getString("context"),
                MaRows.nullableLong(rs, "created_at"));
    }
}
