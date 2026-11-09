package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.context.ContextPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatContextManifestsTest {
    @Test
    void keepsSubmissionsSeparateAndChecksActorAgentAndLiveTurnOnRead() {
        var sessions = mock(HubSessionStore.class);
        var turns = mock(TurnStore.class);
        var manifests = new ChatContextManifests(sessions, turns);
        HubRow session = session("agent-a", "project-a");
        when(sessions.findById("chat")).thenReturn(Optional.of(session));
        when(turns.triggerPresent("chat", "8")).thenReturn(true);
        var selection = mock(ContextPlan.Selection.class);
        var body = new ObjectMapper().createObjectNode().put("model", "fixture-model");
        body.putArray("messages").addObject().put("role", "user").put("content", "body only");
        var first = manifests.listener(session, "alice", 8, "main", 0, 0, selection);
        first.submitted(body, 100);
        first.submitted(body, 100);
        manifests.listener(session, "alice", 8, "main", 0, 1, selection).submitted(body, 100);
        manifests.listener(session, "alice", 8, "interim", 0, 0, selection).submitted(body, 100);

        var rows = manifests.recent("chat", "alice", 8L, 30);
        assertEquals(List.of("interim", "main", "main", "main"),
                rows.stream().map(ChatContextManifests.Manifest::executionId).toList());
        assertEquals(List.of(0, 1, 0, 0), rows.stream().map(ChatContextManifests.Manifest::retry).toList());
        assertEquals(List.of(1, 1, 2, 1), rows.stream().map(ChatContextManifests.Manifest::submission).toList());
        assertEquals(List.of("user"), rows.getFirst().messageRoles());
        assertTrue(manifests.recent("chat", "bob", 8L, 30).isEmpty());

        when(sessions.findById("chat")).thenReturn(Optional.of(session("agent-b", "project-a")));
        assertTrue(manifests.recent("chat", "alice", 8L, 30).isEmpty());
        when(sessions.findById("chat")).thenReturn(Optional.of(session("agent-a", "project-b")));
        assertTrue(manifests.recent("chat", "alice", 8L, 30).isEmpty());
        when(sessions.findById("chat")).thenReturn(Optional.of(session));
        when(turns.triggerPresent("chat", "8")).thenReturn(false);
        assertTrue(manifests.recent("chat", "alice", 8L, 30).isEmpty());
    }

    private static HubRow session(String agentId, String projectId) {
        return new HubRow("chat", null, "ai", null, null, agentId, projectId, true, false,
                0, false, false, 0L, 0L);
    }
}
