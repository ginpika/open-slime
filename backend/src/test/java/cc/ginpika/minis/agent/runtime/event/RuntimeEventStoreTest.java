package cc.ginpika.minis.agent.runtime.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 运行时事件正本的回归：幂等追加、游标窗口、按任务回放。
 *
 * <p>对应设计一 §二「事件日志是事实来源」与 §五-3「同一事件重复投递不能重复执行副作用」。
 * 内存 SQLite，不起 Spring 上下文。
 */
class RuntimeEventStoreTest {

    /** 与 schema.sql 的 runtime_event 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE runtime_event(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              event_id TEXT NOT NULL,
              session_id TEXT NOT NULL,
              type TEXT NOT NULL,
              source TEXT,
              target TEXT,
              task_id TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              correlation_id TEXT,
              payload TEXT NOT NULL DEFAULT '{}',
              created_at INTEGER NOT NULL,
              UNIQUE(event_id))""";

    private static RuntimeEventStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return new RuntimeEventStore(jdbc, new ObjectMapper());
    }

    @Test
    void repeatedDeliveryIsIdempotent() {
        RuntimeEventStore store = store();
        RuntimeEvent e = RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, "s1", "user", null,
                Map.of("input", "你好"));
        RuntimeEventStore.AppendResult first = store.append(e);
        RuntimeEventStore.AppendResult second = store.append(e);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.id(), second.id());
        assertEquals(1, store.recent("s1", 10).size());
    }

    @Test
    void sinceReturnsOnlyRowsAfterCursor() {
        RuntimeEventStore store = store();
        long first = store.append(RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, "s1", "user", null,
                Map.of())).id();
        store.append(RuntimeEvent.of(RuntimeEventType.AGENT_MESSAGE, "s1", "p1", null, Map.of()));
        store.append(RuntimeEvent.of(RuntimeEventType.TOOL_STARTED, "s1", "tool", null, Map.of()));
        /* 另一个会话的事件不该混进来 */
        store.append(RuntimeEvent.of(RuntimeEventType.AGENT_MESSAGE, "s2", "p2", null, Map.of()));

        List<RuntimeEventStore.EventRow> rows = store.since("s1", first, 10);
        assertEquals(2, rows.size());
        assertEquals("agent_message".toUpperCase(), rows.get(0).type().toUpperCase());
    }

    @Test
    void byTaskRestoresOneLifecycle() {
        RuntimeEventStore store = store();
        store.append(new RuntimeEvent("e1", RuntimeEventType.TASK_CREATED, "s1", "runtime", null,
                "t1", 20, null, 1L, Map.of()));
        store.append(new RuntimeEvent("e2", RuntimeEventType.TASK_COMPLETED, "s1", "runtime", null,
                "t1", 20, null, 2L, Map.of()));
        store.append(new RuntimeEvent("e3", RuntimeEventType.TASK_CREATED, "s1", "runtime", null,
                "t2", 20, null, 3L, Map.of()));

        assertEquals(2, store.byTask("t1").size());
        assertEquals(1, store.byTask("t2").size());
    }
}
