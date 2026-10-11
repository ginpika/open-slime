package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 工具执行生命周期的回归（设计一 §五-2）：开始置 running、结束置终态、按幂等键可查。
 *
 * <p>「取消生成，不等于取消工具副作用」——每次工具调用都要有独立记录，结果不确定的先查状态。
 * 内存 SQLite，不起 Spring 上下文。
 */
class ToolExecutionStoreTest {

    /** 与 schema.sql 的 tool_execution 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE tool_execution(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT,
              task_id TEXT,
              tool TEXT NOT NULL,
              args TEXT,
              idem_key TEXT,
              status TEXT NOT NULL,
              cancellable INTEGER NOT NULL DEFAULT 0,
              result TEXT,
              error TEXT,
              started_at INTEGER NOT NULL,
              finished_at INTEGER)""";

    private static ToolExecutionStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return new ToolExecutionStore(jdbc);
    }

    @Test
    void runsThroughItsLifecycle() {
        ToolExecutionStore store = store();
        long id = store.start("s1", null, "search_documents_by_title", "{\"q\":\"x\"}", "k1", false);
        assertTrue(id > 0);

        ToolExecutionStore.ToolExecRow started = store.byIdemKey("k1").orElseThrow();
        assertEquals(ToolExecutionStore.STATUS_RUNNING, started.status());
        assertNull(started.finishedAt());

        store.finish(id, ToolExecutionStore.STATUS_DONE, "命中 2 条", null);
        ToolExecutionStore.ToolExecRow done = store.byIdemKey("k1").orElseThrow();
        assertEquals(ToolExecutionStore.STATUS_DONE, done.status());
        assertEquals("命中 2 条", done.result());
        assertNotNull(done.finishedAt());
    }

    @Test
    void recordsFailureWithItsReason() {
        ToolExecutionStore store = store();
        long id = store.start("s1", "t1", "run_command", "{\"cmd\":\"ls\"}", "k2", false);
        store.finish(id, ToolExecutionStore.STATUS_FAILED, null, "容器不可达");

        ToolExecutionStore.ToolExecRow row = store.byIdemKey("k2").orElseThrow();
        assertEquals(ToolExecutionStore.STATUS_FAILED, row.status());
        assertEquals("容器不可达", row.error());
        assertEquals("t1", row.taskId());
    }

    @Test
    void unknownIdemKeyIsEmpty() {
        assertTrue(store().byIdemKey("nope").isEmpty());
    }
}
