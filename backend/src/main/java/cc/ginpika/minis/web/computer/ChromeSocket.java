package cc.ginpika.minis.web.computer;

import cc.ginpika.minis.ai.computer.browser.ChromeBridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Only a paired extension on the local desktop can attach; normal web/remote deployments cannot pair. */
@Component
public class ChromeSocket extends TextWebSocketHandler implements HandshakeInterceptor {
    private final ChromeBridge bridge;
    private final ObjectMapper json;
    public ChromeSocket(ChromeBridge bridge, ObjectMapper json) { this.bridge = bridge; this.json = json; }
    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String path = request.getURI().getPath();
        String secret = path.substring(path.lastIndexOf('/') + 1);
        String origin = request.getHeaders().getOrigin();
        boolean local = request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                && request.getRemoteAddress().getAddress().isLoopbackAddress();
        if (!local || origin == null || !origin.matches("chrome-extension://[a-p]{32}") || !bridge.accepts(secret)) return false;
        attributes.put("pairing", secret); return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception error) {}
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        session.setTextMessageSizeLimit(2_100_000);
        try {
            bridge.attach(session.getId(), (String) session.getAttributes().remove("pairing"), text -> {
                try { synchronized (session) { session.sendMessage(new TextMessage(text)); } }
                catch (java.io.IOException error) { throw new IllegalStateException("Chrome WebSocket 已断开", error); }
            }, () -> { try { session.close(); } catch (java.io.IOException ignored) {} });
        } catch (RuntimeException error) { session.close(CloseStatus.POLICY_VIOLATION); }
    }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var response = json.readTree(message.getPayload());
        if (response != null) bridge.receive(session.getId(), response);
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { bridge.detach(session.getId()); }
    @Override public void handleTransportError(WebSocketSession session, Throwable error) throws Exception {
        bridge.detach(session.getId()); session.close(CloseStatus.SERVER_ERROR);
    }
}
