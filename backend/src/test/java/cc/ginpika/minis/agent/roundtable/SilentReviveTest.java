package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 回归：全桌沉默之后，人再说话必须能把这张桌子重新叫起来。
 *
 * <p>这场僵局曾经真实存在——沉默者不在「可被叫到」之列（见 {@link SpeakerStrategy#eligible}），
 * 而全场沉默时终止判定的两个入口（全体提议结束 / 除沉默者外都提议结束）又都不成立，
 * 于是话题既收不了场、也再没人开口：人的下一句话只落了一行消息，一个应答都没有。
 * 修复是在人的每一句新话进来时把沉默者放回活跃（见 {@link TopicStateMachine#reviveSilent}）。
 */
class SilentReviveTest {

    /** 内存库 + 两张表的最小舞台（真实 schema 见 schema.sql，这里只建会用到的那一段） */
    private static JdbcTemplate memoryDb() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE roundtable_topic("
                    + "id TEXT PRIMARY KEY, session_id TEXT NOT NULL, origin TEXT NOT NULL,"
                    + "origin_peer TEXT, status TEXT NOT NULL, floor_peer TEXT,"
                    + "resolve_round INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,"
                    + "closed_at INTEGER)");
            st.execute("CREATE TABLE roundtable_topic_event("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, topic_id TEXT NOT NULL,"
                    + "seq INTEGER NOT NULL, actor TEXT, kind TEXT NOT NULL, from_state TEXT,"
                    + "to_state TEXT, peer_id TEXT, content TEXT, message_id INTEGER,"
                    + "created_at INTEGER NOT NULL)");
        }
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private static AgentRow peer(String id, String name, int sort) {
        return new AgentRow(id, name, false, null, null, null, sort, null, null, null, 0L, 0L,
                null, null);
    }

    @Test
    void allSilentTableIsRevivedByTheNextUserMessage() throws Exception {
        JdbcTemplate jdbc = memoryDb();
        RoundtableTopicStore topics = new RoundtableTopicStore(jdbc);
        TopicStateMachine machine = new TopicStateMachine(topics, new RoundtableTopicEventStore(jdbc));
        SpeakerStrategy strategy = new RotationSpeakerStrategy(topics);
        List<AgentRow> peers = List.of(peer("a", "甲", 0), peer("b", "乙", 1), peer("c", "丙", 2));

        TopicRow topic = machine.open("s1", RoundtableTopicStore.ORIGIN_USER, null);
        machine.seatAll(topic.id(), peers.stream().map(AgentRow::id).toList());
        for (AgentRow p : peers) {
            machine.silent(topic.id(), p.id(), "这一轮没什么要说的");
        }

        /* 僵局：话题还开着，但选人挑不到任何人——修复前，人再说话也不会有应答 */
        assertEquals(RoundtableTopicStore.STATUS_OPEN, topics.topic(topic.id()).orElseThrow().status());
        assertTrue(strategy.pickNext(topic.id(), peers, machine.states(topic.id()), "c").isEmpty());

        /* 人的新话开启新的一轮：沉默者全部回到活跃，选人重新挑得到人 */
        assertEquals(peers.size(), machine.reviveSilent(topic.id()));
        for (AgentRow p : peers) {
            assertEquals(PeerState.ACTIVE, machine.stateOf(topic.id(), p.id()));
        }
        assertTrue(strategy.pickNext(topic.id(), peers, machine.states(topic.id()), "c").isPresent());
    }

    @Test
    void reviveOnlyTouchesSilentMembers() throws Exception {
        JdbcTemplate jdbc = memoryDb();
        RoundtableTopicStore topics = new RoundtableTopicStore(jdbc);
        TopicStateMachine machine = new TopicStateMachine(topics, new RoundtableTopicEventStore(jdbc));
        List<AgentRow> peers = List.of(peer("a", "甲", 0), peer("b", "乙", 1));

        TopicRow topic = machine.open("s1", RoundtableTopicStore.ORIGIN_USER, null);
        machine.seatAll(topic.id(), peers.stream().map(AgentRow::id).toList());
        machine.silent(topic.id(), "a", null);
        machine.proposeEnd(topic.id(), "b", "没什么要补充的了");

        /* 只放回沉默者：已经交卷的人不该被一句话又拽回场上 */
        assertEquals(1, machine.reviveSilent(topic.id()));
        assertEquals(PeerState.ACTIVE, machine.stateOf(topic.id(), "a"));
        assertEquals(PeerState.PROPOSE_END, machine.stateOf(topic.id(), "b"));
    }
}
