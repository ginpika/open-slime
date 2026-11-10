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
    private AgentStore.AgentRow row(String model){return new AgentStore.AgentRow(BuiltinAgents.CORE,"默认助手",true,null,model,0,"note",null,null,0,0,null,null);}

    @Test void codingUsesTheDefaultIdentityAndOnlyRequestsTheIdePrompt(){
        AgentStore agents=mock(AgentStore.class);AgentPromptService prompts=mock(AgentPromptService.class);
        when(agents.peer(BuiltinAgents.CORE)).thenReturn(Optional.of(row("model-1")));
        when(prompts.ideSystem()).thenReturn("default identity plus coding skill");
        CodingAgent agent=new CodingAgent(agents,prompts);
        new CodingAgent(agents,prompts);
        verify(agents,never()).insert(any());
        assertEquals(BuiltinAgents.CORE,agent.profile().id());
        assertEquals("model-1",agent.modelId(null));assertEquals("explicit",agent.modelId("explicit"));
        assertEquals("default identity plus coding skill",agent.instructions());
        verify(prompts).ideSystem();
        when(agents.peer(BuiltinAgents.CORE)).thenReturn(Optional.of(row("model-2")));
        assertEquals("model-2",agent.profile().modelId());assertEquals("model-2",agent.modelId(""));
    }

    @Test void defaultNameAvatarAndModelAreSharedWithTheIde() throws Exception {
        var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("schema.sql")).execute(source);
            var agents = new AgentStore(new org.springframework.jdbc.core.JdbcTemplate(source));
            new AgentSeeder(agents);
            var prompts = mock(AgentPromptService.class);
            var agent = new CodingAgent(agents, prompts);
            var directory = new AgentDirectory(agents, null, prompts, null, json);
            String avatar = "data:image/png;base64,aGVsbG8=";
            directory.update(BuiltinAgents.CORE, "牧濑红莉栖", null, "character-model", null,
                    null, avatar, "研究员", "#6b7280");
            var restarted = new CodingAgent(agents, prompts);
            assertEquals(agent.profile(), restarted.profile());
            assertEquals("牧濑红莉栖", restarted.profile().name());
            assertEquals(avatar, restarted.profile().avatar());
            assertEquals("character-model", restarted.profile().modelId());
            assertEquals(1, directory.peers().size());
            assertThrows(IllegalArgumentException.class, () -> directory.delete(BuiltinAgents.CORE));
        } finally { source.destroy(); }
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
        assertNull(tool.validate(json.createObjectNode().put("action","start").put("repositoryId","p1").put("prompt","build").put("mode","EXECUTE")),
                "syntax accepts EXECUTE; CodingFacade verifies live project dispatch before granting it");
        assertNotNull(tool.validate(json.createObjectNode().put("action","start").put("repositoryId","p1").put("prompt","build").put("mode","ROOT")));
        assertNull(tool.validate(json.createObjectNode().put("action","resume").put("runId","r1")));
    }

    @Test void serialAgendaCapturesEachAuthenticatedActorAndRestoresCallerScope() throws Exception {
        RoundtableAgenda agenda=new RoundtableAgenda();CountDownLatch done=new CountDownLatch(2);List<String> actors=new CopyOnWriteArrayList<>();
        try(var actor=UserScope.bind("alice")){agenda.submit("same-session",()->{actors.add(UserScope.get());done.countDown();});}
        try(var actor=UserScope.bind("bob")){agenda.submit("same-session",()->{actors.add(UserScope.get());done.countDown();});}
        assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(List.of("alice","bob"),actors);assertNull(UserScope.get());
    }
}
