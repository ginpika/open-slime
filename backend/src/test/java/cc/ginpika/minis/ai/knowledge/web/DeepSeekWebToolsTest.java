package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cc.ginpika.minis.ai.core.tool.*;
import cc.ginpika.minis.ai.core.tool.doc.*;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.knowledge.recall.*;
import cc.ginpika.minis.config.DeepSeekWebSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeepSeekWebToolsTest {
    @Test void everyToolSelectionPathHonorsRuntimeSwitches() {
        var settings = mock(DeepSeekWebSettings.class);
        var manager = new ToolManager(List.of(new SearchVectorChunksTool(), new SearchWebTool(settings), new FetchWebTool(settings)), new ObjectMapper());
        var c = new DeepSeekWebSettings.Config("https://api.deepseek.com", "fixture", true, false, true, 5, 2);
        when(settings.resolve()).thenReturn(c);
        assertEquals(1, manager.toolsOf(ToolSurface.CHAT).size());
        assertEquals(0, manager.toolsOf(List.of("search_web", "fetch_web"), ToolSurface.CHAT).size());
        assertEquals(0, manager.grantedToolsOf(List.of("search_web", "fetch_web"), ToolSurface.CHAT).size());
        assertEquals(1, manager.toolsOfDomains(Set.of(ToolDomain.DOC), ToolSurface.CHAT).size());
        assertEquals(1, manager.readOnlyToolsOf(ToolSurface.CHAT).size());
        assertEquals(1, manager.grantableTools(ToolSurface.CHAT).size());
        when(settings.resolve()).thenReturn(new DeepSeekWebSettings.Config(c.baseUrl(), c.apiKey(), false, true, false, 5, 2));
        assertEquals(2, manager.toolsOf(ToolSurface.HEARTBEAT).size());
        assertEquals(1, manager.grantedToolsOf(List.of("search_web", "fetch_web"), ToolSurface.CHAT).size());
        when(settings.resolve()).thenReturn(new DeepSeekWebSettings.Config(c.baseUrl(), c.apiKey(), false, true, true, 5, 2));
        assertEquals(3, manager.readOnlyToolsOf(ToolSurface.CHAT).size());
    }
    @Test void actualToolDispatchRechecksSwitchAndPersistsExactlyWhatModelReceives() {
        var settings = mock(DeepSeekWebSettings.class); var client = mock(DeepSeekWebClient.class);
        when(settings.resolve()).thenReturn(new DeepSeekWebSettings.Config("https://api.deepseek.com", "fixture", true, false, true, 5, 2));
        var service = new WebRetrievalService(settings, client);
        var recorder = mock(RagRecallRecorder.class);
        var toolbox = new KnowledgeToolbox(null, null, null, null, null, null, null, null, null, null,
                recorder, null, null, null, null, null, null, null, null, service);
        var search = toolbox.run("search_web", "{\"query\":\"hello\"}", "chat");
        assertTrue(search.content().contains("未启用"));
        verify(recorder).record("search_web", "{\"query\":\"hello\"}", search.content(), "chat");
        var fetch = toolbox.run("fetch_web", "{\"url\":\"https://example.com\"}", "agent");
        assertTrue(fetch.content().contains("未启用"));
        verify(recorder).record("fetch_web", "{\"url\":\"https://example.com\"}", fetch.content(), "agent");
        verifyNoInteractions(client);
    }
    @Test void webRecallChannelIncludesAutomaticAndActiveCalls() {
        var store = mock(RagRecallStore.class); var recorder = new RagRecallRecorder(store);
        recorder.record("search_web", "query", "result", "agent");
        verify(store).append(eq("web"), eq("search_web"), eq("agent"), isNull(), eq("query"), eq("result"), eq(6L));
        recorder.recordWebInject("query", "result", null);
        verify(store).append("web", "web_inject", "chat", "builtin", "query", "result", 6L);
    }
}
