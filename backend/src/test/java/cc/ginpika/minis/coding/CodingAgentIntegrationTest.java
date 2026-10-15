package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.ai.core.tool.*;
import cc.ginpika.minis.agent.member.*;
import cc.ginpika.minis.agent.roundtable.RoundtableAgenda;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.integration.CodingAgent;
import cc.ginpika.minis.coding.integration.CodingTaskTool;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.CodingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CodingAgentIntegrationTest {
    private final ObjectMapper json=new ObjectMapper();
    private AgentStore.AgentRow row(String model){return new AgentStore.AgentRow(BuiltinAgents.CODING,"编码 Agent",true,"[\"coding_task\"]",null,model,1,"note",null,null,0,0,"编码",null);}

    @Test void seedsARealProtectedDirectoryMemberAndItsOwnIdentity(){
        AgentStore agents=mock(AgentStore.class);AgentPromptStore versions=mock(AgentPromptStore.class);
        when(agents.peer(BuiltinAgents.CODING)).thenReturn(Optional.empty());when(agents.peerByName("编码 Agent")).thenReturn(Optional.empty());
        when(versions.versions(BuiltinAgents.CODING)).thenReturn(List.of());
        new CodingAgent(agents,mock(AgentPromptService.class),versions);
        var seeded=ArgumentCaptor.forClass(AgentStore.AgentRow.class);verify(agents).insert(seeded.capture());
        assertEquals(BuiltinAgents.CODING,seeded.getValue().id());assertTrue(seeded.getValue().builtin());assertTrue(seeded.getValue().tools().contains("coding_task"));
        verify(versions).saveBinding(BuiltinAgents.CODING,"identity","builtin-coding-identity-v1");
        var directory=new AgentDirectory(agents,null,mock(AgentPromptService.class),null,json);
        when(agents.peer(BuiltinAgents.CODING)).thenReturn(Optional.of(seeded.getValue()));
        assertThrows(IllegalArgumentException.class,()->directory.delete(BuiltinAgents.CODING));
        assertFalse(AgentDirectory.isBuiltin(BuiltinAgents.CODING),"Coding identity must not alias the assistant's global prompt");
    }

    @Test void restartPreservesUserConfigAndBothEntrancesReadTheSameProfile(){
        AgentStore agents=mock(AgentStore.class);AgentPromptStore versions=mock(AgentPromptStore.class);AgentPromptService prompts=mock(AgentPromptService.class);
        when(agents.peer(BuiltinAgents.CODING)).thenReturn(Optional.of(row("model-1")));
        when(prompts.system(BuiltinAgents.CODING)).thenReturn("shared-config");
        CodingAgent agent=new CodingAgent(agents,prompts,versions);new CodingAgent(agents,prompts,versions);
        verify(agents,never()).insert(any());verifyNoInteractions(versions);
        assertEquals("model-1",agent.modelId(null));assertEquals("explicit",agent.modelId("explicit"));assertEquals("shared-config",agent.instructions());
        when(agents.peer(BuiltinAgents.CODING)).thenReturn(Optional.of(row("model-2")));
        assertEquals("model-2",agent.profile().modelId());assertEquals("model-2",agent.modelId(""));
    }

    @Test void codingContextUsesSuppliedIdentityAndRebuildsModeRulesWithoutLosingHistory(){
        CodingStore store=mock(CodingStore.class);
        CodingContext context=new CodingContext(json,mock(CodingWorkspace.class),store,new CodingProps());
        Run run=new Run("run",1,"owner","repo","goal",null,Mode.PLAN,Runner.WORKSPACE,"QUEUED",1,0,null,null,"NOT_RUN",null,0,0);
        Repository repo=new Repository("repo","owner","name","workspace://w1/app",null,null,0);
        var messages=context.messages(run,repo,null,"shared-config");
        assertTrue(messages.get(0).path("content").asText().contains("shared-config"));
        assertTrue(messages.get(0).path("content").asText().contains("PLAN 模式只读分析"));
        when(store.messages("run")).thenReturn(messages.toString());
        // The facade supplies the frozen task profile; context rebuilds the runtime contract.
        assertEquals(messages,context.messages(run,repo,null,"shared-config"));
        Run resumed=new Run("run",1,"owner","repo","goal",null,Mode.EDIT,Runner.WORKSPACE,"QUEUED",2,0,null,null,"NOT_RUN",null,0,0);
        var restored=context.messages(resumed,repo,null,"provided-profile");
        String system=restored.get(0).path("content").asText();
        assertTrue(system.contains("provided-profile"));assertFalse(system.contains("shared-config"));
        assertTrue(system.contains("当前模式：EDIT"));assertFalse(system.contains("当前模式：PLAN"));
        assertEquals(messages.get(1),restored.get(1));assertEquals(messages.size(),restored.size());
    }

    @Test void codingGatewayIsGrantableButNotAPassivelyGrantedReadOnlyTool(){
        CodingTaskTool tool=new CodingTaskTool();ToolManager manager=new ToolManager(List.of(tool),json);
        assertTrue(manager.readOnlyToolsOf(ToolSurface.CHAT).isEmpty());
        assertEquals(1,manager.toolsOf(List.of("coding_task"),ToolSurface.CHAT).size());
        assertEquals(ToolDomain.CODING,tool.domain());
        assertNotNull(tool.validate(json.createObjectNode().put("action","start").put("repositoryId","p1").put("prompt","build").put("mode","EXECUTE")));
        assertNull(tool.validate(json.createObjectNode().put("action","resume").put("runId","r1")));
    }

    @Test void serialAgendaCapturesEachAuthenticatedActorAndRestoresCallerScope() throws Exception {
        RoundtableAgenda agenda=new RoundtableAgenda();CountDownLatch done=new CountDownLatch(2);List<String> actors=new CopyOnWriteArrayList<>();
        try(var actor=UserScope.bind("alice")){agenda.submit("same-session",()->{actors.add(UserScope.get());done.countDown();});}
        try(var actor=UserScope.bind("bob")){agenda.submit("same-session",()->{actors.add(UserScope.get());done.countDown();});}
        assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(List.of("alice","bob"),actors);assertNull(UserScope.get());
    }
}
