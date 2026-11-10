package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 回归：发言欲望（desire）的两个缺陷——「漏报取上界」与「旧值永不过期」。
 *
 * <p>修复前，{@code DEFAULT_DESIRE = 10} 等于把「没表态」当成「最想说话」；而 {@code latestDesires}
 * 只按事件序折叠、不划轮次，于是上一轮报过高分、这一轮漏报的人会继续以旧高分排在队首。
 * 两者叠加的结果是：被优先叫到的人可能根本不想说话——选人的依据是假的。
 *
 * <p>现在欲望的有效期只有一轮：人的一句新话开启新一轮时，{@link TopicStateMachine#turnStart}
 * 落下水位线，之前的欲望全部作废（见 {@link TopicStateMachine#latestDesires}）。
 */
class DesireTurnScopedTest {

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
        return new AgentRow(id, name, false, null, null, sort, null, null, null, 0L, 0L,
                null, null);
    }

    /** 一套装好的舞台：话题已开、两位成员已入场 */
    private record Stage(TopicStateMachine machine, TopicRow topic, List<AgentRow> peers) {
    }

    private static Stage stage() throws Exception {
        JdbcTemplate jdbc = memoryDb();
        RoundtableTopicStore topics = new RoundtableTopicStore(jdbc);
        TopicStateMachine machine = new TopicStateMachine(topics, new RoundtableTopicEventStore(jdbc));
        List<AgentRow> peers = List.of(peer("a", "甲", 0), peer("b", "乙", 1));
        TopicRow topic = machine.open("s1", RoundtableTopicStore.ORIGIN_USER, null);
        machine.seatAll(topic.id(), peers.stream().map(AgentRow::id).toList());
        return new Stage(machine, topic, peers);
    }

    @Test
    void missedReportFallsToNeutralNotToTop() {
        /* 漏报是常态：它必须落在「想说的」之后、而不是与「最想说的」平手 */
        assertEquals(5, DesireSpeakerStrategy.DEFAULT_DESIRE);
        assertTrue(DesireSpeakerStrategy.DEFAULT_DESIRE < DesireSpeakerStrategy.MAX_DESIRE);
    }

    @Test
    void previousRoundDesireIsDiscardedByTurnStart() throws Exception {
        Stage s = stage();
        s.machine().desire(s.topic().id(), "a", 10);
        s.machine().desire(s.topic().id(), "b", 0);
        assertEquals(10, s.machine().latestDesires(s.topic().id()).get("a"));

        /* 人的新话开启新一轮：上一轮的欲望全部作废 */
        s.machine().turnStart(s.topic().id());
        assertTrue(s.machine().latestDesires(s.topic().id()).isEmpty(),
                "新一轮开始后，上一轮报出的欲望不得继续有效");

        /* 本轮只有 b 表态：a 不在表里（取用时按默认值兜底，而不是沿用旧高分） */
        s.machine().desire(s.topic().id(), "b", 9);
        assertEquals(9, s.machine().latestDesires(s.topic().id()).get("b"));
        assertNull(s.machine().latestDesires(s.topic().id()).get("a"));
    }

    @Test
    void staleHighDesireNoLongerWinsTheNextRound() throws Exception {
        Stage s = stage();
        /* 上一轮：a 报满分、b 报 9；新一轮：a 漏报、b 仍报 9 */
        s.machine().desire(s.topic().id(), "a", 10);
        s.machine().desire(s.topic().id(), "b", 9);
        s.machine().turnStart(s.topic().id());
        s.machine().desire(s.topic().id(), "b", 9);

        DesireSpeakerStrategy strategy = new DesireSpeakerStrategy(s.machine());
        Optional<AgentRow> next = strategy.pickNext(s.topic().id(), s.peers(),
                s.machine().states(s.topic().id()), "zzz");

        /* 修复前 a 以旧高分胜出；修复后 a 回落到中性 5，本轮真报了 9 的 b 先开口 */
        assertEquals("b", next.orElseThrow().id());
    }

    @Test
    void turnStartIsTransparentToStateAndParticipants() throws Exception {
        Stage s = stage();
        s.machine().turnStart(s.topic().id());

        /* 水位线事件不带任何跃迁：不改状态、不改参与者、不冒充举手 */
        assertEquals(PeerState.ACTIVE, s.machine().stateOf(s.topic().id(), "a"));
        assertEquals(List.of("a", "b"), s.machine().participants(s.topic().id()));
        assertFalse(s.machine().lastSpeaker(s.topic().id()).isPresent());
        assertTrue(s.machine().handRaiser(s.topic().id()).isEmpty());
    }
}
