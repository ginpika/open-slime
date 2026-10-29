package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemorySnapshotStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 快照构建器的回归（memory-v6 §4.3 / §8.3）：受保护条目恒定注入、预算按桶生效、回放记录按 turnId 落。
 */
class MemorySnapshotBuilderTest {

    private static MemoryItemStore.ItemRow row(long id, String owner, String domain, String layer,
                                               String scope, String body, boolean pinned) {
        long now = System.currentTimeMillis();
        return new MemoryItemStore.ItemRow(id, "builtin", scope, "t", body, 0.9, "auto", pinned, now,
                null, null, null, now, now, null, owner, domain, layer, null, null, null, null,
                null, null, "active", pinned ? "pinned" : "normal", 0L, "fp" + id, now, null, now,
                null, "REAL", null, null);
    }

    private static MemoryExec exec() {
        return new MemoryExec(null, "a1", "p1", "s1", null, MemorySurface.CHAT, false, "turn-1",
                MemoryIdentity.REAL);
    }

    private static MemorySnapshotBuilder builder(MemoryItemRecall recall, MemorySnapshotStore store,
                                                 MemoryProps props) {
        WorkingSetService work = mock(WorkingSetService.class);
        when(work.open(any(), anyInt())).thenReturn(List.of());
        return new MemorySnapshotBuilder(recall, store, work, props);
    }

    @Test
    void blankQueryInjectsOnlyProtectedEntries() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        MemoryItemStore.ItemRow profile = row(1, "USER", "USER_PROFILE", "L1", "profile", "用 Java", false);
        MemoryItemStore.ItemRow rule = row(2, "USER", "USER_PROFILE", "L1", "directive", "别改口", false);
        MemoryItemStore.ItemRow agent = row(3, "AGENT", "AGENT", "L2", "method", "某做法", false);
        when(recall.visible(any(), anyInt())).thenReturn(List.of(profile, rule, agent));

        MemorySnapshot snap = builder(recall, store, new MemoryProps()).build(exec(), "  ", "turn-1");

        assertEquals(2, snap.items().size(), "无查询时只注入受保护条目（画像 / 全局规则）");
        assertTrue(snap.items().stream().anyMatch(it -> it.id() == 1L));
        assertTrue(snap.items().stream().anyMatch(it -> it.id() == 2L));
        assertFalse(snap.items().stream().anyMatch(it -> it.id() == 3L), "普通 Agent 经验不进恒定注入集");
        assertEquals("turn-1", snap.turnId());
        verify(store).save(any(MemorySnapshot.class), anyInt());
    }

    @Test
    void retentionProtectionDoesNotBypassContextBudget() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        MemoryItemStore.ItemRow profile = row(1, "USER", "USER_PROFILE", "L1", "profile", "画像", false);
        MemoryItemStore.ItemRow agent = row(2, "AGENT", "AGENT", "L2", "method", "经验", false);
        when(recall.recall(any(), eq("查一下"), eq(null), anyInt()))
                .thenReturn(List.of(new MemoryItemRecall.Hit(profile, 0.9d),
                        new MemoryItemRecall.Hit(agent, 0.8d)));
        MemoryProps props = new MemoryProps();
        props.getBudget().setTotal(0);

        MemorySnapshot snap = builder(recall, store, props).build(exec(), "查一下", null);

        assertTrue(snap.items().isEmpty(), "长期保留与本轮注入分别控制，预算为零时不注入");
    }

    @Test
    void l4ItemsUsePublishBucketNotAgentBucket() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        MemoryItemStore.ItemRow shared = row(1, "AGENT", "AGENT", "L4", "knowledge", "共享知识", false);
        MemoryItemStore.ItemRow own = row(2, "AGENT", "AGENT", "L2", "method", "我的经验", false);
        when(recall.recall(any(), eq("查一下"), eq(null), anyInt()))
                .thenReturn(List.of(new MemoryItemRecall.Hit(shared, 0.9d),
                        new MemoryItemRecall.Hit(own, 0.8d)));
        MemoryProps props = new MemoryProps();
        props.getBudget().setPublish(0);

        MemorySnapshot snap = builder(recall, store, props).build(exec(), "查一下", null);

        assertEquals(1, snap.items().size(), "L4 走发布桶：发布桶为 0 时被挤出");
        assertEquals(2L, snap.items().get(0).id());
    }

    @Test
    void workItemsUseTaskBucketAndAreRendered() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        WorkingSetService work = mock(WorkingSetService.class);
        MemoryWorkStore.WorkRow item = new MemoryWorkStore.WorkRow(7L, "s1", 3L, "a1", null,
                WorkingSetService.KIND_PROGRESS, "第三步", "正在替换并发实现", null, null, "open",
                null, 1L, 1L);
        when(work.open(any(), anyInt())).thenReturn(List.of(item));
        when(recall.visible(any(), anyInt())).thenReturn(List.of());
        MemoryProps props = new MemoryProps();

        MemorySnapshot snap = new MemorySnapshotBuilder(recall, store, work, props)
                .build(exec(), null, null);

        assertEquals(1, snap.work().size(), "工作集随快照一并注入（L3）");
        assertEquals(List.of(7L), snap.workIds());
        assertTrue(MemorySnapshotBuilder.workLine(item, 300).contains("进度"));
        assertTrue(MemorySnapshotBuilder.workLine(item, 300).contains("第三步"));
    }

    @Test
    void workDroppedWhenTaskBucketEmpty() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        WorkingSetService work = mock(WorkingSetService.class);
        when(work.open(any(), anyInt())).thenReturn(List.of(new MemoryWorkStore.WorkRow(7L, "s1",
                3L, "a1", null, WorkingSetService.KIND_PROGRESS, null, "进度正文", null, null,
                "open", null, 1L, 1L)));
        when(recall.visible(any(), anyInt())).thenReturn(List.of());
        MemoryProps props = new MemoryProps();
        props.getBudget().setTask(0);

        MemorySnapshot snap = new MemorySnapshotBuilder(recall, store, work, props)
                .build(exec(), null, null);

        assertTrue(snap.work().isEmpty(), "L3 桶为 0 时工作项不注入");
    }

    @Test
    void replayIsSkippedWithoutTurnId() {
        MemoryItemRecall recall = mock(MemoryItemRecall.class);
        MemorySnapshotStore store = mock(MemorySnapshotStore.class);
        when(recall.visible(any(), anyInt())).thenReturn(List.of());

        builder(recall, store, new MemoryProps()).build(exec(), null, null);

        verify(store, never()).save(any(MemorySnapshot.class), anyInt());
    }
}
