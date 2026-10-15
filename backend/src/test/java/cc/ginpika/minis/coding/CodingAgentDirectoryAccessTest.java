package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.coding.integration.CodingTaskTool;
import cc.ginpika.minis.web.controller.AgentController;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodingAgentDirectoryAccessTest {
    private final AgentDirectory directory=mock(AgentDirectory.class);
    private final TaskStore tasks=mock(TaskStore.class);
    private final CodingFacade coding=mock(CodingFacade.class);
    private MockMvc mvc;

    @BeforeEach void setup(){
        var row=new AgentStore.AgentRow(BuiltinAgents.CODING,"编码 Agent",true,"[\"coding_task\"]",null,"model",1,null,null,null,0,0,"编码",null);
        when(directory.peer(BuiltinAgents.CODING)).thenReturn(Optional.of(row));when(directory.peers()).thenReturn(List.of(row));
        when(directory.declaredTools(row)).thenReturn(List.of("coding_task"));when(directory.declaredTerminals(row)).thenReturn(List.of());
        when(tasks.recent(BuiltinAgents.CODING,null,100)).thenReturn(List.of(task(1,"coding","r1"),task(2,"coding","r2"),task(3,"agent",null)));
        when(coding.ownsTask("alice",1)).thenReturn(true);
        var controller=new AgentController(directory,null,null,null,null,new ToolManager(List.of(new CodingTaskTool()),new ObjectMapper()),new ObjectMapper(),null,null,null,tasks,null,null,null,null,null,null,null);
        controller.setCoding(coding);mvc=MockMvcBuilders.standaloneSetup(controller).build();
    }
    private TaskStore.TaskRow task(long id,String origin,String payload){return new TaskStore.TaskRow(id,null,"coding".equals(origin)?"code":"subagent","pending","task "+id,null,BuiltinAgents.CODING,null,"p1",null,payload,origin,0,0);}

    @Test void codingAgentIsListedWithItsSpecializedRuntime() throws Exception {
        mvc.perform(get("/api/agents/peers")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(BuiltinAgents.CODING))
                .andExpect(jsonPath("$[0].builtin").value(true)).andExpect(jsonPath("$[0].executionKind").value("CODING"));
    }
    @Test void actionLogLinksOwnedCodingTasksAndHidesAnotherUsersTasks() throws Exception {
        mvc.perform(get("/api/agents/"+BuiltinAgents.CODING+"/tasks")
                        .requestAttr(SsoInterceptor.ATTR,new SsoUser("alice","alice","alice","","user","")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].codingRunId").value("r1")).andExpect(jsonPath("$[0].execId").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(3));
    }
    @Test void missingActorDoesNotRevealCodingTasksThroughTheGenericLog() throws Exception {
        mvc.perform(get("/api/agents/"+BuiltinAgents.CODING+"/tasks"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(3));
    }
    @Test void codingGatewayAppearsAsAnOptionalCapabilityRatherThanDefaultReadAccess() throws Exception {
        mvc.perform(get("/api/agents/tools")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("coding_task")).andExpect(jsonPath("$[0].defaultGranted").value(false))
                .andExpect(jsonPath("$[0].domain").value("coding"));
    }
}
