package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore;
import cc.ginpika.minis.ai.memory.store.MemoryCursorStore;
import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import cc.ginpika.minis.ai.memory.store.MemoryHandoffStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevocationStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevisionStore;
import cc.ginpika.minis.ai.memory.store.MemoryShareStore;
import cc.ginpika.minis.ai.memory.store.MemorySnapshotStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import org.springframework.stereotype.Service;

/**
 * 记忆维护（memory-v5）：一次性清空<b>整个记忆子系统</b>的所有落点。
 *
 * <p>为什么单独一个服务：记忆横跨 7 张表（事件 / 条目 / 候选 / 共享 / 撤销墓碑 / 知识边 / 提炼水位），
 * 「重置」必须一处收口、不可遗漏——设置页「重置知识库」连带清除记忆时调它（见 SettingsService）。
 *
 * <p>语义：清空是<b>不可恢复</b>的用户显式操作；向量投影由各 store 的 clearAll 连带清理，避免悬挂。
 */
@Service
public class MemoryMaintenanceService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** Acquire the SQLite write boundary before clearing any content. Reserved reset cursors survive cleanup. */
    public void beginReset() {
        long now=System.currentTimeMillis();
        var cursor=new MemoryCursorStore(jdbc);
        cursor.set("reset:generation",MemoryResetBoundary.read(jdbc).generation()+1);
        cursor.set("reset:at",now);
        cursor.set("reset:chat",jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM chat_message",Long.class));
        cursor.set("reset:task",jdbc.queryForObject("SELECT COALESCE(max(id),0) FROM task",Long.class));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { MemoryStage.invalidateAll(); MemoryMetrics.reset(); }
                });
    }

    /** 一次清空的统计 */
    public record Cleared(int events, int items, int candidates, int shares, int revocations,
                          int edges, int cursors, int snapshots, int promotions, int handoffs,
                          int works, int revisions) {

        /** 记忆条目总数（事件 + 条目），供设置页展示 */
        public int entries() {
            return events + items;
        }
    }

    private final MemoryEventStore events;
    private final MemoryItemStore items;
    private final MemoryCandidateStore candidates;
    private final MemoryShareStore shares;
    private final MemoryRevocationStore revocations;
    private final MemoryEdgeStore edges;
    private final MemoryCursorStore cursors;
    private final MemorySnapshotStore snapshots;
    private final MemoryPromotionStore promotions;
    private final MemoryHandoffStore handoffs;
    private final MemoryWorkStore works;
    private final MemoryRevisionStore revisions;

    public MemoryMaintenanceService(MemoryEventStore events, MemoryItemStore items,
                                    MemoryCandidateStore candidates, MemoryShareStore shares,
                                    MemoryRevocationStore revocations, MemoryEdgeStore edges,
                                    MemoryCursorStore cursors, MemorySnapshotStore snapshots,
                                    MemoryPromotionStore promotions, MemoryHandoffStore handoffs,
                                    MemoryWorkStore works, MemoryRevisionStore revisions) {
        this.events = events;
        this.items = items;
        this.candidates = candidates;
        this.shares = shares;
        this.revocations = revocations;
        this.edges = edges;
        this.cursors = cursors;
        this.snapshots = snapshots;
        this.promotions = promotions;
        this.handoffs = handoffs;
        this.works = works;
        this.revisions = revisions;
    }

    /** 清空记忆子系统全部落点（调用方须在事务内，保证整体成功或整体回滚） */
    public Cleared clearAll() {
        return clearAll(false);
    }

    public Cleared clearAll(boolean deleteProjectKnowledge) {
        int c = candidates.clearAll();
        int s = shares.clearAll();
        int r = revocations.clearAll();
        int g = edges.clearAll();
        g+=jdbc.update("DELETE FROM memory_edge");
        jdbc.update("DELETE FROM memory_retracted_source");
        jdbc.update("DELETE FROM graph_ingest_job");
        if(deleteProjectKnowledge) jdbc.update("DELETE FROM project_document");
        int cur = cursors.clearAll();
        int snap = snapshots.clearAll();
        int promo = promotions.clearAll();
        int hand = handoffs.clearAll();
        int work = works.clearAll();
        int rev = revisions.clearAll();
        int e = events.clearAll();
        int i = items.clearAll(!deleteProjectKnowledge);
        return new Cleared(e, i, c, s, r, g, cur, snap, promo, hand, work, rev);
    }
}
