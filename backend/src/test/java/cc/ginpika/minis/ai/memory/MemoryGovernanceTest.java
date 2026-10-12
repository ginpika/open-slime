package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryHandoffStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevocationStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevisionStore;
import cc.ginpika.minis.ai.memory.store.MemoryShareStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 记忆治理的回归（memory-v6 §6.1 审计 / §12.5 显式遗忘 / §9.3 提升层）：
 * 写入留痕、遗忘先预览后执行且写墓碑、L2 经验能生成 L4 候选。
 */
class MemoryGovernanceTest {

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
            CREATE TABLE memory_revision(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER, op TEXT NOT NULL,
              actor_kind TEXT NOT NULL, actor_id TEXT, turn_id TEXT, before_json TEXT,
              after_json TEXT, reason TEXT, created_at INTEGER NOT NULL)
            CREATE TABLE memory_revocation(
              id INTEGER PRIMARY KEY AUTOINCREMENT, target_item_id INTEGER, fingerprint TEXT NOT NULL,
              reason TEXT, allow_relearn INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)
            CREATE TABLE memory_promotion(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              from_layer TEXT NOT NULL, to_layer TEXT NOT NULL, policy TEXT NOT NULL,
              evidence_count INTEGER NOT NULL DEFAULT 1, state TEXT NOT NULL DEFAULT 'candidate',
              note TEXT, created_at INTEGER NOT NULL, applied_at INTEGER)
            CREATE TABLE memory_share(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              from_owner TEXT NOT NULL, from_agent TEXT, from_project TEXT,
              to_kind TEXT NOT NULL, to_ref TEXT, status TEXT NOT NULL DEFAULT 'published',
              note TEXT, created_at INTEGER NOT NULL, revoked_at INTEGER)""";

    private record Fixture(MemoryItemStore items, MemoryRevisionStore revisions,
                           MemoryRevocationStore revocations, MemoryPromotionStore promotions,
                           MemoryEditService edit) {
    }

    private static Fixture fixture() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        MemoryRevisionStore revisions = new MemoryRevisionStore(j);
        j.execute("CREATE TABLE memory_item_embedding(item_id INTEGER PRIMARY KEY, item_version INTEGER)");
        MemoryItemStore items = new MemoryItemStore(j, revisions);
        MemoryRevocationStore revocations = new MemoryRevocationStore(j);
        MemoryEditService edit = new MemoryEditService(items, mock(MemoryItemRecall.class),
                revocations, new MemoryPublisher(new MemoryShareStore(j), new MemoryHandoffStore(j),
                new MemoryPromotionStore(j), items));
        return new Fixture(items, revisions, revocations, new MemoryPromotionStore(j), edit);
    }

    private static long item(MemoryItemStore items, String owner, String domain, String layer,
                             String scope, String evidence, double confidence, boolean pinned) {
        return itemFor(items, owner, domain, layer, scope,
                "AGENT".equals(owner) ? "a1" : null, evidence, confidence, pinned);
    }

    /** 同 {@link #item}，但可指定归属 Agent（用于「别的 Agent 的记忆」这类判定） */
    private static long itemFor(MemoryItemStore items, String owner, String domain, String layer,
                                String scope, String agentId, String evidence, double confidence,
                                boolean pinned) {
        long id = items.insert(
                new MemoryItemStore.Ownership(null, owner, domain, layer, null, null, null, agentId),
                new MemoryItemStore.Governance(agentId, "inferred", evidence,
                        pinned ? "pinned" : "normal", MemoryIdentity.REAL),
                scope, "t", "正文-" + scope, confidence, "auto", null, null);
        if (pinned) {
            items.setPinned(id, true);
        }
        return id;
    }

    /** 当前会话的任务记忆（owner=TASK 必须绑会话 / 任务，见 I4） */
    private static long taskItem(MemoryItemStore items, String agentId, String sessionId) {
        return items.insert(
                new MemoryItemStore.Ownership(null, "TASK", "TASK_EPISODIC", "L3", null, sessionId,
                        null, agentId),
                new MemoryItemStore.Governance(agentId, "inferred", "inferred", "normal",
                        MemoryIdentity.REAL),
                "episode", "t", "正文-episode", 0.5, "auto", null, null);
    }

    @Test
    void writesLeaveAuditTrail() {
        Fixture f = fixture();
        long first = item(f.items(), "AGENT", "AGENT", "L2", "method", "explicit", 0.9, false);
        long second = item(f.items(), "AGENT", "AGENT", "L2", "method", "explicit", 0.9, false);
        f.items().supersede(first, second);

        List<MemoryRevisionStore.RevisionRow> rows = f.revisions().byItem(first, 10);

        assertEquals(2, rows.size(), "create + supersede 各留一条");
        assertEquals("supersede", rows.get(0).op(), "新的在前");
        assertTrue(rows.get(0).afterJson().contains("supersededBy"), "after 摘要带取代关系");
        assertEquals("SYSTEM", rows.get(0).actorKind(), "无执行作用域时记为系统动作");
    }

    @Test
    void purgePreviewsThenRevokesWithTombstone() {
        Fixture f = fixture();
        long first = item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        item(f.items(), "AGENT", "AGENT", "L2", "episode", "inferred", 0.5, false);
        item(f.items(), "USER", "USER_PROFILE", "L1", "profile", "explicit", 0.9, false);
        MemoryPurgeService service = new MemoryPurgeService(f.items(), f.revocations());

        MemoryPurgeService.Preview preview = service.preview(
                new MemoryPurgeService.Filter(null, "AGENT", null, null, null, null, "测试遗忘"), 10);
        assertEquals(2, preview.matched(), "按 owner 收窄：只命中 Agent 私有那两条");
        assertEquals(0, preview.userProfile(), "不含正式画像");

        int purged = service.purge(new MemoryPurgeService.Filter(List.of(first), null, null, null,
                null, null, "用户要求忘掉"));

        assertEquals(1, purged);
        assertEquals("revoked", f.items().findById(first).orElseThrow().status());
        assertTrue(f.revocations().blocked(f.items().findById(first).orElseThrow().fingerprint()),
                "写墓碑：同指纹不再自动重建（I11 的撤销侧）");
    }

    @Test
    void promoterTurnsPrivateExperienceIntoPublishCandidate() {
        Fixture f = fixture();
        MemoryProps props = new MemoryProps();
        MemoryPromoter promoter = new MemoryPromoter(f.items(), f.promotions(), props);
        long good = item(f.items(), "AGENT", "AGENT", "L2", "method", "explicit", 0.9, false);
        item(f.items(), "USER", "USER_PROFILE", "L1", "profile", "explicit", 0.9, false);
        item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.2, true);   // pinned → 受保护

        assertEquals(1, promoter.scan(), "只有够硬的经验成为候选");
        assertEquals(good, f.promotions().listByState("candidate", 10).get(0).itemId());
        assertEquals(0, promoter.scan(), "重复扫描不重复建候选");
    }

    // ---------------------------------------------------------------- Agent 侧改写 / 遗忘（§9.1）

    private static MemoryExec exec(String agentId, String projectId, String sessionId, Long taskId) {
        return new MemoryExec(null, agentId, projectId, sessionId, taskId, MemorySurface.CHAT, false,
                "turn-9", MemoryIdentity.REAL);
    }

    @Test
    void agentCanOnlyEditItsOwnScope() {
        Fixture f = fixture();
        long own = item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        long otherAgent = itemFor(f.items(), "AGENT", "AGENT", "L2", "method", "a2", "inferred", 0.5,
                false);
        long profile = item(f.items(), "USER", "USER_PROFILE", "L1", "profile", "explicit", 0.9, false);
        long task = taskItem(f.items(), "a1", "s1");
        long published = item(f.items(), "AGENT", "AGENT", "L4", "knowledge", "explicit", 0.9, false);
        MemoryExec me = exec("a1", null, "s1", null);

        assertTrue(MemoryEditService.canEdit(me, f.items().findById(own).orElseThrow()),
                "自己的 L2 私有记忆可以改");
        assertTrue(MemoryEditService.canEdit(me, f.items().findById(task).orElseThrow()),
                "当前会话的任务记忆可以改");
        assertFalse(MemoryEditService.canEdit(me, f.items().findById(otherAgent).orElseThrow()),
                "别的 Agent 的私有记忆不可改");
        assertFalse(MemoryEditService.canEdit(me, f.items().findById(profile).orElseThrow()),
                "用户画像一律不可由 Agent 改写（I2）");
        assertFalse(MemoryEditService.canEdit(me, f.items().findById(published).orElseThrow()),
                "已发布的 L4 知识不可由 Agent 悄悄改写");
    }

    @Test
    void updateRewritesOwnMemoryWithAudit() {
        Fixture f = fixture();
        long id = item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        MemoryExec me = exec("a1", null, "s1", null);

        MemoryEditService.Outcome out = f.edit().update(me, id, null, "新标题", "纠正后的做法");

        assertTrue(out.ok(), out.message());
        MemoryItemStore.ItemRow after = f.items().findById(id).orElseThrow();
        assertEquals("纠正后的做法", after.body());
        assertEquals("新标题", after.title());
        assertEquals("auto", after.source(), "Agent 改写标记为 auto（人工编辑才是 manual）");
        assertEquals(1L, after.version(), "乐观锁版本自增");
        assertEquals("update", f.revisions().byItem(id, 5).get(0).op(), "改写留审计");
    }

    @Test
    void updateRejectsProfileAndAmbiguousMatch() {
        Fixture f = fixture();
        long profile = item(f.items(), "USER", "USER_PROFILE", "L1", "profile", "explicit", 0.9, false);
        MemoryExec me = exec("a1", null, "s1", null);

        MemoryEditService.Outcome denied = f.edit().update(me, profile, null, null, "改掉它");
        assertFalse(denied.ok());
        assertTrue(denied.message().contains("无权"), "画像改写被拒：" + denied.message());

        /* 两条内容几乎相同的记忆 → 按描述定位不猜，要求指明 item_id */
        item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        MemoryEditService.Outcome ambiguous = f.edit().update(me, null, "正文-method", null, "改掉它");
        assertFalse(ambiguous.ok());
        assertTrue(ambiguous.message().contains("item_id"), "命中多条时回候选让模型重挑：" + ambiguous.message());
    }

    @Test
    void forgetRevokesAndWritesTombstone() {
        Fixture f = fixture();
        long id = item(f.items(), "AGENT", "AGENT", "L2", "method", "inferred", 0.5, false);
        MemoryExec me = exec("a1", null, "s1", null);

        MemoryEditService.Outcome out = f.edit().forget(me, id, null, "这条记错了");

        assertTrue(out.ok(), out.message());
        MemoryItemStore.ItemRow after = f.items().findById(id).orElseThrow();
        assertEquals("revoked", after.status(), "遗忘 = 撤销，不是物理删除");
        assertTrue(f.revocations().blocked(after.fingerprint()), "写墓碑：同指纹不再自动重建");
        assertEquals("status", f.revisions().byItem(id, 5).get(0).op(), "遗忘同样留审计");
    }
}
