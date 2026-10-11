package cc.ginpika.minis.agent.roundtable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * roundtable_topic_event（隐式状态机的事件正本：每次发言、每次状态跃迁、每次征询与打断）。
 *
 * <p>只追加、不改写。成员的当前状态不存在任何列里，而是由本表按 seq 折叠得出
 * （见 {@code TopicStateMachine}）——「状态在任何时刻都等于事件流折叠出来的结果」，
 * 于是不存在两处状态互相打架的可能，这正是本仓库对「LLM 无状态、状态在库里」的一贯做法。
 *
 * <p>seq 是话题内的序号（1 起），由本类在追加时算出来：并发写入同一话题的情况在
 * 圆桌里不会发生（同一时刻至多一个成员在发言），故不必另加锁。
 */
@Repository
public class RoundtableTopicEventStore {

    /** 事件种类：话题开始 */
    public static final String KIND_TOPIC_OPEN = "topic_open";
    /**
     * 事件种类：新一轮开始（人的一句新话）。
     *
     * <p>它是一条纯粹的<b>水位线</b>，不带任何状态跃迁（from/to 都为 null）：欲望的有效期只有一轮
     * （见 {@code TopicStateMachine#latestDesires}），本事件就是那一轮的起点——上一轮报出的欲望
     * 到此作废，于是「上一轮想说话、这一轮没表态」的人不会继续排在队首。
     */
    public static final String KIND_TURN_START = "turn_start";
    /** 事件种类：被点名（发言带有指向性） */
    public static final String KIND_ADDRESSED = "addressed";
    /** 事件种类：入场（话题开始时在座；名册上的人都应到场，否则永远轮不到） */
    public static final String KIND_JOINED = "joined";
    /** 事件种类：授予发言权 */
    public static final String KIND_FLOOR_GRANTED = "floor_granted";
    /** 事件种类：发言 */
    public static final String KIND_SPEECH = "speech";
    /** 事件种类：成员报出的「下一轮发言欲望」（0~10，选人依据——见 DesireSpeakerStrategy） */
    public static final String KIND_DESIRE = "desire";
    /** 事件种类：说完让出（自己结束发言） */
    public static final String KIND_FLOOR_RELEASED = "floor_released";
    /** 事件种类：被打断（人抢话，或同伴抢答） */
    public static final String KIND_INTERRUPT = "interrupt";
    /** 事件种类：成员举手抢话——声明自己打算发言（下一位优先交给它） */
    public static final String KIND_INTERJECT = "interject";
    /** 事件种类：转入沉默 */
    public static final String KIND_SILENT = "silent";
    /** 事件种类：重新参与（新一轮开始时，把上一轮转入沉默的成员放回活跃） */
    public static final String KIND_REVIVED = "revived";
    /**
     * 事件种类：重入（人的新话开启新一轮时，把<b>任何非活跃参与者</b>都复位为活跃）。
     *
     * <p>它比 {@link #KIND_REVIVED} 更彻底：不只是沉默者，连「提议结束」「待表态」「等待用户」也一并
     * 放回活跃——人既然又开口了，上一轮的结论（含收场票）就该重新讨论，否则整桌会停在
     * 「全员提议结束」而再也没人能被叫到（见 {@code TopicStateMachine#reenter}）。
     */
    public static final String KIND_REENTER = "reenter";
    /** 事件种类：提议结束 */
    public static final String KIND_PROPOSE_END = "propose_end";
    /** 事件种类：被要求立刻表态（向沉默者征询） */
    public static final String KIND_PROMPT = "prompt";
    /** 事件种类：请求用户表态——全桌暂停，等用户发言（见 TopicStateMachine#askUser） */
    public static final String KIND_ASK_USER = "ask_user";
    /** 事件种类：用户已回应，等待中的成员回到活跃 */
    public static final String KIND_RESUME = "resume";
    /** 事件种类：全员重新决策（某位沉默者发言了） */
    public static final String KIND_REOPEN = "reopen";
    /** 事件种类：话题关闭 */
    public static final String KIND_CLOSE = "close";
    /**
     * 事件种类：本轮结束（一次推进跑完一轮）。
     *
     * <p>它记的是<b>客观清单</b>——这一轮谁发了言、是不是被发言次数上限打断，不写任何推断。
     * 「谁没说、为什么」由各成员此刻的状态给出（{@code silent} = 自己不说、{@code propose_end} = 投了结束票、
     * {@code active} = 没轮到），把猜测写成事实比不记更糟。
     */
    public static final String KIND_TURN_END = "turn_end";

    /** 事件发起者：人 */
    public static final String ACTOR_USER = "user";

    public record EventRow(long id, String topicId, int seq, String actor, String kind,
                           String fromState, String toState, String peerId, String content,
                           Long messageId, long createdAt) {
    }

    private static final String COLS =
            "id, topic_id, seq, actor, kind, from_state, to_state, peer_id, content, message_id, "
                    + "created_at";

    private final JdbcTemplate jdbc;

    public RoundtableTopicEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条事件（seq 自增）；返回它的 seq */
    public int append(String topicId, String actor, String kind, String fromState, String toState,
                      String peerId, String content, Long messageId) {
        int seq = nextSeq(topicId);
        jdbc.update("INSERT INTO roundtable_topic_event(topic_id, seq, actor, kind, from_state, to_state, "
                        + "peer_id, content, message_id, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                topicId, seq, actor, kind, fromState, toState, peerId, content, messageId,
                System.currentTimeMillis());
        return seq;
    }

    /** 某话题的全部事件（按 seq 升序，折叠状态的顺序） */
    public List<EventRow> byTopic(String topicId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_topic_event WHERE topic_id = ? "
                + "ORDER BY seq", RoundtableTopicEventStore::map, topicId);
    }

    /** 某话题最近若干条事件（观测台倒序展示用） */
    public List<EventRow> latestByTopic(String topicId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_topic_event WHERE topic_id = ? "
                + "ORDER BY seq DESC LIMIT ?", RoundtableTopicEventStore::map, topicId, limit);
    }

    /** 某会话全部话题的事件（删除会话时先清它，话题行随后删） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM roundtable_topic_event WHERE topic_id IN "
                + "(SELECT id FROM roundtable_topic WHERE session_id = ?)", sessionId);
    }

    private int nextSeq(String topicId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM roundtable_topic_event WHERE topic_id = ?",
                Integer.class, topicId);
        return (max == null ? 0 : max) + 1;
    }

    private static EventRow map(ResultSet rs, int rowNum) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("topic_id"), rs.getInt("seq"),
                rs.getString("actor"), rs.getString("kind"), rs.getString("from_state"),
                rs.getString("to_state"), rs.getString("peer_id"), rs.getString("content"),
                RoundtableRows.nullableLong(rs, "message_id"), rs.getLong("created_at"));
    }
}
