package cc.ginpika.minis.ai.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.config.MemoryProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆条目存储 + 条目召回的回归（内存 SQLite，不起 Spring 上下文）：
 * 「仍有效」过滤、级联删向量、相关性门控（固定条目恒在、低分被挡）。
 */
class MemoryItemStoreTest {

    private static final String DDL = """
            CREATE TABLE graph_claim(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              agent_id TEXT, scope TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 0.5, source TEXT NOT NULL DEFAULT 'auto',
              pinned INTEGER NOT NULL DEFAULT 0, valid_from INTEGER NOT NULL,
              valid_until INTEGER, superseded_by INTEGER, source_refs TEXT,
              created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
              user_id TEXT, owner TEXT, domain TEXT, layer TEXT, project_id TEXT, session_id TEXT,
              task_id INTEGER, producer_agent TEXT, source_kind TEXT, evidence_level TEXT,
              status TEXT, retention TEXT, version INTEGER, fingerprint TEXT,
              valid_at INTEGER, invalid_at INTEGER, learned_at INTEGER, expired_at INTEGER,
              identity TEXT, promoted_from INTEGER, expires_soft INTEGER)
            CREATE TABLE memory_item_embedding(item_version INTEGER,
              item_id INTEGER PRIMARY KEY, model TEXT NOT NULL, dim INTEGER NOT NULL,
              vec BLOB NOT NULL, updated_at INTEGER NOT NULL)""";

    private static JdbcTemplate jdbc() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        try { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(ds.getConnection(),new org.springframework.core.io.ClassPathResource("schema.sql")); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return j;
    }

    /** 桩：configured=true、固定模型名、embed 恒返回给定向量 */
    private static EmbeddingClient embedder(String model, float[] vec) {
        return new EmbeddingClient(new LlmProps(), null, new ObjectMapper(), null) {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public String model() {
                return model;
            }

            @Override
            public Optional<float[]> embed(String text) {
                return Optional.of(vec);
            }
        };
    }

    @Test
    void activeExcludesSupersededAndExpired() {
        JdbcTemplate j = jdbc();
        MemoryItemStore store = new MemoryItemStore(j);
        long keep = store.insert(null, "profile", "t", "有效", 0.9, "auto", null, null);
        long superseded = store.insert(null, "profile", "t", "被取代", 0.9, "auto", null, null);
        store.supersede(superseded, keep);
        /* 已过期（valid_until 早于现在） */
        long expired = store.insert(null, "project", "t", "已过期", 0.9, "auto",
                System.currentTimeMillis() - 1000, null);

        List<MemoryItemStore.ItemRow> active = store.active(10);

        assertTrue(active.stream().anyMatch(r -> r.id() == keep));
        assertTrue(active.stream().noneMatch(r -> r.id() == superseded));
        assertTrue(active.stream().noneMatch(r -> r.id() == expired));
    }

    @Test
    void recallGatesLowScoreButKeepsPinned() {
        JdbcTemplate j = jdbc();
        MemoryItemStore store = new MemoryItemStore(j);
        MemoryItemEmbeddingStore vectors = new MemoryItemEmbeddingStore(j);
        long pinned = store.insert(null, "directive", "t", "固定条目", 0.9, "manual", null, null);
        store.setPinned(pinned, true);
        /* 普通条目无向量 → 分为 0 → 低于门控阈值，被挡掉 */
        store.insert(null, "method", "t", "普通条目", 0.9, "auto", null, null);

        MemoryProps props = new MemoryProps();
        props.setMinScore(0.35);
        MemoryItemRecall recall = new MemoryItemRecall(store, vectors,
                embedder("m", new float[]{1f, 0f}), props);

        List<MemoryItemRecall.Hit> hits = recall.recall("随便什么", null, 10);

        assertEquals(1, hits.size());
        assertEquals(pinned, hits.get(0).item().id());
    }

    @Test
    void recallReturnsHighScoreItem() {
        JdbcTemplate j = jdbc();
        MemoryItemStore store = new MemoryItemStore(j);
        MemoryItemEmbeddingStore vectors = new MemoryItemEmbeddingStore(j);
        long id = store.insert(null, "profile", "t", "命中条目", 0.9, "auto", null, null);
        vectors.save(id, "m", new float[]{1f, 0f});

        MemoryProps props = new MemoryProps();
        props.setMinScore(0.35);
        MemoryItemRecall recall = new MemoryItemRecall(store, vectors,
                embedder("m", new float[]{1f, 0f}), props);

        List<MemoryItemRecall.Hit> hits = recall.recall("查询", null, 10);

        assertEquals(1, hits.size());
        assertEquals(id, hits.get(0).item().id());
    }

    @Test
    void deleteCascadesEmbedding() {
        JdbcTemplate j = jdbc();
        MemoryItemStore store = new MemoryItemStore(j);
        MemoryItemEmbeddingStore vectors = new MemoryItemEmbeddingStore(j);
        long id = store.insert(null, "profile", "t", "待删", 0.9, "auto", null, null);
        vectors.save(id, "m", new float[]{1f});

        store.delete(id);

        assertTrue(store.findById(id).isEmpty());
        assertEquals(0, vectors.byItemIds("m", List.of(id)).size());
    }
}
