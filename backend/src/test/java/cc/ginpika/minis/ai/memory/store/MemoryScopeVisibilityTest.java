package cc.ginpika.minis.ai.memory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 作用域可见读的回归（memory-v6 §8.1 · 内存 SQLite）：谓词 R1–R6 真的在 SQL 里收窄候选集，
 * 以及写侧矩阵真的拦得住非法归属（不变量 I3–I6）。
 *
 * <p>对应 v6 测试矩阵：T1 跨项目隔离 / T11 L3 会话隔离 / T15 无作用域只见画像 / T25 矩阵违例。
 */
class MemoryScopeVisibilityTest {

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
            CREATE TABLE memory_share(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              from_owner TEXT NOT NULL, from_agent TEXT, from_project TEXT,
              to_kind TEXT NOT NULL, to_ref TEXT, status TEXT NOT NULL DEFAULT 'published',
              note TEXT, created_at INTEGER NOT NULL, revoked_at INTEGER)""";

    private static JdbcTemplate jdbc() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        return j;
    }

    /** 按归属落一条最小合法条目 */
    private static long insert(MemoryItemStore store, String owner, String domain, String layer,
                               String projectId, String sessionId, Long taskId, String agentId,
                               String scope) {
        MemoryItemStore.Ownership own = new MemoryItemStore.Ownership(
                null, owner, domain, layer, projectId, sessionId, taskId, agentId);
        MemoryItemStore.Governance gov = new MemoryItemStore.Governance(
                agentId, "inferred", "inferred", "normal", MemoryIdentity.REAL);
        return store.insert(own, gov, scope, "t", "正文-" + scope, 0.9, "auto", null, null);
    }

    private static MemoryExec exec(String agentId, String projectId, String sessionId, Long taskId) {
        return new MemoryExec(null, agentId, projectId, sessionId, taskId, MemorySurface.CHAT, false,
                "turn-1", MemoryIdentity.REAL);
    }

    @Test
    void crossProjectIsolation() {
        MemoryItemStore store = new MemoryItemStore(jdbc());
        insert(store, "PROJECT", "PROJECT", "L1", "novel", null, null, null, "fact");

        assertEquals(0, store.visible(exec(null, "software", null, null), null, 50).size(),
                "T1：别的项目读不到本项目条目");
        assertEquals(1, store.visible(exec(null, "novel", null, null), null, 50).size());
    }

    @Test
    void l3SessionIsolation() {
        MemoryItemStore store = new MemoryItemStore(jdbc());
        insert(store, "AGENT", "AGENT", "L3", null, "s1", null, "a1", "episode");
        insert(store, "AGENT", "AGENT", "L2", null, "s1", null, "a1", "method");

        List<MemoryItemStore.ItemRow> sameSession = store.visible(exec("a1", null, "s1", null), null, 50);
        List<MemoryItemStore.ItemRow> otherSession = store.visible(exec("a1", null, "s2", null), null, 50);

        assertEquals(2, sameSession.size(), "本会话：L2 + L3 都可见");
        assertEquals(1, otherSession.size(), "T11：别的会话只剩 L2，L3 被隔离");
        assertEquals("method", otherSession.get(0).scope());
    }

    @Test
    void noScopeSeesOnlyProfile() {
        MemoryItemStore store = new MemoryItemStore(jdbc());
        insert(store, "USER", "USER_PROFILE", "L1", null, null, null, null, "profile");
        insert(store, "AGENT", "AGENT", "L2", null, null, null, "a1", "method");

        List<MemoryItemStore.ItemRow> hits = store.visible(MemoryExec.none(), null, 50);

        assertEquals(1, hits.size(), "T15：无作用域只读正式画像，绝不读全量");
        assertEquals("USER", hits.get(0).owner());
    }

    @Test
    void humanScopeSeesEverything() {
        MemoryItemStore store = new MemoryItemStore(jdbc());
        insert(store, "USER", "USER_PROFILE", "L1", null, null, null, null, "profile");
        insert(store, "PROJECT", "PROJECT", "L1", "novel", null, null, null, "fact");
        insert(store, "AGENT", "AGENT", "L2", null, null, null, "a1", "method");

        assertEquals(3, store.visible(MemoryExec.system(null), null, 50).size(),
                "R6：管理页（human）读全量");
    }

    @Test
    void agentL4NeedsPublication() {
        JdbcTemplate j = jdbc();
        MemoryItemStore store = new MemoryItemStore(j);
        long item = insert(store, "AGENT", "AGENT", "L4", null, "s1", null, "a1", "knowledge");

        assertEquals(0, store.visible(exec("a1", null, "s1", null), null, 50).size(),
                "I6：未发布的 AGENT×L4 不可读");

        j.update("INSERT INTO memory_share(item_id, from_owner, from_agent, to_kind, to_ref, "
                        + "status, created_at) VALUES(?,?,?,?,?,?,?)",
                item, "AGENT", "a1", "agent", "a1", "published", System.currentTimeMillis());

        assertEquals(1, store.visible(exec("a1", null, "s1", null), null, 50).size(),
                "发布后（R5）可读");
    }

    @Test
    void writeMatrixRejectsIllegalOwnership() {
        MemoryItemStore store = new MemoryItemStore(jdbc());

        assertThrows(IllegalArgumentException.class,
                () -> insert(store, "USER", "USER_PROFILE", "L1", "p1", null, null, null, "profile"),
                "I3：画像不得绑项目");
        assertThrows(IllegalArgumentException.class,
                () -> insert(store, "AGENT", "AGENT", "L3", null, null, null, "a1", "episode"),
                "I5：L3 必须绑会话或任务");
        assertThrows(IllegalArgumentException.class,
                () -> insert(store, "PROJECT", "PROJECT", "L1", null, null, null, null, "fact"),
                "I4：项目条目必须绑 project_id");
        assertTrue(store.visible(MemoryExec.system(null), null, 50).isEmpty(), "违例条目一条都没落库");
    }
}
