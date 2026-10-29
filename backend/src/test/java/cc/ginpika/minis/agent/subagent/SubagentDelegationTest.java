package cc.ginpika.minis.agent.subagent;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.runtime.*;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.*;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubagentDelegationTest {
    final ObjectMapper json=new ObjectMapper();
    final AgentRunner runner=mock(AgentRunner.class);
    final KnowledgeToolbox toolbox=mock(KnowledgeToolbox.class);
    final ScratchpadStore scratch=mock(ScratchpadStore.class);
    final ApplicationEventPublisher publisher=mock(ApplicationEventPublisher.class);
    SubagentService service;
    @BeforeEach void setup() {
        var context=mock(ContextBuilder.class);
        when(context.build(any())).thenAnswer(i->{var body=json.createObjectNode();body.putArray("messages");return body;});
        var prompts=mock(PromptManager.class);when(prompts.resolve(anyString())).thenReturn("只读调查，返回证据");
        service=new SubagentService(runner,mock(ExecStore.class),mock(TaskStore.class),scratch,prompts,
                new ToolManager(List.of(tool("read_document",ToolRisk.READ),tool("run_command",ToolRisk.EXEC)),json),toolbox,json,context,publisher);
        var sessions=mock(HubSessionStore.class);var row=mock(HubSessionStore.HubRow.class);
        when(row.mode()).thenReturn("multi-agent");when(row.projectId()).thenReturn("project");
        when(sessions.findById("session")).thenReturn(Optional.of(row));
        ReflectionTestUtils.setField(service,"hubSessions",sessions);
        ReflectionTestUtils.setField(service,"projects",mock(cc.ginpika.minis.project.ProjectStore.class));
        when(runner.openExec(any())).thenReturn(7L);
        when(runner.run(any(),eq(7L))).thenReturn(new AgentRunner.Outcome(7,"done","已核查来源 document-12",null));
    }
    static AiTool tool(String name,ToolRisk risk) {
        return new AiTool() {
            public String name(){return name;} public String description(){return name;}
            public Set<ToolSurface> surfaces(){return Set.of(ToolSurface.CHAT);} public ToolRisk risk(){return risk;}
            public ObjectNode parameters(ObjectMapper json){return json.createObjectNode().put("type","object");}
        };
    }
    ObjectNode call(String tools) throws Exception {
        var args=json.createObjectNode().put("instruction","核查一条独立事实并附来源").put("background",false);
        if(tools!=null)args.set("tools",json.readTree(tools));
        var call=json.createObjectNode();call.putObject("function").put("name","delegate_subagent").put("arguments",args.toString());return call;
    }
    @Test void synchronousInvestigationReturnsEvidenceToTheCallerAndRejectsForgedTools() throws Exception {
        String report=service.runChatTool("controller",call(null),null,"session");
        assertTrue(report.contains("document-12"));assertTrue(report.contains("状态=done"));
        var request=org.mockito.ArgumentCaptor.forClass(AgentRunner.Request.class);verify(runner).openExec(request.capture());
        assertEquals("session",request.getValue().sessionId());assertFalse(request.getValue().toolSchemas().toString().contains("run_command"));
        var forged=json.createObjectNode();forged.putObject("function").put("name","run_command").put("arguments","{}");
        assertTrue(request.getValue().dispatch().run(forged,7).content().contains("未执行"));verifyNoInteractions(toolbox);
        verify(scratch).append("session","核查一条独立事实并附来源",7L,"已核查来源 document-12");
        verifyNoInteractions(publisher);
    }
    @Test void aManagedInvestigationCannotRequestCommandAuthority() throws Exception {
        var error=assertThrows(IllegalArgumentException.class,()->service.runChatTool("controller",call("[\"run_command\"]"),null,"session"));
        assertTrue(error.getMessage().contains("只读调研"));verifyNoInteractions(runner,toolbox);
    }
}
