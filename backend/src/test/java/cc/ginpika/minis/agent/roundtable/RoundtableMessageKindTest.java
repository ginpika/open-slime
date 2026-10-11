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
 * 回归：状态行要带上<b>事件种类</b>（{@code chat_message.event_kind}）。
 *
 * <p>「文案不是协议」——界面原先靠匹配中文（「入场」「退场」……）判断一行是什么、该居中还是收起，
 * 后端改一个字界面就静默错位。kind 落库之后，界面按它分类，文案只是显示形态。
 */
class RoundtableMessageKindTest {

    private static final String DDL = """
            CREATE TABLE chat_message (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id  TEXT NOT NULL,
              role        TEXT NOT NULL,
              content     TEXT NOT NULL,
              reasoning   TEXT,
              trace       TEXT,
              tool_rounds TEXT,
              kind        TEXT,
              meta        TEXT,
              topic_id    TEXT,
              peer_id     TEXT,
              peer_name   TEXT,
              context     TEXT,
              event_seq   INTEGER,
              event_kind  TEXT,
              created_at  INTEGER NOT NULL
            )
            """;

    @Test
    void eventRowsCarryTheirKindAndPeerRowsDoNot() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute(DDL);
            RoundtableMessageStore store = new RoundtableMessageStore(
                    new JdbcTemplate(new SingleConnectionDataSource(connection, true)));

            store.insert("s1", "t1", RoundtableMessageStore.ROLE_USER, null, null, "大家怎么看", null,
                    null, null, null);
            store.insertEvent("s1", "t1", 7, "peer-a", "甲", "本轮结束：甲 发言", "turn_end");
            store.insert("s1", "t1", RoundtableMessageStore.ROLE_PEER, "peer-a", "甲", "我说两句", null,
                    null, null, null);

            List<RoundtableMessageStore.MessageRow> rows = store.byTopic("t1");
            assertEquals(3, rows.size());
            /* 人的话与成员发言不是状态事件：没有种类 */
            assertNull(rows.get(0).eventKind());
            assertEquals("turn_end", rows.get(1).eventKind());
            assertNull(rows.get(2).eventKind());
            assertEquals("本轮结束：甲 发言", rows.get(1).content());
            assertEquals(7, store.maxEventSeq("t1"));
        }
    }
}
