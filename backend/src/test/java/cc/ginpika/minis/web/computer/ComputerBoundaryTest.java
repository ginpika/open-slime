package cc.ginpika.minis.web.computer;

import cc.ginpika.minis.ai.computer.browser.ChromeBridge;
import cc.ginpika.minis.ai.computer.window.WindowCapture;
import cc.ginpika.minis.config.DesktopProps;
import cc.ginpika.minis.web.controller.ComputerController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.server.ResponseStatusException;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComputerBoundaryTest {
    final ObjectMapper json = new ObjectMapper();
    final DesktopProps desktop = new DesktopProps();
    final ChromeBridge bridge = new ChromeBridge(desktop, json);
    final ComputerController controller = new ComputerController(bridge, mock(WindowCapture.class));
    @Test void webAndRemoteServersCannotCreateOrRevokeLocalChromeConnections() {
        assertFalse((Boolean) controller.status().get("desktopLocal"));
        assertThrows(ResponseStatusException.class, () -> controller.pair(new MockHttpServletRequest()));
        assertThrows(ResponseStatusException.class, controller::disconnect);
    }
    @Test void localPairingStillRequiresALoopbackCaller() {
        desktop.setEnabled(true); desktop.setLocalToken("desktop-token");
        var remote = new MockHttpServletRequest(); remote.setRemoteAddr("192.0.2.1");
        assertThrows(ResponseStatusException.class, () -> controller.pair(remote));
        var local = new MockHttpServletRequest(); local.setRemoteAddr("127.0.0.1"); local.setLocalPort(12345);
        assertTrue(controller.pair(local).get("connectionUrl").startsWith("ws://127.0.0.1:12345/ws/computer/chrome/"));
    }
    @Test void socketRequiresLivePairingLoopbackAndAnExtensionOrigin() {
        desktop.setEnabled(true); desktop.setLocalToken("desktop-token");
        String token = bridge.pair(); var socket = new ChromeSocket(bridge, json);
        assertTrue(handshake(socket, token, "127.0.0.1", "chrome-extension://" + "a".repeat(32)));
        assertFalse(handshake(socket, "invalid", "127.0.0.1", "chrome-extension://" + "a".repeat(32)));
        assertFalse(handshake(socket, token, "192.0.2.1", "chrome-extension://" + "a".repeat(32)));
        assertFalse(handshake(socket, token, "127.0.0.1", "https://malicious.example"));
        assertFalse(handshake(socket, token, "127.0.0.1", "null"));
        bridge.disconnect();
        assertFalse(handshake(socket, token, "127.0.0.1", "chrome-extension://" + "a".repeat(32)));
    }
    private boolean handshake(ChromeSocket socket, String token, String remote, String origin) {
        var request = new MockHttpServletRequest("GET", "/ws/computer/chrome/" + token);
        request.setRemoteAddr(remote); request.addHeader("Origin", origin);
        return socket.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(new MockHttpServletResponse()), socket, new HashMap<>());
    }
}
