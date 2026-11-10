package cc.ginpika.minis.agent.member;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentCapabilitiesTest {
    private final AgentStore store = mock(AgentStore.class);
    private final TerminalStore terminals = mock(TerminalStore.class);
    private final AgentDirectory directory = new AgentDirectory(store, null, null, terminals, new ObjectMapper());

    private static AgentStore.AgentRow peer(String id, boolean builtin) {
        return new AgentStore.AgentRow(id, "助手", builtin, "[\"old\"]", null,
                0, null, null, null, 0, 0, null, null);
    }

    private static TerminalStore.TargetRow target(String id) {
        var target = mock(TerminalStore.TargetRow.class);
        when(target.id()).thenReturn(id);
        return target;
    }

    @Test void coreWorkspaceAccessFollowsTheLiveDirectory() {
        var core = peer(BuiltinAgents.CORE, true);
        var one = target("one");
        var added = target("new");
        when(terminals.targets()).thenReturn(List.of(one));
        assertEquals(List.of("one"), directory.declaredTerminals(core));
        when(terminals.targets()).thenReturn(List.of(one, added));
        assertEquals(List.of("one", "new"), directory.declaredTerminals(core));
    }

    @Test void savingEmptyOrStaleWorkspaceGrantsCannotRestrictTheCore() {
        when(store.peer(BuiltinAgents.CORE)).thenReturn(Optional.of(peer(BuiltinAgents.CORE, true)));
        var updated = directory.update(BuiltinAgents.CORE, null, List.of("removed-workspace"),
                null, null, null, null, null, null);
        assertNull(updated.terminals());
        var saved = ArgumentCaptor.forClass(AgentStore.AgentRow.class);
        verify(store).update(saved.capture());
        assertEquals(updated, saved.getValue());
        verifyNoInteractions(terminals);
    }

    @Test void ordinaryAndCodingAgentsKeepTheirOwnWorkspaceGrantsEvenWhenMarkedBuiltin() {
        for (var member : List.of(peer("member", false), peer(BuiltinAgents.CODING, true))) {
            assertEquals(List.of("old"), directory.declaredTerminals(member));
        }
        verifyNoInteractions(terminals);
    }

    @Test void coreResponsibilityPreservesTheSelectedGlobalPersona() {
        var prompts = mock(PromptManager.class);
        when(prompts.chatSystem("chosen")).thenReturn("用户选定的人格");
        var service = new AgentPromptService(null, null, prompts, null);
        var system = service.builtinSystem("chosen");
        assertTrue(system.startsWith("用户选定的人格"));
        assertTrue(system.contains("直接代表软件向用户负责"));
        assertTrue(system.contains("无需逐项申请"));
    }
}
