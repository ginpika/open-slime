package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import cc.ginpika.minis.config.LlmProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆事件流 M0 的回归冒烟（内存 SQLite，不起 Spring 上下文）：
 * 幂等去重、时间倒序召回、写入侧截断与失败隔离。
 */
class MemoryEventStoreTest {

    /** 与 schema.sql 的 memory_event 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE memory_event(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              event_key TEXT NOT NULL UNIQUE,
              kind TEXT NOT NULL,
              actor TEXT NOT NULL,
              title TEXT,
              payload TEXT,
              ref_type TEXT NOT NULL,
              ref_id INTEGER NOT NULL,
              created_at INTEGER NOT NULL)""";

    /** 未配置的 embedding 桩（M0 路径不触发向量补齐，见 MemoryRecallTest 的完整桩） */
    private static EmbeddingClient unconfiguredEmbedding() {
        return new EmbeddingClient(new LlmProps(), null, new ObjectMapper(), null) {
            @Override
            public boolean configured() {
                return false;
            }
        };
    }

    private static MemoryEventStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return new MemoryEventStore(jdbc);
    }

    @Test
    void dedupsByEventKey() {
        MemoryEventStore store = store();
        assertTrue(store.append("chat_message:1", "chat", "user", "会话", "你好",
                MemoryWriter.REF_CHAT_MESSAGE, 1));
        /* 同 key 重复 push（正本行 id 不变）应静默跳过 */
        assertFalse(store.append("chat_message:1", "chat", "user", "会话", "你好",
                MemoryWriter.REF_CHAT_MESSAGE, 1));
        assertEquals(1, store.recent(10).size());
        /* 不同正本行不受影响 */
        assertTrue(store.append("chat_message:2", "chat", "assistant", "会话", "在",
                MemoryWriter.REF_CHAT_MESSAGE, 2));
        assertEquals(2, store.recent(10).size());
    }

    @Test
    void recentOrdersNewestFirstEvenInSameMillisecond() {
        MemoryEventStore store = store();
        store.append("chat_message:1", "chat", "user", null, "第一条",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        store.append("sub_exec:9", "exec", "plan", "记忆体检", "结论",
                MemoryWriter.REF_SUB_EXEC, 9);
        store.append("chat_message:2", "chat", "assistant", null, "最后一条",
                MemoryWriter.REF_CHAT_MESSAGE, 2);
        /* 同毫秒内连写三行：created_at 并列时按 id 分先后，最新在前 */
        assertEquals("最后一条", store.recent(10).get(0).payload());
        assertEquals("sub_exec", store.recent(10).get(1).refType());
        assertEquals("第一条", store.recent(10).get(2).payload());
    }

    @Test
    void writerTruncatesPayloadAndSwallowsFailures() {
        MemoryEventStore store = store();
        MemoryWriter writer = new MemoryWriter(store,
                new MemoryEmbeddingWriter(store, null, unconfiguredEmbedding()));
        writer.onChatMessage(1, "user", "会话", "长".repeat(600));
        /* 投影截断：500 字 + 省略号 */
        assertEquals(501, store.recent(1).get(0).payload().length());

        /* 失败隔离：库没有 memory_event 表 → 写入抛错也绝不能冒泡到聊天/执行主流程 */
        SingleConnectionDataSource empty =
                new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        MemoryWriter broken = new MemoryWriter(new MemoryEventStore(new JdbcTemplate(empty)),
                new MemoryEmbeddingWriter(new MemoryEventStore(new JdbcTemplate(empty)), null,
                        unconfiguredEmbedding()));
        assertDoesNotThrow(() -> broken.onExecDone(1, "plan", "记忆体检", "结论"));
        assertDoesNotThrow(() -> broken.onChatMessage(1, "user", "会话", "你好"));
    }
}
