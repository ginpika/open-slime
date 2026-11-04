package cc.ginpika.minis.ai.computer;

import cc.ginpika.minis.ai.computer.browser.ChromeBridge;
import cc.ginpika.minis.config.DesktopProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ChromeBridgeTest {
    final ObjectMapper json = new ObjectMapper();
    ChromeBridge bridge() { var props = new DesktopProps(); props.setEnabled(true); props.setLocalToken("local-session-token"); return new ChromeBridge(props, json); }
    @Test void pairingRejectsWrongTokensAndRotationRevokesTheOldConnection() {
        var bridge = bridge(); String first = bridge.pair();
        assertTrue(bridge.accepts(first)); assertFalse(bridge.accepts("wrong"));
        assertThrows(IllegalArgumentException.class, () -> bridge.attach("one", "wrong", text -> {}, () -> {}));
        bridge.attach("one", first, text -> {}, () -> {}); assertTrue(bridge.connected());
        assertThrows(IllegalArgumentException.class, () -> bridge.attach("two", first, text -> {}, () -> {}));
        String second = bridge.pair(); assertFalse(bridge.accepts(first)); assertTrue(bridge.accepts(second)); assertFalse(bridge.connected());
    }
    @Test void repliesMustMatchTheLiveConnectionAndCallId() throws Exception {
        var bridge = bridge(); String token = bridge.pair(); var sent = new LinkedBlockingQueue<String>();
        bridge.attach("one", token, sent::add, () -> {});
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> bridge.call("scope", json.createObjectNode().put("action", "list")));
            var request = json.readTree(sent.poll(2, TimeUnit.SECONDS));
            var response = json.createObjectNode().put("id", request.path("id").asText()); response.putObject("result").put("accepted", true);
            bridge.receive("stale-connection", response); assertFalse(result.isDone());
            bridge.receive("one", response); assertTrue(result.get(2, TimeUnit.SECONDS).path("accepted").asBoolean());
        }
    }
    @Test void disconnectsUnblockPendingCallsAndOldDisconnectsCannotKillNewConnections() throws Exception {
        var bridge = bridge(); var sent = new LinkedBlockingQueue<String>();
        bridge.attach("one", bridge.pair(), sent::add, () -> {});
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> bridge.call("scope", json.createObjectNode().put("action", "list")));
            assertNotNull(sent.poll(2, TimeUnit.SECONDS));
            bridge.detach("one"); assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        }
        bridge.attach("two", bridge.pair(), sent::add, () -> {});
        bridge.detach("one"); assertTrue(bridge.connected());
    }
}
