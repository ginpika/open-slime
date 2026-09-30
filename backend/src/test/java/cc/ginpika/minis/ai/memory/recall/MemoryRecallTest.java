package cc.ginpika.minis.ai.memory.recall;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.subconscious.SubconsciousInjectors;
import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.MemoryEmbeddingWriter;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.ai.memory.store.MemoryEventEmbeddingStore;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import cc.ginpika.minis.config.LlmProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆 M1（向量轴 + 融合读侧）的回归冒烟（内存 SQLite，不起 Spring 上下文）：
 * 向量覆盖式写入、push 后异步补齐、时间窗收敛 + 窗口内余弦打分、
 * 无 embedding 配置/调用失败的降级路径、补齐扫描入口、"memory" 注入器改道不回退。
 */
class MemoryRecallTest {

    private static final long DAY = 86_400_000L;

    private static final String STUB_MODEL = "stub-model";

    /**
     * 一次测试的全部件：内存库两张表 + 可翻转行为的 EmbeddingClient 桩
     * （覆写 configured/model/embed，绕开真实配置；embedCalls 供幂等断言）。
     */
    private static final class Fixture {
        final JdbcTemplate jdbc;
        final MemoryEventStore events;
        final MemoryEventEmbeddingStore vectors;
        final AtomicInteger embedCalls = new AtomicInteger();
        boolean configured = true;
        boolean failEmbed = false;
        Function<String, float[]> embedder = t -> new float[]{1f, 0f};

        Fixture() {
            SingleConnectionDataSource ds =
                    new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
            jdbc = new JdbcTemplate(ds);
            jdbc.execute("""
                    CREATE TABLE memory_event(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      event_key TEXT NOT NULL UNIQUE,
                      kind TEXT NOT NULL,
                      actor TEXT NOT NULL,
                      title TEXT,
                      payload TEXT,
                      ref_type TEXT NOT NULL,
                      ref_id INTEGER NOT NULL,
                      created_at INTEGER NOT NULL)""");
            jdbc.execute("""
                    CREATE TABLE memory_event_embedding(
                      event_id INTEGER PRIMARY KEY,
                      model TEXT NOT NULL,
                      dim INTEGER NOT NULL,
                      vec BLOB NOT NULL,
                      updated_at INTEGER NOT NULL)""");
            events = new MemoryEventStore(jdbc);
            vectors = new MemoryEventEmbeddingStore(jdbc);
        }

        EmbeddingClient client() {
            return new EmbeddingClient(new LlmProps(), null, new ObjectMapper(), null) {
                @Override
                public boolean configured() {
                    return configured;
                }

                @Override
                public String model() {
                    return STUB_MODEL;
                }

                @Override
                public Optional<float[]> embed(String text) {
                    if (failEmbed) {
                        throw new IllegalStateException("stub: embedding 调用失败");
                    }
                    embedCalls.incrementAndGet();
                    return Optional.of(embedder.apply(text));
                }
            };
        }

        MemoryEmbeddingWriter embeddingWriter() {
            return new MemoryEmbeddingWriter(events, vectors, client());
        }

        MemoryRecall recall() {
            return new MemoryRecall(events, vectors, client());
        }

        void setCreatedAt(long id, long at) {
            jdbc.update("UPDATE memory_event SET created_at = ? WHERE id = ?", at, id);
        }
    }

