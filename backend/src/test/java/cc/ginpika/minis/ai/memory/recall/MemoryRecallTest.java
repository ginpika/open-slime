package cc.ginpika.minis.ai.memory.recall;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        f.events.append("task:3", "exec", "plan", "旧计划", "线性代数总结",
                MemoryWriter.REF_TASK, 3);
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
        f.events.append("task:2", "exec", "plan", "计划", "结论",
                MemoryWriter.REF_TASK, 2);
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
    void queryDrivesSemanticOrderWhileBlankFallsBackToTimeOrder() {
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
        MemoryRecall recall = f.recall();

        /* 空 query（注入器退役后由调用方直接决定）：纯时间倒序，最新的在前 */
        List<MemoryRecall.Hit> timeOrder = recall.recall("  ", null, null, null, null, 10);
        assertEquals("晚饭吃火锅吧", timeOrder.get(0).event().payload());

        /* 语义 query：与它相关的旧事件浮到首位（query 由调用方传入，不再经注入器） */
        List<MemoryRecall.Hit> semantic = recall.recall("负责记忆维护", null, null, null, null, 10);
        assertEquals("记忆维护注意事项", semantic.get(0).event().payload());
    }

    /** 默认召回挡掉后台产出（心跳 / 职责 / 子代理）；显式点名来源时以它为准、排除不再生效 */
    @Test
    void defaultRecallExcludesBackgroundButExplicitActorWins() {
        Fixture f = new Fixture();
        f.events.append("chat_message:1", "chat", "user", "会话", "你说过的话",
                MemoryWriter.REF_CHAT_MESSAGE, 1);
        f.events.append("task:1", "exec", "plan", "记忆体检", "职责跑出来的结论",
                MemoryWriter.REF_TASK, 1);
        f.events.append("task:2", "exec", "agent", "子代理", "子代理跑出来的结论",
                MemoryWriter.REF_TASK, 2);
        MemoryRecall recall = f.recall();

        /* 不指定来源：只剩前台那条——「你跟我说过什么」不被后台产出挤占 */
        List<MemoryRecall.Hit> foreground = recall.recall("说了什么", null, null, null, null, 10);
        assertEquals(1, foreground.size());
        assertEquals("user", foreground.get(0).event().actor());

        /* 显式点名 plan：以它为准 */
        List<MemoryRecall.Hit> plans = recall.recall("结论", null, null, null, "plan", 10);
        assertEquals(1, plans.size());
        assertEquals("plan", plans.get(0).event().actor());

        /* 排除表为空 = 全都要（后台的也回来） */
        assertEquals(3, f.events.window(null, null, null, null, List.of(), 10).size());
    }
}
