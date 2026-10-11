package cc.ginpika.minis.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 子代理共享黑板（STEP 4 协作）的最小回归：内存 SQLite，不起 Spring 上下文。
 * 只增不改写、按会话隔离、读最近 N 条且按时间正序返回（读起来是「一路查到这」的次序）。
 */
class ScratchpadStoreTest {

    /** 与 schema.sql 的 subagent_scratch 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE subagent_scratch(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              author TEXT,
              exec_id INTEGER,
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL)""";

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return jdbc;
    }

    @Test
    void appendsAndReadsRecentInChronologicalOrder() {
        ScratchpadStore pad = new ScratchpadStore(db());
        pad.append("hub-1", "甲", 1L, "查到 X");
        pad.append("hub-1", "乙", 2L, "查到 Y");
        pad.append("hub-1", "丙", 3L, "查到 Z");

        /* 取最近 2 条：返回的是最后两条，且按时间正序（最早在前） */
        List<ScratchpadStore.Entry> recent = pad.recent("hub-1", 2);
        assertEquals(2, recent.size());
        assertEquals("查到 Y", recent.get(0).content());
        assertEquals("查到 Z", recent.get(1).content());
        assertEquals(3L, recent.get(1).execId());

        /* 按会话隔离 */
        assertTrue(pad.recent("hub-2", 5).isEmpty());
    }

    @Test
    void ignoresBlankSessionOrContent() {
        ScratchpadStore pad = new ScratchpadStore(db());
        pad.append(null, "甲", 1L, "内容");
        pad.append("hub-1", "甲", 1L, "  ");
        assertTrue(pad.recent("hub-1", 5).isEmpty());
        assertTrue(pad.recent(null, 5).isEmpty());
    }
}
