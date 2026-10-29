package cc.ginpika.minis.agent.member;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentCapabilitiesTest {
    private final AgentStore store = mock(AgentStore.class);
    private final TerminalStore terminals = mock(TerminalStore.class);
    private final AiTool read = tool("read", true);
    private final AiTool manage = tool("manage", false);
    private final ToolManager tools = new ToolManager(List.of(read, manage), new ObjectMapper());
    private final AgentDirectory directory = new AgentDirectory(store, null, null, terminals, new ObjectMapper(), tools);

    private static AiTool tool(String name, boolean worker) {
        AiTool tool = mock(AiTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.available()).thenReturn(true);
        when(tool.agentsMayCall()).thenReturn(worker);
        when(tool.surfaces()).thenReturn(java.util.Set.of(ToolSurface.CHAT));
        return tool;
    }

    private static AgentStore.AgentRow peer(String id, boolean builtin) {
        return new AgentStore.AgentRow(id, "助手", builtin, "[\"read\"]", "[\"old\"]", null,
                0, null, null, null, 0, 0, null, null);
    }

    private static TerminalStore.TargetRow target(String id) {
        var target = mock(TerminalStore.TargetRow.class);
        when(target.id()).thenReturn(id);
        return target;
    }

    @Test void coreCapabilitiesFollowTheLiveRegistryAndWorkspaceDirectory() {
        var core = peer(BuiltinAgents.CORE, true);
        var one = target("one");
        var added = target("new");
        when(terminals.targets()).thenReturn(List.of(one));
        assertEquals(List.of("manage", "read"), directory.declaredTools(core));
        assertEquals(List.of("one"), directory.declaredTerminals(core));
        when(manage.available()).thenReturn(false);
        when(terminals.targets()).thenReturn(List.of(one, added));
        assertEquals(List.of("read"), directory.declaredTools(core));
        assertEquals(List.of("one", "new"), directory.declaredTerminals(core));
        assertEquals(List.of(read), tools.grantableTools(ToolSurface.CHAT));
    }

    @Test void savingEmptyOrStaleGrantsCannotRestrictTheCore() {
        when(store.peer(BuiltinAgents.CORE)).thenReturn(Optional.of(peer(BuiltinAgents.CORE, true)));
        var updated = directory.update(BuiltinAgents.CORE, null, List.of(), List.of("removed-workspace"),
                null, null, null, null, null, null);
        assertNull(updated.tools());
        assertNull(updated.terminals());
        assertEquals(List.of("manage", "read"), directory.declaredTools(updated));
        var saved = ArgumentCaptor.forClass(AgentStore.AgentRow.class);
        verify(store).update(saved.capture());
        assertEquals(updated, saved.getValue());
        verifyNoInteractions(terminals);
    }

    @Test void ordinaryAndCodingAgentsKeepTheirOwnGrantsEvenWhenMarkedBuiltin() {
        for (var member : List.of(peer("member", false), peer(BuiltinAgents.CODING, true))) {
            assertEquals(List.of("read"), directory.declaredTools(member));
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
