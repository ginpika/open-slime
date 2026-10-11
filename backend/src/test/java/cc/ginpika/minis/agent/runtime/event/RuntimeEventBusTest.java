package cc.ginpika.minis.agent.runtime.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 运行时事件总线的回归：幂等去重不重复广播、普通通知合并、用户消息绝不合并。
 *
 * <p>对应设计一 §五-3 的防事件风暴三条约定。内存 SQLite，不起 Spring 上下文。
 */
class RuntimeEventBusTest {

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
    void repeatedDeliveryBroadcastsOnce() {
        RuntimeEventStore store = store();
        RuntimeEventBus bus = new RuntimeEventBus(store);
        List<String> got = new ArrayList<>();
        bus.subscribe("s1", null, row -> {
            got.add(row.eventId());
            return true;
        });

        RuntimeEvent e = RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, "s1", "user", null, Map.of());
        bus.publish(e);
        bus.publish(e);   // 同一条再投一次

        assertEquals(1, got.size());
        assertEquals(1, store.recent("s1", 10).size());
    }

    @Test
    void coalescesPlainNotificationsButKeepsThemInTheLog() {
        RuntimeEventStore store = store();
        RuntimeEventBus bus = new RuntimeEventBus(store);
        List<String> got = new ArrayList<>();
        bus.subscribe("s1", null, row -> {
            got.add(row.eventId());
            return true;
        });

        bus.publish(RuntimeEvent.of(RuntimeEventType.TOOL_STARTED, "s1", "tool", null, Map.of()));
        bus.publish(RuntimeEvent.of(RuntimeEventType.TOOL_STARTED, "s1", "tool", null, Map.of()));

        /* 广播合并成一次；但事件正本一条不少（审计不丢） */
        assertEquals(1, got.size());
        assertEquals(2, store.recent("s1", 10).size());
    }

    @Test
    void neverCoalescesUserMessages() {
        RuntimeEventStore store = store();
        RuntimeEventBus bus = new RuntimeEventBus(store);
        List<String> got = new ArrayList<>();
        bus.subscribe("s1", null, row -> {
            got.add(row.eventId());
            return true;
        });

        bus.publish(RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, "s1", "user", null, Map.of()));
        bus.publish(RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, "s1", "user", null, Map.of()));

        assertEquals(2, got.size());
    }

    @Test
    void subscriptionIsScopedToItsSession() {
        RuntimeEventStore store = store();
        RuntimeEventBus bus = new RuntimeEventBus(store);
        List<String> got = new ArrayList<>();
        bus.subscribe("s1", null, row -> {
            got.add(row.sessionId());
            return true;
        });

        bus.publish(RuntimeEvent.of(RuntimeEventType.AGENT_MESSAGE, "s2", "p1", null, Map.of()));
        assertEquals(0, got.size());
        bus.publish(RuntimeEvent.of(RuntimeEventType.AGENT_MESSAGE, "s1", "p1", null, Map.of()));
        assertEquals(1, got.size());
    }
}
