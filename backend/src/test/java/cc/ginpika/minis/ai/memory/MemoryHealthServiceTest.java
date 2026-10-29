package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆体检的回归（memory-v6 §15.4 · T30）：三病指标真的能从数据里读出来。
 */
class MemoryHealthServiceTest {

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
            CREATE TABLE memory_candidate(valid_until INTEGER,
              id INTEGER PRIMARY KEY AUTOINCREMENT, fingerprint TEXT NOT NULL, user_id TEXT,
              owner TEXT NOT NULL DEFAULT 'USER', scope TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
              source_kind TEXT NOT NULL DEFAULT 'inferred', evidence_level TEXT NOT NULL,
              evidence_count INTEGER NOT NULL DEFAULT 1, evidence_refs TEXT,
              status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)
            CREATE TABLE memory_work(
              id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, task_id INTEGER,
              agent_id TEXT, project_id TEXT, kind TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
              ref_type TEXT, ref_id INTEGER, status TEXT NOT NULL DEFAULT 'open',
              expires_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""";

    private record Fixture(MemoryItemStore items, MemoryCandidateStore candidates, MemoryWorkStore works, JdbcTemplate jdbc) {
    }

    private static Fixture fixture() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        return new Fixture(new MemoryItemStore(j), new MemoryCandidateStore(j), new MemoryWorkStore(j), j);
    }

    private static MemoryHealthService health(Fixture f) {
        return new MemoryHealthService(f.items(), f.works(), f.candidates());
    }

    private static long item(Fixture f, String owner, String domain, String layer, String body,
                             String status, String identity, Long supersededBy) {
        MemoryItemStore.Ownership own = new MemoryItemStore.Ownership(null, owner, domain, layer,
                "PROJECT".equals(owner) ? "p1" : null, null, null,
                "AGENT".equals(owner) ? "a1" : null);
        long id = f.items().insert(own,
                new MemoryItemStore.Governance("a1", "inferred", "inferred", "normal",
                        cc.ginpika.minis.ai.core.MemoryIdentity.parse(identity)),
                "PROJECT".equals(domain) ? "fact" : "profile", "t", body, 0.9, "auto", null, null);
        if (status != null || supersededBy != null) {
            if (supersededBy != null) {
                f.items().supersede(id, supersededBy);
            } else if (!"active".equals(status)) {
                f.items().setStatus(id, status);
            }
        }
        return id;
    }

    @Test
    void cleanMemoryGradesOk() {
        Fixture f = fixture();
        item(f, "USER", "USER_PROFILE", "L1", "用户用 Java", null, "REAL", null);

        MemoryHealthService.Report report = health(f).report();

        assertEquals("ok", report.grade());
        assertTrue(report.pollution().alerts().isEmpty());
        assertTrue(report.crowding().alerts().isEmpty());
        assertTrue(report.drift().alerts().isEmpty());
    }

    @Test
    void fictionInProfileIsRisk() {
        Fixture f = fixture();
        long id = item(f, "USER", "USER_PROFILE", "L1", "角色讨厌下雨", null, "REAL", null);
        // 模拟历史脏数据；正常写入口已经拒绝虚构画像，体检仍需能发现遗留污染。
        f.jdbc().update("UPDATE graph_claim SET identity='FICTION' WHERE id=?", id);

        MemoryHealthService.Report report = health(f).report();

        assertEquals(1, report.pollution().fictionInProfile());
        assertEquals("risk", report.grade(), "画像被虚构内容污染 = 风险");
        assertFalse(report.pollution().alerts().isEmpty());
    }

    @Test
    void singleDomainCrowdingIsWarned() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            item(f, "PROJECT", "PROJECT", "L1", "小说世界观第 " + i + " 条设定，越写越长越写越长", null,
                    "REAL", null);
        }
        item(f, "USER", "USER_PROFILE", "L1", "用 Java", null, "REAL", null);

        MemoryHealthService.Report report = health(f).report();

        assertEquals("warn", report.grade());
        assertFalse(report.crowding().alerts().isEmpty(), "单域占比过高应告警");
        assertEquals("PROJECT", report.crowding().domains().get(0).key(), "占用最大的是小说项目");
    }

    @Test
    void supersededItemsAreReportedAsDrift() {
        Fixture f = fixture();
        long newer = item(f, "PROJECT", "PROJECT", "L1", "现行方案：线程安全实现", null, "REAL", null);
        item(f, "PROJECT", "PROJECT", "L1", "旧方案：直接用那个并发有问题的 API", null, "REAL", newer);

        MemoryHealthService.Report report = health(f).report();

        assertEquals(1, report.drift().superseded());
        assertFalse(report.drift().alerts().isEmpty());
        assertEquals("superseded", f.items().findById(report.drift().recent().get(0).itemId())
                .orElseThrow().status());
    }

    @Test
    void candidateCountersFeedPollution() {
        Fixture f = fixture();
        f.candidates().upsertPending("fp1", null, "profile", "t", "来自小说的偏好", "fiction",
                "inferred", null);
        f.candidates().upsertPending("fp2", null, "profile", "t", "被拒的候选", "inferred",
                "inferred", null);
        f.candidates().setStatus(2L, "rejected");

        MemoryHealthService.Report report = health(f).report();

        assertEquals(1, report.pollution().blockedFictionCandidates());
        assertEquals(1, report.pollution().rejectedCandidates());
    }
}
