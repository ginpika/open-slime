package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.tool.doc.WebBrowserTool;
import cc.ginpika.minis.ai.knowledge.web.WebBrowserService;
import cc.ginpika.minis.ai.knowledge.web.WebDtos;
import cc.ginpika.minis.ai.knowledge.web.WebSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserContextProviderTest {
    private final ObjectMapper om = new ObjectMapper();
    private final WebBrowserService browser = mock(WebBrowserService.class);
    private final BrowserContextProvider provider = new BrowserContextProvider(browser, om);

    @Test void injectsLiveHandlesEvenWhenToolHistoryIsDisabledAndPreviewHasNoThreadScope() {
        var req = request(ContextRequest.Surface.CHAT, "chat", true);
        String owner = MemoryScopeCtx.run(MemoryExec.chat("peer", null, "chat", "turn-1", false),
                WebSnapshotService::agentOwner);
        assertEquals("agent:peer:chat", owner);
        when(browser.tabs(owner, "chat")).thenReturn(List.of(new WebDtos.BrowserTab("existing-id", "https://fixture.test/", "Title", true)));
        var body = new ContextBuilder(om, List.of(provider)).build(req);
        String system = body.path("messages").get(0).path("content").asText();
        assertTrue(system.contains("existing-id"));
        assertTrue(system.contains("web_browser.read"));
        assertTrue(system.contains("不可信网页"));
        assertFalse(req.contextTrace());
        verify(browser).tabs("agent:peer:chat", "chat");
    }

    @Test void otherSurfacesMissingToolAndMissingSessionDoNotReadBrowsers() {
        assertEquals("", provider.systemSuffix(request(ContextRequest.Surface.SUBAGENT, "chat", true)));
        assertEquals("", provider.systemSuffix(request(ContextRequest.Surface.HEARTBEAT, "chat", true)));
        assertEquals("", provider.systemSuffix(request(ContextRequest.Surface.CHAT, null, true)));
        assertEquals("", provider.systemSuffix(request(ContextRequest.Surface.CHAT, "chat", false)));
        verifyNoInteractions(browser);
    }

    @Test void noLiveBrowserAddsNoContextAndBrowserFailureDoesNotBreakChat() {
        var req = request(ContextRequest.Surface.CHAT, "chat", true);
        when(browser.tabs("agent:peer:chat", "chat")).thenReturn(List.of());
        assertEquals("", provider.systemSuffix(req));
        when(browser.tabs("agent:peer:chat", "chat")).thenThrow(new IllegalStateException("Browser unavailable"));
        assertEquals("", provider.systemSuffix(req));
    }

    private ContextRequest request(ContextRequest.Surface surface, String chat, boolean includeTool) {
        ArrayNode schemas = om.createArrayNode();
        if (includeTool) schemas.addObject().putObject("function").put("name", WebBrowserTool.NAME);
        return new ContextRequest("model", "system", chat, "peer", null, null, false, "继续", 10000, schemas, surface, null);
    }
}
