package cc.ginpika.minis.ai.computer;

import cc.ginpika.minis.ai.computer.browser.*;
import cc.ginpika.minis.ai.computer.integration.*;
import cc.ginpika.minis.ai.computer.window.WindowCapture;
import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.config.DesktopProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ComputerToolsTest {
    final ObjectMapper json = new ObjectMapper();
    final WindowCapture capture = mock(WindowCapture.class);
    final ChromeBrowser isolated = mock(ChromeBrowser.class);
    final ChromeBridge bridge = mock(ChromeBridge.class);
    final ComputerTools tools = new ComputerTools(capture, isolated, bridge, json, new ChromeTool());

    @Test void webAndRemoteModesCannotBorrowLocalChromeEvenWithForgedToolArguments() throws Exception {
        try (var user = UserScope.bind("alice")) {
            String result = tools.run("chrome", json.readTree("{\"action\":\"open\",\"environment\":\"user\",\"url\":\"https://example.com\"}"), "chat");
            assertTrue(result.contains("Web / 远程部署"));
            verify(bridge, never()).call(anyString(), any());
            verifyNoInteractions(isolated);
        }
    }
    @Test void borrowedTabsNeverFallBackToAnIsolatedProfileWhenTheExtensionDisconnects() throws Exception {
        when(bridge.desktopLocal()).thenReturn(true);
        when(bridge.call(anyString(), any())).thenThrow(new IllegalStateException("未连接"));
        try (var user = UserScope.bind("alice")) {
            assertTrue(tools.run("chrome", json.readTree("{\"action\":\"read\",\"tab_id\":\"user:11\"}"), "chat").contains("未连接"));
            verifyNoInteractions(isolated);
        }
    }
    @Test void userAgentAndConversationAllContributeToBrowserIsolation() throws Exception {
        when(isolated.run(anyString(), any())).thenReturn("{}");
        var args = json.readTree("{\"action\":\"list\",\"environment\":\"isolated\"}");
        for (String user : new String[]{"alice", "bob"}) try (var ignored = UserScope.bind(user)) {
            for (String agent : new String[]{"one", "two"}) AgentScope.run(agent, () -> {
                for (String conversation : new String[]{"chat-1", "chat-2"}) tools.run("chrome", args, conversation);
                return null;
            });
        }
        var scopes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(isolated, times(8)).run(scopes.capture(), any());
        assertEquals(8, new java.util.HashSet<>(scopes.getAllValues()).size());
    }
    @Test void missingIdentityAndInvalidCommandsNeverReachAComputer() throws Exception {
        assertTrue(tools.run("chrome", json.readTree("{\"action\":\"list\"}"), "chat").contains("缺少"));
        try (var user = UserScope.bind("alice")) {
            assertTrue(tools.run("chrome", json.readTree("{\"action\":\"eval\",\"text\":\"document.cookie\"}"), "chat").contains("未知"));
            assertTrue(tools.run("window_capture", json.readTree("{\"action\":\"capture\",\"window_id\":\"bad\",\"pid\":1}"), "chat").contains("window_id"));
        }
        verifyNoInteractions(capture, isolated, bridge);
    }
    @Test void configuringAHelperDoesNotGrantServerDesktopAccess() {
        var desktop = new DesktopProps();
        String helper = Path.of("pom.xml").toAbsolutePath().toString();
        assertFalse(new WindowCapture(helper, json, desktop).available());
        desktop.setEnabled(true);
        assertFalse(new WindowCapture(helper, json, desktop).available(), "A missing local token must fail closed");
        assertThrows(IllegalStateException.class, () -> new ChromeBridge(desktop, json).pair());
    }
}
