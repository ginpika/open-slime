package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 会话事件总线（STEP 4 回流改推送）的最小回归：内存 SQLite，不起 Spring 上下文。
 *
 * <p>钉住三件事：发布即落库并广播给在线订阅者；退订后不再收到；断线重连按 {@code afterId}
 * 把漏掉的补推一遍（这是「推送」相对「轮询」不丢事件的关键）。
 */
class SessionEventBusTest {

    /** 与 schema.sql 的 session_events 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE session_events(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              hub_session_id TEXT NOT NULL,
              seq INTEGER NOT NULL,
              kind TEXT NOT NULL,
              event_id TEXT,
              payload TEXT NOT NULL,
              ts INTEGER NOT NULL,
              UNIQUE (hub_session_id, event_id))""";

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return jdbc;
    }

    @Test
    void publishesToLiveSubscribersAndPersists() {
        JdbcTemplate jdbc = db();
        SessionEventBus bus = new SessionEventBus(new SessionEventStore(jdbc), new ObjectMapper());
        List<SessionEventStore.EventRow> got = new ArrayList<>();

        Runnable unsubscribe = bus.subscribe(null, row -> {
            got.add(row);
            return true;
        });
        long id = bus.publish(new SessionEventBus.Event("hub-1",
                SessionEventBus.KIND_DELIVERED, true, "会话 A"));

        assertTrue(id > 0);
        assertEquals(1, got.size());
        assertEquals("hub-1", got.get(0).sessionId());
        assertTrue(got.get(0).payload().contains("delivered"), got.get(0).payload());

        /* 退订后不再收到 */
        unsubscribe.run();
        bus.publish(new SessionEventBus.Event("hub-2", SessionEventBus.KIND_ANSWERED, false, null));
        assertEquals(1, got.size());
    }

    @Test
    void replaysOnlyEventsAfterCursor() {
        JdbcTemplate jdbc = db();
        SessionEventStore store = new SessionEventStore(jdbc);
        SessionEventBus bus = new SessionEventBus(store, new ObjectMapper());

        long first = bus.publish(new SessionEventBus.Event("hub-1",
                SessionEventBus.KIND_DELIVERED, true, "会话 A"));
        long second = bus.publish(new SessionEventBus.Event("hub-1",
                SessionEventBus.KIND_ANSWERED, false, "会话 A"));

        /* 拿 first 当游标重连：只补 second（断线期间漏掉的那条） */
        List<SessionEventStore.EventRow> replayed = new ArrayList<>();
        bus.subscribe(first, row -> {
            replayed.add(row);
            return true;
        });
        assertEquals(1, replayed.size());
        assertEquals(second, replayed.get(0).id());

        /* 从头订阅（afterId=null）不补推存量：只挂实时 */
        List<SessionEventStore.EventRow> none = new ArrayList<>();
        bus.subscribe(null, row -> {
            none.add(row);
            return true;
        });
        assertTrue(none.isEmpty());
    }
}
