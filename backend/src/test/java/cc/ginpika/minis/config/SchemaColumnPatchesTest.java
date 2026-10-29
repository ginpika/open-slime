package cc.ginpika.minis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 回归：结构补丁必须「补上缺列、保留数据、可重复执行」。
 *
 * <p>它修的是一个真实事故：{@code chat_message} 新增 {@code event_kind} 之后，老库（建表语句对已存在的表
 * 是空操作）在新代码下报 {@code no such column}。补列是唯一不动数据的修法，但必须幂等——
 * 每次启动都执行，第二次不能再报错，更不能动数据。
 */
class SchemaColumnPatchesTest {

    /** 一份「老结构」的 chat_message：没有 event_kind，且已经有一行老数据 */
    private static final String LEGACY_DDL = """
            CREATE TABLE chat_message (
              id         INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              role       TEXT NOT NULL,
              content    TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """;

    /** 一份「老结构」的 roundtable_todo：没有 depends_on（前置依赖列） */
    private static final String LEGACY_TODO_DDL = """
            CREATE TABLE roundtable_todo (
              id            TEXT PRIMARY KEY,
              session_id    TEXT NOT NULL,
              topic_id      TEXT,
              owner_peer    TEXT,
              title         TEXT NOT NULL,
              detail        TEXT,
              status        TEXT NOT NULL,
              created_by    TEXT,
              result        TEXT,
              block_reason  TEXT,
              seq           INTEGER NOT NULL,
              created_at    INTEGER NOT NULL,
              updated_at    INTEGER NOT NULL
            )
            """;

    @Test
    void addsMissingColumnKeepsRowsAndIsIdempotent() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                Statement statement = connection.createStatement()) {
            statement.execute(LEGACY_DDL);
            statement.execute(LEGACY_TODO_DDL);
            statement.execute("CREATE TABLE term_target(id TEXT PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO term_target(id, name) VALUES('legacy', '已有工作空间')");
            statement.execute("INSERT INTO chat_message(session_id, role, content, created_at) "
                    + "VALUES('s1','user','老数据',1)");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            SchemaColumnPatches patches = new SchemaColumnPatches(jdbc);

            patches.apply();

            assertTrue(columnsOf(connection, "term_target").contains("host_workdir"));
            assertEquals("已有工作空间", jdbc.queryForObject("SELECT name FROM term_target", String.class));
            assertNull(jdbc.queryForObject("SELECT host_workdir FROM term_target", String.class));
            assertTrue(columnsOf(connection, "chat_message").contains("event_kind"), "缺的列应被补上");
            assertTrue(columnsOf(connection, "roundtable_todo").contains("depends_on"),
                    "任务板的前置依赖列也应被补上");
            assertTrue(columnsOf(connection, "roundtable_todo").contains("priority"),
                    "任务板的优先级列也应被补上");
            /* 老数据一行不动，新列为 NULL——补列不该碰任何一行 */
            assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class));
            assertNull(jdbc.queryForObject("SELECT event_kind FROM chat_message", String.class));

            /* 再跑一次：已存在就跳过，不得抛（每次启动都会执行） */
            patches.apply();
            assertTrue(columnsOf(connection, "chat_message").contains("event_kind"));
        }
    }

    private static List<String> columnsOf(Connection connection, String table) throws Exception {
        List<String> names = new ArrayList<>();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }
}
