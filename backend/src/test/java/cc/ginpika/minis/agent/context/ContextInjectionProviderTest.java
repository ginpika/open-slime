package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.ai.knowledge.retrieval.FastSearchService;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.memory.recall.MemoryRecall;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import cc.ginpika.minis.config.ContextProps;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * STEP 2「RAG-first / 记忆默认注入」两路源的回归（{@link KnowledgeProvider} / {@link MemoryProvider}）。
 *
 * <p>钉住两条契约：① 默认<b>关</b>时一律短路——完全不碰检索依赖，保证旧行为零变化；
 * ② 开启后按本次用户输入召回，命中渲染进 system；③ 查询为空不注入。
 */
class ContextInjectionProviderTest {

    private static ContextRequest req(String query) {
        return new ContextRequest("m", "sys", "s1", false, query, 0, null);
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
    void memoryInjectionIsOffByDefault() {
        MemoryRecall recall = mock(MemoryRecall.class);

        String suffix = new MemoryProvider(recall, new ContextProps()).systemSuffix(req("上次说到哪"));

        assertEquals("", suffix);
        verifyNoInteractions(recall);
    }

    @Test
    void memoryInjectionRendersRecalledHits() {
        MemoryRecall recall = mock(MemoryRecall.class);
        MemoryEventStore.EventRow row = new MemoryEventStore.EventRow(1L, "k", "chat", "user",
                "标题", "用户说过这件事", null, 0L, System.currentTimeMillis());
        when(recall.recall("上次说到哪", null, null, null, null, 5))
                .thenReturn(List.of(new MemoryRecall.Hit(row, 0.9f)));
        ContextProps props = new ContextProps();
        props.setInjectMemory(true);

        String suffix = new MemoryProvider(recall, props).systemSuffix(req("上次说到哪"));

        assertTrue(suffix.contains("相关记忆"));
        assertTrue(suffix.contains("用户说过这件事"));
    }

    @Test
    void blankQueryInjectsNothingEvenWhenEnabled() {
        FastSearchService fast = mock(FastSearchService.class);
        MemoryRecall recall = mock(MemoryRecall.class);
        ContextProps props = new ContextProps();
        props.setInjectKnowledge(true);
        props.setInjectMemory(true);

        assertEquals("", new KnowledgeProvider(fast, props).systemSuffix(req("")));
        assertEquals("", new MemoryProvider(recall, props).systemSuffix(req(null)));
        verifyNoInteractions(fast, recall);
    }

    @Test
    void chatOnlyProvidersStaySilentOnOtherSurfaces() {
        FastSearchService fast = mock(FastSearchService.class);
        MemoryRecall recall = mock(MemoryRecall.class);
        ContextProps props = new ContextProps();
        props.setInjectKnowledge(true);
        props.setInjectMemory(true);
        /* 即便开启、即便有查询，子代理 / 潜意识面也不该触发前台会话那两路召回（见 STEP 6-③） */
        ContextRequest subagent = new ContextRequest("m", "sys", "s1", false, "查一下 X", 0, null,
                ContextRequest.Surface.SUBAGENT);

        assertEquals("", new KnowledgeProvider(fast, props).systemSuffix(subagent));
        assertEquals("", new MemoryProvider(recall, props).systemSuffix(subagent));
        verifyNoInteractions(fast, recall);
    }
}