    /** 轮询等待异步补齐落库（虚拟线程短任务，2 秒余量足够） */
    private static void awaitTrue(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("异步补齐超时未落库");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void embeddingSaveUpsertsByPrimaryKey() {
        Fixture f = new Fixture();
        f.vectors.save(1, "m", new float[]{1f, 0f});
        f.vectors.save(1, "m", new float[]{0f, 1f});
        /* 同事件重算/换模型都整行覆盖：PK 是 event_id，始终只有一行 */
        f.vectors.save(1, "other", new float[]{1f, 1f});
        Long count = f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_event_embedding", Long.class);
        assertEquals(1L, count);
        assertArrayEquals(new float[]{1f, 1f},
                f.vectors.byEventIds("other", List.of(1L)).get(1L), 1e-6f);
        /* 模型不符 = 无向量（同模型余弦的语义） */
        assertTrue(f.vectors.byEventIds("m", List.of(1L)).isEmpty());
    }

    @Test
    void pushComplementsEmbeddingAsyncAndIdempotent() throws InterruptedException {
        Fixture f = new Fixture();
        MemoryWriter writer = new MemoryWriter(f.events, f.embeddingWriter());
        writer.onChatMessage(7, "user", "会话", "帮我讲讲线性代数");
        /* 落库成功 → 异步补向量（event 行 id=1） */
        awaitTrue(() -> f.vectors.byEventIds(STUB_MODEL, List.of(1L)).containsKey(1L));
        /* 幂等重放：append 被忽略，不再花第二次 embedding 调用 */
        writer.onChatMessage(7, "user", "会话", "帮我讲讲线性代数");
        assertEquals(1, f.embedCalls.get());
    }

    @Test
    void recallScoresCosineInsideWindowOnly() {
        Fixture f = new Fixture();
        f.events.append("chat_message:1", "chat", "user", null, "线性代数怎么复习",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        f.events.append("chat_message:2", "chat", "assistant", "闲聊", "晚饭吃火锅吧",
                MemoryWriter.REF_CHAT_MESSAGE, 2);
        f.events.append("sub_exec:3", "exec", "plan", "旧计划", "线性代数总结",
                MemoryWriter.REF_SUB_EXEC, 3);
        long now = System.currentTimeMillis();
        f.setCreatedAt(1, now - DAY);
        f.setCreatedAt(2, now - 1_000);
        f.setCreatedAt(3, now - 30 * DAY);
        f.vectors.save(1, STUB_MODEL, new float[]{1f, 0f});
        f.vectors.save(2, STUB_MODEL, new float[]{0f, 1f});
        /* 窗外事件即使语义最相关也进不了候选：时序轴只做过滤，但过滤必须先于打分 */
        f.vectors.save(3, STUB_MODEL, new float[]{1f, 0f});
        f.embedder = t -> t.contains("线性代数") ? new float[]{1f, 0f} : new float[]{0f, 1f};

        MemoryRecall recall = f.recall();
        List<MemoryRecall.Hit> hits = recall.recall("线性代数", now - 7 * DAY, null, null, null, 10);
        assertEquals(2, hits.size());
        /* 相关的旧事件压过无关的新事件；无关者零分垫底 */
        assertEquals("线性代数怎么复习", hits.get(0).event().payload());
        assertTrue(hits.get(0).score() > 0.99f);
        assertEquals(0f, hits.get(1).score(), 0f);

        /* kind/actor 过滤走时序轴的窗口收敛，不另设一路 */
        List<MemoryRecall.Hit> execs =
                recall.recall("线性代数", null, null, "exec", "plan", 10);
        assertEquals(1, execs.size());
        assertEquals("旧计划", execs.get(0).event().title());
    }

    @Test
    void recallDegradesToTimeOrderWithoutUsableVector() {
        Fixture f = new Fixture();
        f.events.append("chat_message:1", "chat", "user", null, "第一条",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        f.events.append("chat_message:2", "chat", "assistant", "会话", "最新一条",
                MemoryWriter.REF_CHAT_MESSAGE, 2);
        f.vectors.save(1, STUB_MODEL, new float[]{1f, 0f});
        f.vectors.save(2, STUB_MODEL, new float[]{0f, 1f});

        /* 降级一：embedding 已配置但调用失败 → 纯时间倒序，读路径不抛错 */
        MemoryRecall recall = f.recall();
        f.failEmbed = true;
        List<MemoryRecall.Hit> onFail =
                assertDoesNotThrow(() -> recall.recall("随便什么", null, null, null, null, 10));
        assertEquals("最新一条", onFail.get(0).event().payload());
        assertEquals(0f, onFail.get(0).score(), 0f);
        f.failEmbed = false;

        /* 降级二：未配置 → 不发起调用，同样纯时间倒序 */
        f.configured = false;
        int callsBefore = f.embedCalls.get();
        List<MemoryRecall.Hit> bare = f.recall().recall("随便什么", null, null, null, null, 10);
        assertEquals("最新一条", bare.get(0).event().payload());
        assertEquals(callsBefore, f.embedCalls.get());

        /* 降级三：query 为空（注入器的主会话路径）→ 等价于 recent(n) */
        f.configured = true;
        List<MemoryRecall.Hit> noQuery = f.recall().recall("  ", null, null, null, null, 1);
        assertEquals(1, noQuery.size());
        assertEquals("最新一条", noQuery.get(0).event().payload());
    }

    @Test
    void backfillScansMissingAndStaleModelRows() {
        Fixture f = new Fixture();
        f.events.append("chat_message:1", "chat", "user", "会话", "欠账的旧事件",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        f.events.append("sub_exec:2", "exec", "plan", "计划", "结论",
                MemoryWriter.REF_SUB_EXEC, 2);
        /* 旧模型留下的行不算「已有向量」，重算覆盖 */
        f.vectors.save(2, "old-model", new float[]{1f, 0f});

        MemoryEmbeddingWriter writer = f.embeddingWriter();
        assertEquals(2, writer.backfill(16));
        assertEquals(2, f.vectors.byEventIds(STUB_MODEL, List.of(1L, 2L)).size());
        /* 补齐后无欠账 */
        assertEquals(0, writer.backfill(16));
        Long count = f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_event_embedding", Long.class);
        assertEquals(2L, count);

        /* 未配置 → 直接 0，不发起调用 */
        f.configured = false;
        assertEquals(0, writer.backfill(16));
    }

    @Test
    void injectorRerouteKeepsM0BehaviorAndGainsSemanticOrder() {
        Fixture f = new Fixture();
        f.events.append("chat_message:1", "chat", "user", "会话", "记忆维护注意事项",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        f.events.append("chat_message:2", "chat", "assistant", "闲聊", "晚饭吃火锅吧",
                MemoryWriter.REF_CHAT_MESSAGE, 2);
        long now = System.currentTimeMillis();
        f.setCreatedAt(1, now - DAY);
        f.setCreatedAt(2, now - 1_000);
        f.vectors.save(1, STUB_MODEL, new float[]{1f, 0f});
        f.vectors.save(2, STUB_MODEL, new float[]{0f, 1f});
        f.embedder = t -> t.contains("记忆维护") ? new float[]{1f, 0f} : new float[]{0f, 1f};

        /* documents/chunks 两个重依赖在 memory() 路径不会被触碰，占位 null 即可 */
        SubconsciousInjectors injectors = new SubconsciousInjectors(f.jdbc, null, null,
                new GraphStore(f.jdbc, new ObjectMapper()), new PlanStore(f.jdbc),
                new ExecStore(f.jdbc), new ChatMessageStore(f.jdbc), f.recall());

        /* 主会话路径（无计划）+ 未配置向量 = M0 行为：时间倒序、行格式不变 */
        f.configured = false;
        String m0 = injectors.load("slot", "memory", "10", null);
        assertTrue(m0.startsWith("- "));
        assertTrue(m0.contains("〔用户·会话〕"));
        assertTrue(m0.indexOf("晚饭吃火锅吧") < m0.indexOf("记忆维护注意事项"));

        /* 计划路径 + 已配置：与计划正文语义相关的旧事件浮到首位 */
        f.configured = true;
        PlanStore.PlanRow plan = new PlanStore.PlanRow("p1", "记忆维护", "system",
                "负责记忆维护的编制", null, null, null, null, null, null, 0, null);
        String m1 = injectors.load("slot", "memory", "10", plan);
        assertTrue(m1.indexOf("记忆维护注意事项") < m1.indexOf("晚饭吃火锅吧"));
    }
}
