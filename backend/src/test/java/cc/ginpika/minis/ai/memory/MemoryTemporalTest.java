package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 双时态的回归（memory-v6 §7.1 / §12.4 · T7）：旧值只关闭有效期、历史可点查、取代链可回溯。
 */
class MemoryTemporalTest {

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
            CREATE TABLE memory_edge(
              id TEXT PRIMARY KEY, user_id TEXT, owner TEXT NOT NULL, project_id TEXT, agent_id TEXT,
              subject TEXT NOT NULL, predicate TEXT NOT NULL, object TEXT NOT NULL,
              confidence REAL NOT NULL DEFAULT 0.5, valid_at INTEGER, invalid_at INTEGER,
              learned_at INTEGER NOT NULL, expired_at INTEGER, source_item INTEGER,
              created_at INTEGER NOT NULL)""";

    private record Fixture(JdbcTemplate jdbc, MemoryItemStore items, MemoryEdgeStore edges,
                           MemoryTemporalService temporal) {
    }

    private static Fixture fixture() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        MemoryItemStore items = new MemoryItemStore(j);
        MemoryEdgeStore edges = new MemoryEdgeStore(j);
        return new Fixture(j, items, edges, new MemoryTemporalService(items, edges));
    }

    private static long item(MemoryItemStore items, String body) {
        return items.insert(
                new MemoryItemStore.Ownership(null, "AGENT", "AGENT", "L2", null, "s1", null, "a1"),
                new MemoryItemStore.Governance("a1", "inferred", "inferred", "normal",
                        MemoryIdentity.REAL),
                "method", "做法", body, 0.9, "auto", null, null);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void asOfStillSeesSupersededValue() {
        Fixture f = fixture();
        long old = item(f.items(), "旧做法：直接用那个 API");
        sleep(5);
        long tOld = System.currentTimeMillis();
        sleep(5);
        long fresh = item(f.items(), "新做法：换线程安全实现");
        sleep(5);
        f.items().supersede(old, fresh);   // 关闭有效期，不删除
        sleep(5);
        long tNow = System.currentTimeMillis();

        MemoryTemporalService.AsOfView past = f.temporal().asOf(tOld, 50);
        MemoryTemporalService.AsOfView now = f.temporal().asOf(tNow, 50);

        assertEquals(List.of(old), past.items().stream().map(MemoryItemStore.ItemRow::id).toList(),
                "过去那一刻：只知道旧做法");
        assertEquals(List.of(fresh), now.items().stream().map(MemoryItemStore.ItemRow::id).toList(),
                "现在：旧做法已被取代，不再有效但仍在库里");
        assertEquals(2, f.items().listAll().size(), "取代不是删除（双时态保留全量）");
    }

    @Test
    void historyWalkChainOldToNew() {
        Fixture f = fixture();
        long first = item(f.items(), "第一版");
        long second = item(f.items(), "第二版");
        long third = item(f.items(), "第三版");
        f.items().supersede(first, second);
        f.items().supersede(second, third);

        List<Long> chain = f.temporal().history(third).stream()
                .map(MemoryItemStore.ItemRow::id).toList();

        assertEquals(List.of(first, second, third), chain, "取代链按旧 → 新排列");
        assertEquals(List.of(first, second, third), f.temporal().history(second).stream()
                .map(MemoryItemStore.ItemRow::id).toList(), "从链条中间点进也能拿到全链");
    }

    @Test
    void edgeAsOfFollowsValidityWindow() {
        Fixture f = fixture();
        long src = item(f.items(), "支撑边的事实");
        f.edges().add(null, "AGENT", null, "a1", "Java", "存在并发问题", "1.2 版本", 0.8, null, src);
        sleep(5);
        long tBefore = System.currentTimeMillis();
        sleep(5);
        f.edges().invalidateBySourceItem(src);   // 支撑条目被取代 → 边关闭有效期
        sleep(5);
        long tAfter = System.currentTimeMillis();

        assertEquals(1, f.temporal().asOf(tBefore, 50).edges().size(), "失效前：边有效");
        assertTrue(f.temporal().asOf(tAfter, 50).edges().isEmpty(), "失效后：边不再有效");
        Integer rows = f.jdbc().queryForObject("SELECT COUNT(*) FROM memory_edge", Integer.class);
        assertEquals(1, rows == null ? 0 : rows, "双时态不删除：边仍在库里，只是关闭了有效期");
    }
}
