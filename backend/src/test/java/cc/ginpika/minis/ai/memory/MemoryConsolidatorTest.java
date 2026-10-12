package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆巩固的回归（内存 SQLite）：去重（取代重复，保留最新）、情景层裁剪。
 */
class MemoryConsolidatorTest {

    private static final String DDL = """
            CREATE TABLE memory_item(
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

    private static MemoryItemStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        return new MemoryItemStore(j);
    }

    @Test
    void dedupeSupersedesDuplicatesKeepingNewest() {
        MemoryItemStore store = store();
        /* v5：去重只作用于非 protected 条目——用 agent 私有 scope（fact），不用受保护的画像 */
        long first = store.insert(null, "fact", "t", "同一句话", 0.9, "auto", null, null);
        long second = store.insert(null, "fact", "t", "同一句话", 0.9, "auto", null, null);
        MemoryProps props = new MemoryProps();
        props.setEpisodeMax(50);

        int changed = new MemoryConsolidator(props, store).consolidate();

        assertEquals(1, changed);
        /* 新的（second）保留，旧的（first）被取代 */
        assertNotNull(store.findById(first).orElseThrow().supersededBy());
        assertNull(store.findById(second).orElseThrow().supersededBy());
    }

    @Test
    void dedupeSkipsProtectedProfileRows() {
        MemoryItemStore store = store();
        /* 画像行（owner=USER）受保护：即便正文重复也不参与去重（memory-v5 §8.4） */
        long first = store.insert(null, "profile", "t", "同一句话", 0.9, "explicit", null, null);
        store.insert(null, "profile", "t", "同一句话", 0.9, "explicit", null, null);
        MemoryProps props = new MemoryProps();
        props.setEpisodeMax(50);

        int changed = new MemoryConsolidator(props, store).consolidate();

        assertEquals(0, changed);
        assertNull(store.findById(first).orElseThrow().supersededBy());
    }

    @Test
    void trimsEpisodesBeyondMax() {
        MemoryItemStore store = store();
        for (int i = 0; i < 5; i++) {
            store.insert(null, "episode", "t", "情景" + i, 0.5, "auto", null, null);
        }
        MemoryProps props = new MemoryProps();
        props.setEpisodeMax(2);

        int changed = new MemoryConsolidator(props, store).consolidate();

        assertEquals(3, changed);
        assertEquals(2, store.activeByScope("episode", 10).size());
    }
}
