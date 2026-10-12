package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.knowledge.recall.RagRecallRecorder;
import cc.ginpika.minis.ai.knowledge.retrieval.FastSearchService;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.memory.MemorySnapshot;
import cc.ginpika.minis.ai.memory.MemorySnapshotBuilder;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「RAG-first / 记忆默认注入」两路源的回归（{@link KnowledgeProvider} / {@link MemoryProvider}）。
 *
 * <p>钉住三条契约：① 默认<b>关</b>时一律短路——完全不碰检索依赖；② 开启后按本次输入召回、渲染进 system；
 * ③ v6 起记忆注入不再只服务前台（memory-v6 §8.5：子代理 / 心跳面也读，但各自作用域）。
 */
class ContextInjectionProviderTest {

    private static ContextRequest req(String query) {
        return new ContextRequest("m", "sys", "s1", false, query, 0, null);
    }

    private static MemoryItemStore.ItemRow row() {
        return new MemoryItemStore.ItemRow(1L, null, "profile", "标题",
                "用户说过这件事", 0.9, "auto", false, System.currentTimeMillis(), null, null, null,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private static MemorySnapshot snapshot(List<MemoryItemStore.ItemRow> items) {
        return new MemorySnapshot(null, null, 1L, items, List.of(), System.currentTimeMillis(), 60_000L);
    }

    private static MemoryProvider memoryProvider(MemorySnapshotBuilder builder, ContextProps props) {
        return new MemoryProvider(builder, props, new MemoryProps(), mock(RagRecallRecorder.class));
    }

    @Test
    void knowledgeInjectionIsOffByDefault() {
        FastSearchService fast = mock(FastSearchService.class);

        String suffix = new KnowledgeProvider(fast, new ContextProps()).systemSuffix(req("查一下 X"));

        assertEquals("", suffix);
        verifyNoInteractions(fast);
    }

    @Test
    void knowledgeInjectionAddsRecalledChunks() {
        FastSearchService fast = mock(FastSearchService.class);
        ChunkStore.Row row = new ChunkStore.Row(1L, "u", "doc", "ref", "标题", "正文",
                null, null, null, null, null, null, 0L);
        when(fast.ragRecall("查一下 X"))
                .thenReturn(new FastSearchService.RagRecall(List.of(row), null));
        ContextProps props = new ContextProps();
        props.setInjectKnowledge(true);

        String suffix = new KnowledgeProvider(fast, props).systemSuffix(req("查一下 X"));

        assertTrue(suffix.contains("知识库自动召回"));
        assertTrue(suffix.contains("标题"));
        assertTrue(suffix.contains("正文"));
    }

    @Test
    void memoryInjectionCanBeTurnedOff() {
        MemorySnapshotBuilder builder = mock(MemorySnapshotBuilder.class);
        ContextProps props = new ContextProps();
        props.setInjectMemory(false);

        String suffix = memoryProvider(builder, props).systemSuffix(req("上次说到哪"));

        assertEquals("", suffix);
        verifyNoInteractions(builder);
    }

    @Test
    void memoryInjectionRendersSnapshotHits() {
        MemorySnapshotBuilder builder = mock(MemorySnapshotBuilder.class);
        when(builder.build(any(MemoryExec.class), eq("上次说到哪"), nullable(String.class)))
                .thenReturn(snapshot(List.of(row())));
        ContextProps props = new ContextProps();
        props.setInjectMemory(true);

        String suffix = memoryProvider(builder, props).systemSuffix(req("上次说到哪"));

        assertTrue(suffix.contains("相关记忆"));
        assertTrue(suffix.contains("用户说过这件事"));
    }

    @Test
    void blankQueryStillRunsThroughSnapshot() {
        MemorySnapshotBuilder builder = mock(MemorySnapshotBuilder.class);
        FastSearchService fast = mock(FastSearchService.class);
        ContextProps props = new ContextProps();
        props.setInjectKnowledge(true);
        props.setInjectMemory(true);
        when(builder.build(any(MemoryExec.class), nullable(String.class), nullable(String.class)))
                .thenReturn(snapshot(List.of()));

        assertEquals("", new KnowledgeProvider(fast, props).systemSuffix(req("")));
        /* v6：空查询也走快照构建（此时只注入恒定注入集），没有命中就注入空串 */
        assertTrue(memoryProvider(builder, props).systemSuffix(req(null)).contains("<minis-memory>"));
        verifyNoInteractions(fast);
    }

    @Test
    void knowledgeStaysChatOnlyWhileMemoryServesBackgroundFaces() {
        FastSearchService fast = mock(FastSearchService.class);
        MemorySnapshotBuilder builder = mock(MemorySnapshotBuilder.class);
        ContextProps props = new ContextProps();
        props.setInjectKnowledge(true);
        props.setInjectMemory(true);
        when(builder.build(any(MemoryExec.class), nullable(String.class), nullable(String.class)))
                .thenReturn(snapshot(List.of(row())));
        ContextRequest subagent = new ContextRequest("m", "sys", "s1", false, "查一下 X", 0, null,
                ContextRequest.Surface.SUBAGENT);

        /* 知识库面仍只服务前台 */
        assertEquals("", new KnowledgeProvider(fast, props).systemSuffix(subagent));
        verifyNoInteractions(fast);
        /* 子代理面也要有记忆（memory-v6 §8.5：修 D6——它读派发方作用域 + 本会话） */
        assertTrue(memoryProvider(builder, props).systemSuffix(subagent).contains("用户说过这件事"));
    }
}
