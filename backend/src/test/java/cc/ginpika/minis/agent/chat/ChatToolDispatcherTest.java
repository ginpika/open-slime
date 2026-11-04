package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.memory.ManageProjectTool;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.computer.terminal.TerminalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChatToolDispatcherTest {
    private final ObjectMapper om = new ObjectMapper();
    private final ToolValidator validator = mock(ToolValidator.class);
    private final ProjectWorkflowService projects = mock(ProjectWorkflowService.class);
    private final KnowledgeToolbox knowledge = mock(KnowledgeToolbox.class);
    private final TerminalService terminal = mock(TerminalService.class);
    private final TodoService todo = mock(TodoService.class);
    private final HeartbeatTool heartbeat = mock(HeartbeatTool.class);
    private final SubagentTool subagents = mock(SubagentTool.class);
    private final ChatToolDispatcher dispatcher = new ChatToolDispatcher(om, validator, todo,
            heartbeat, subagents, terminal, knowledge, projects);

    @Test void invalidToolDoesNotReachAnyCapability() {
        var call = om.createObjectNode();
        call.putObject("function").put("name", ManageProjectTool.NAME).put("arguments", "{}");
        when(validator.validate(ManageProjectTool.NAME, "{}")).thenReturn("rejected");
        assertEquals("rejected", dispatcher.runTool(call, "batch", "session"));
        verifyNoInteractions(projects, knowledge, terminal, todo, heartbeat, subagents);
    }

    @Test void projectToolPreservesSessionAndSharesUserWorkflow() throws Exception {
        var call = om.createObjectNode();
        call.putObject("function").put("name", ManageProjectTool.NAME)
                .put("arguments", "{\"action\":\"continue\"}");
        when(projects.runTool(eq("session"), any())).thenReturn("accepted");
        assertEquals("accepted", dispatcher.runTool(call, "batch", "session"));
        verify(projects).runTool("session", om.readTree("{\"action\":\"continue\"}"));
        verifyNoInteractions(knowledge, terminal, todo, heartbeat, subagents);
    }
    @Test void computerToolsUseTheirOwnCapabilityAndPreserveTheConversationBoundary() throws Exception {
        var computer = mock(cc.ginpika.minis.ai.computer.integration.ComputerTools.class);
        dispatcher.setComputer(computer);
        var call = om.createObjectNode();
        call.putObject("function").put("name", "chrome").put("arguments", "{\"action\":\"list\"}");
        when(computer.run(eq("chrome"), any(), eq("session"))).thenReturn("tabs");
        assertEquals("tabs", dispatcher.runTool(call, "batch", "session"));
        verify(computer).run("chrome", om.readTree("{\"action\":\"list\"}"), "session");
        verifyNoInteractions(knowledge, terminal);
    }
}
