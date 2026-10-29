package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 策略实现一致性（memory-v6 §18 · T26）：读谓词的 SQL 版（{@code MemoryItemStore.visible}）与
 * Java 镜像（{@code MemoryPolicy.canRead}）在同一份数据、同一组作用域下必须给出**同一个集合**。
 *
 * <p>只有两边都改才可能同时错，而漏改一边会被这条测试立刻抓住。
 * 比较范围限定在 R1–R4 / R6：样本全部 active、未过期、无共享（R5 需要另建 memory_share 数据），
 * 从而排除 valid_until / status / share 这些只在 SQL 侧或镜像侧出现的条件。
 */
class MemoryPolicyConsistencyTest {

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
            CREATE TABLE memory_share(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              from_owner TEXT NOT NULL, from_agent TEXT, from_project TEXT,
              to_kind TEXT NOT NULL, to_ref TEXT, status TEXT NOT NULL DEFAULT 'published',
              note TEXT, created_at INTEGER NOT NULL, revoked_at INTEGER)""";

    /** 一条样本：条目 id + 它的归属（Java 镜像判定所需的全部输入） */
    private record Sample(long id, String owner, String domain, String layer, String projectId,
                          String sessionId, Long taskId, String agentId) {
    }

    private static MemoryItemStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        return new MemoryItemStore(j);
    }

    private static Sample insert(MemoryItemStore store, String owner, String domain, String layer,
                                 String projectId, String sessionId, Long taskId, String agentId) {
        long id = store.insert(
                new MemoryItemStore.Ownership(null, owner, domain, layer, projectId, sessionId,
                        taskId, agentId),
                new MemoryItemStore.Governance(agentId, "inferred", "inferred", "normal",
                        MemoryIdentity.REAL),
                "USER_PROFILE".equals(domain) ? "profile" : "method", "t", "正文", 0.9, "auto",
                null, null);
        return new Sample(id, owner, domain, layer, projectId, sessionId, taskId, agentId);
    }

    private static MemoryExec exec(String agentId, String projectId, String sessionId, Long taskId) {
        return new MemoryExec(null, agentId, projectId, sessionId, taskId, MemorySurface.CHAT, false,
                null, MemoryIdentity.REAL);
    }

    @Test
    void sqlAndJavaMirrorAgreeAcrossScopes() {
        MemoryItemStore store = store();
        List<Sample> samples = List.of(
                insert(store, "USER", "USER_PROFILE", "L1", null, null, null, null),
                insert(store, "AGENT", "AGENT", "L2", null, null, null, "a1"),
                insert(store, "AGENT", "AGENT", "L3", null, "s1", null, "a1"),
                insert(store, "AGENT", "AGENT", "L3", null, "s2", null, "a1"),
                insert(store, "AGENT", "AGENT", "L2", null, null, null, "a2"),
                insert(store, "PROJECT", "PROJECT", "L1", "p1", null, null, null),
                insert(store, "PROJECT", "PROJECT", "L2", "p2", null, null, "a2"),
                insert(store, "TASK", "TASK_EPISODIC", "L3", null, "s1", 5L, "a1"));
        List<MemoryExec> execs = List.of(
                exec("a1", "p1", "s1", null),
                exec("a1", null, "s2", null),
                exec("a2", "p2", "s1", 5L),
                MemoryExec.none(),
                MemoryExec.system(null));

        for (MemoryExec exec : execs) {
            List<Long> sql = store.visible(exec, null, 100).stream()
                    .map(MemoryItemStore.ItemRow::id).sorted(Comparator.naturalOrder()).toList();
            List<Long> mirror = samples.stream()
                    .filter(s -> MemoryPolicy.canRead(exec, s.owner(), s.domain(), s.projectId(),
                            s.agentId(), s.sessionId(), s.taskId(), s.layer()))
                    .map(Sample::id).sorted(Comparator.naturalOrder()).toList();
            assertEquals(sql, mirror,
                    "作用域 " + describe(exec) + " 下 SQL 谓词与 Java 镜像必须命中同一集合");
        }
    }

    @Test
    void mirrorRejectsCrossScopeReads() {
        MemoryItemStore store = store();
        insert(store, "PROJECT", "PROJECT", "L1", "novel", null, null, null);
        insert(store, "AGENT", "AGENT", "L2", null, null, null, "a1");

        MemoryExec otherProject = exec(null, "software", null, null);
        MemoryExec otherAgent = exec("a2", null, null, null);

        assertTrue(store.visible(otherProject, null, 50).isEmpty(), "SQL：别的项目读不到");
        assertFalse(MemoryPolicy.canRead(otherProject, "PROJECT", "PROJECT", "novel", null, null,
                null, "L1"), "镜像：别的项目读不到");
        assertTrue(store.visible(otherAgent, null, 50).isEmpty(), "SQL：别的 Agent 读不到私有记忆");
        assertFalse(MemoryPolicy.canRead(otherAgent, "AGENT", "AGENT", null, "a1", null, null, "L2"),
                "镜像：别的 Agent 读不到私有记忆");
        assertEquals(2, store.visible(MemoryExec.system(null), null, 50).size(), "人工全量视图看得到两条");
    }

    private static String describe(MemoryExec exec) {
        return "agent=" + exec.agentId() + " project=" + exec.projectId() + " session="
                + exec.sessionId() + " task=" + exec.taskId() + " human=" + exec.human();
    }
}
