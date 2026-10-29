package cc.ginpika.minis.ai.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos.*;
import cc.ginpika.minis.ai.knowledge.store.*;
import cc.ginpika.minis.ai.knowledge.web.WebRetrievalService;
import cc.ginpika.minis.ai.llm.*;
import cc.ginpika.minis.config.FastProps;
import java.util.List;
import org.junit.jupiter.api.Test;

class FastWebSearchTest {
    @Test void remoteFailureDoesNotEraseLocalDocumentAndDiagnosticUsesRagSwitch() {
        var chunks = mock(ChunkStore.class); var docs = mock(DocumentStore.class);
        var graph = mock(GraphStore.class); var web = mock(WebRetrievalService.class);
        when(chunks.metaMatch(anyList(), anyInt())).thenReturn(List.of(1L));
        when(chunks.excludeDisabled(anyList())).thenAnswer(c -> c.getArgument(0));
        when(chunks.rowsByIds(anyList())).thenReturn(List.of(new ChunkStore.Row(1L, "u", "doc", "ref", "local", "hello content", null, null, null, null, 2L, "enabled", 0L)));
        when(docs.docsByIds(any())).thenReturn(List.of(new DocumentStore.DocHit(2L, "local", null, "summary", "doc")));
        when(web.search("Hello", false)).thenReturn(new Web(true, List.of(), "HTTP 401", List.of()));
        when(web.search("Hello", true)).thenReturn(Web.DISABLED);
        var fast = new FastSearchService(chunks, docs, graph, mock(EmbeddingClient.class), mock(RerankClient.class), new FastProps(), web);
        var result = fast.search("Hello"); assertEquals("local", result.hits().getFirst().title()); assertEquals("HTTP 401", result.web().error());
        var diagnostic = fast.search("Hello", true); assertEquals("chunk", diagnostic.hits().getFirst().kind()); assertFalse(diagnostic.web().enabled());
        verify(web).search("Hello", false); verify(web).search("Hello", true);
        fast.search(" "); verifyNoMoreInteractions(web);
    }
}
