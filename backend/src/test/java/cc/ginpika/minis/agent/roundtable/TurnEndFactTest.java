package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 回归：本轮结束要留下<b>客观清单</b>，且它必须是「只记事实」的。
 *
 * <p>这是「它为什么没说话」的第一手材料：清单说「谁发了言、是不是被次数上限打断」，
 * 而「谁没说、为什么」由各成员此刻的状态给出（沉默 = 自己不说、提议结束 = 投了票、活跃 = 没轮到）。
 * 把推断写成事实比不记更糟，所以这里把「清单可读」「不改状态」两件事都钉住。
 */
class TurnEndFactTest {

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

    @Test
    void turnEndRecordsTheSummaryWithoutTouchingState() throws Exception {
        JdbcTemplate jdbc = memoryDb();
        RoundtableTopicStore topics = new RoundtableTopicStore(jdbc);
        RoundtableTopicEventStore events = new RoundtableTopicEventStore(jdbc);
        TopicStateMachine machine = new TopicStateMachine(topics, events);

        TopicRow topic = machine.open("s1", RoundtableTopicStore.ORIGIN_USER, null);
        machine.seatAll(topic.id(), List.of("a", "b"));
        machine.silent(topic.id(), "b", "这一轮没什么要说的");
        machine.turnStart(topic.id());

        machine.turnEnd(topic.id(), "本轮结束：甲 发言（发言次数已达上限）");

        List<RoundtableTopicEventStore.EventRow> timeline = machine.timeline(topic.id());
        RoundtableTopicEventStore.EventRow last = timeline.get(timeline.size() - 1);
        assertEquals(RoundtableTopicEventStore.KIND_TURN_END, last.kind());
        assertEquals("本轮结束：甲 发言（发言次数已达上限）", last.content());
        assertEquals(RoundtableTopicEventStore.ACTOR_USER, last.actor());

        /* 清单不带任何跃迁：不改状态、不改参与者——前者才是「为什么没说」的依据 */
        assertEquals(PeerState.ACTIVE, machine.stateOf(topic.id(), "a"));
        assertEquals(PeerState.SILENT, machine.stateOf(topic.id(), "b"));
        assertEquals(List.of("a", "b"), machine.participants(topic.id()));
        assertTrue(machine.latestDesires(topic.id()).isEmpty());
    }
}
