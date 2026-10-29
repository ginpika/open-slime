package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.store.MemoryHandoffStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.ai.memory.store.MemoryShareStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 发布 / 交接 / 提升的回归（memory-v6 §9.2 / §9.3）：收件箱按作用域拉取、状态机、提升凭据。
 */
class MemoryPublisherTest {

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
              note TEXT, created_at INTEGER NOT NULL, revoked_at INTEGER)
            CREATE TABLE memory_handoff(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              to_kind TEXT NOT NULL, to_ref TEXT, state TEXT NOT NULL DEFAULT 'offered',
              note TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
            CREATE TABLE memory_promotion(
              id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
              from_layer TEXT NOT NULL, to_layer TEXT NOT NULL, policy TEXT NOT NULL,
              evidence_count INTEGER NOT NULL DEFAULT 1,
              state TEXT NOT NULL DEFAULT 'candidate', note TEXT,
              created_at INTEGER NOT NULL, applied_at INTEGER)""";

    private record Fixture(JdbcTemplate jdbc, MemoryItemStore items, MemoryPublisher publisher) {
    }

    private static Fixture fixture() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        MemoryItemStore items = new MemoryItemStore(j);
        MemoryPublisher publisher = new MemoryPublisher(new MemoryShareStore(j), new MemoryHandoffStore(j),
                new MemoryPromotionStore(j), items);
        return new Fixture(j, items, publisher);
    }

    private static long agentItem(MemoryItemStore items, String agentId, String body) {
        return items.insert(
                new MemoryItemStore.Ownership(null, "AGENT", "AGENT", "L2", null, "s1", null, agentId),
                new MemoryItemStore.Governance(agentId, "inferred", "inferred", "normal",
                        MemoryIdentity.REAL),
                "method", "经验", body, 0.9, "auto", null, null);
    }

    private static MemoryExec exec(String agentId) {
        return new MemoryExec(null, agentId, null, "s1", null, MemorySurface.CHAT, false, null,
                MemoryIdentity.REAL);
    }

    @Test
    void handoffAppearsInInboxUntilAccepted() {
        Fixture f = fixture();
        long item = agentItem(f.items(), "a1", "并发 Bug 的解法");
        long[] ids = f.publisher().handoff(item, "agent", "a1", "交给后续处理");

        List<MemoryPublisher.InboxEntry> inbox = f.publisher().inbox(exec("a1"), 20);
        assertEquals(1, inbox.size(), "交接在我自己的收件箱里可发现");
        assertEquals(ids[1], inbox.get(0).handoffId());

        assertEquals(0, f.publisher().inbox(exec("a2"), 20).size(), "别的 Agent 收不到");
        f.publisher().move(ids[1], "accepted");
        assertEquals(0, f.publisher().inbox(exec("a1"), 20).size(), "accept 后不再出现在待接收");
    }

    @Test
    void humanInboxSeesEverything() {
        Fixture f = fixture();
        long item = agentItem(f.items(), "a1", "给谁都不清楚的交接");
        f.publisher().handoff(item, "agent", "a9", null);

        assertEquals(1, f.publisher().inbox(MemoryExec.system(null), 20).size(),
                "管理页（human）看全量待接收（T18）");
    }

    @Test
    void promoteRecordsProvenanceAndChangesLayer() {
        Fixture f = fixture();
        long item = agentItem(f.items(), "a1", "值得沉淀的经验");

        long promotionId = f.publisher().promote(item, "L4", 3, "复用三次");
        assertEquals("L2", f.items().findById(item).orElseThrow().layer(), "候选阶段不改条目");

        assertEquals(1, f.publisher().applyPromotion(promotionId));
        MemoryItemStore.ItemRow after = f.items().findById(item).orElseThrow();
        assertEquals("L4", after.layer());
        assertEquals(promotionId, after.promotedFrom(), "L4 有可追溯来源（I6）");
        assertTrue(f.publisher().promotionCandidates(10).isEmpty(), "落地后不再挂候选");
    }

    @Test
    void publishRejectsUnknownTargetKind() {
        Fixture f = fixture();
        long item = agentItem(f.items(), "a1", "内容");
        assertThrows(IllegalArgumentException.class,
                () -> f.publisher().publish(item, "galaxy", null, null));
    }
}
