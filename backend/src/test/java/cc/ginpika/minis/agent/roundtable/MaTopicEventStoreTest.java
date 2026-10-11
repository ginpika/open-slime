package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * SQLite 取值类型的回归：同一个 {@code INTEGER} 列，小整数回 {@code Integer}、毫秒时间戳回
 * {@code Long}，所以 {@code (Long) rs.getObject("message_id")} 这种写法一碰小整数就抛
 * {@code ClassCastException}（多智能体事件表按 message_id 指回消息行时实际炸过一次）。
 *
 * <p>这里把「有小整数 id」与「id 为 NULL」两种形态都走一遍，钉住 {@link RoundtableRows#nullableLong}
 * 的收口——不起 Spring 上下文，纯内存库直接过一遍读写。
 */
class MaTopicEventStoreTest {

    private static final String DDL = """
            CREATE TABLE roundtable_topic_event (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              topic_id    TEXT NOT NULL,
              seq         INTEGER NOT NULL,
              actor       TEXT NOT NULL,
              kind        TEXT NOT NULL,
              from_state  TEXT,
              to_state    TEXT,
              peer_id     TEXT,
              content     TEXT,
              message_id  INTEGER,
              created_at  INTEGER NOT NULL
            )
            """;

    @Test
    void smallIntegerMessageIdAndNullBothReadBack() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute(DDL);
            RoundtableTopicEventStore store = new RoundtableTopicEventStore(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            /* 第一次发言：message_id 是小整数（正是当初炸掉的那种值） */
            store.append("t1", "peer-a", RoundtableTopicEventStore.KIND_SPEECH, "SPEAKING", "SPEAKING",
                    null, "说了一句话", 7L);
            /* 第二件事：message_id 为 NULL（要能保持「没有」而不是折成 0） */
            store.append("t1", "peer-a", RoundtableTopicEventStore.KIND_SILENT, "ACTIVE", "SILENT",
                    "peer-a", null, null);

            List<RoundtableTopicEventStore.EventRow> rows = store.byTopic("t1");
            assertEquals(2, rows.size());
            assertEquals(1, rows.get(0).seq());
            assertEquals(7L, rows.get(0).messageId());
            assertEquals("说了一句话", rows.get(0).content());
            assertEquals(2, rows.get(1).seq());
            assertNull(rows.get(1).messageId());
        }
    }
}
