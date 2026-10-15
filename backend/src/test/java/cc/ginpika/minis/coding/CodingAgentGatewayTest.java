package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.context.CodingContext;
import cc.ginpika.minis.coding.engine.CodingTools;
import cc.ginpika.minis.coding.integration.CodingAgent;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.runner.CodingProcesses;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodingAgentGatewayTest {
    private final ObjectMapper json=new ObjectMapper();
    private final CodingStore store=mock(CodingStore.class);
    private final CodingWorkspace files=mock(CodingWorkspace.class);
    private final CodingContext context=mock(CodingContext.class);
    private final ModelLoop loop=mock(ModelLoop.class);
    private final SettingsService settings=mock(SettingsService.class);
    private final WorkspaceProjects projects=mock(WorkspaceProjects.class);
    private final CodingAgent agent=mock(CodingAgent.class);
    private final CodingTools tools=mock(CodingTools.class);
    private final CodingFacade facade=spy(new CodingFacade(new CodingProps(),store,files,mock(CodingProcesses.class),
            tools,context,loop,settings,json,projects,agent));
    private final Run run=new Run("r1",1,"alice","p1","goal","agent-model",Mode.PLAN,Runner.WORKSPACE,"QUEUED",1,0,null,null,"NOT_RUN",null,0,0);

    @AfterEach void close(){facade.close();}
    @Test void studioSubmissionUsesSharedAgentModelIdentityAndAuthenticatedWorkerScope() throws Exception {
        when(store.repository("p1","alice")).thenReturn(new Repository("p1","alice","project","workspace://w1/app",null,null,0));
        when(projects.binding("p1")).thenReturn(new WorkspaceProjects.Binding("p1","w1","app","/workspace/app","fingerprint"));
        when(projects.project("alice","p1")).thenReturn(new CodingProject("p1","project","w1","workspace","ssh","app",null,true,0));
        when(agent.modelId(null)).thenReturn("agent-model");when(agent.instructions()).thenReturn("shared identity");
        when(settings.resolveChatModel("agent-model")).thenReturn(new ModelTarget("fixture","key","model"));
        when(settings.chatContextWindow("agent-model")).thenReturn(32000);
        when(store.create(eq("alice"),any(),anyInt(),eq("request-1"),isNull(),eq("shared identity"))).thenReturn(run);
        when(store.profile("r1")).thenReturn(new CodingStore.Profile(BuiltinAgents.CODING,null,"shared identity"));
        when(store.run("r1","alice")).thenReturn(run);
        when(projects.bound(run)).thenReturn(true);when(store.rounds("r1")).thenReturn(10);
        when(context.messages(eq(run),any(),isNull(),eq("shared identity"))).thenReturn(json.createArrayNode());
        when(files.diff("r1")).thenReturn(new Diff("hash",List.of(),"",false));
        CountDownLatch completed=new CountDownLatch(1);
        OpenAiChatClient client=mock(OpenAiChatClient.class);
        var truncated=json.createObjectNode().put("role","assistant").put("reasoning_content","先检查当前项目入口");
        truncated.putArray("tool_calls").addObject().putObject("function").put("name","apply_patch").put("arguments","{\"patches\":[");
        var complete=json.createObjectNode().put("role","assistant");
        complete.putArray("tool_calls").addObject().put("id","complete-read").put("type","function").putObject("function").put("name","read_file").put("arguments","{\"path\":\"README.md\"}");
        when(client.chatRaw(any(),any(),any(),anyString(),any())).thenReturn(
                new OpenAiChatClient.RawResult(truncated,"raw","length"),
                new OpenAiChatClient.RawResult(complete,"raw","tool_calls"),
                new OpenAiChatClient.RawResult(json.createObjectNode().put("role","assistant").put("content","answer"),"raw","stop"));
        when(tools.execute(eq(run),any(),anyList(),any())).thenReturn(List.of("file read result"));
        when(loop.run(any())).thenAnswer(inv->{
            assertEquals(BuiltinAgents.CODING,AgentScope.get());assertEquals("alice",UserScope.get());
            ModelLoop.Spec spec=inv.getArgument(0);
            assertFalse(spec.body().has("max_tokens"),"Unknown provider output capability must not be forced to 8192");
            return new ModelLoop(client).run(spec);
        });
        doAnswer(inv->{if("REVIEWABLE".equals(inv.getArgument(1))){completed.countDown();}return null;}).when(store).phase(anyString(),anyString(),any());
        assertEquals(run,facade.submit("alice",new StartRun("p1","goal",null,Mode.PLAN,Runner.DOCKER,10),"request-1"));
        assertTrue(completed.await(3,TimeUnit.SECONDS),"Mocked coding worker must finish without network or filesystem operations");
        verify(store).create("alice",new StartRun("p1","goal","agent-model",Mode.PLAN,Runner.WORKSPACE,10),10,"request-1",null,"shared identity");
        verify(client,times(3)).chatRaw(any(),any(),any(),anyString(),any());
        verify(tools,times(1)).execute(eq(run),any(),argThat(calls->calls.size()==1 && calls.getFirst().path("id").asText().equals("complete-read")),any());
        verify(store).event(eq("r1"),eq("response.truncated"),any());
        verify(store).event(eq("r1"),eq("reasoning.finished"),argThat(payload->payload instanceof java.util.Map<?,?> row && "先检查当前项目入口".equals(row.get("text"))));
        assertNull(AgentScope.get());assertNull(UserScope.get());
    }

    @Test void repeatedDefaultModelRequestReturnsOriginalTaskAfterAgentConfigChanges(){
        when(store.request("alice","same-key")).thenReturn(run);when(store.requestedModel("r1")).thenReturn(null);
        when(projects.binding("p1")).thenReturn(new WorkspaceProjects.Binding("p1","w1","app","/workspace/app","fingerprint"));
        assertEquals(run,facade.submit("alice",new StartRun("p1","goal",null,Mode.PLAN,Runner.DOCKER,null),"same-key"));
        verify(agent,never()).modelId(any());verify(store,never()).create(anyString(),any(),anyInt(),any(),any(),any());
    }
    @Test void unsetAgentModelResolvesTheSavedGlobalModelBeforeTaskCreation(){
        when(store.repository("p1","alice")).thenReturn(new Repository("p1","alice","project","/fixture",null,null,0));
        when(settings.chatModelId(null)).thenReturn("saved-default");
        when(settings.resolveChatModel("saved-default")).thenReturn(new ModelTarget("fixture","","not-configured"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->facade.submit("alice",new StartRun("p1","goal",null,Mode.PLAN,Runner.DOCKER,10)));
        verify(settings).resolveChatModel("saved-default");verify(store,never()).create(anyString(),any(),anyInt(),any(),any(),any());
    }

    @Test void agentStartLinksTheSameRunToItsOriginatingConversation() throws Exception {
        doReturn(run).when(facade).submit(eq("alice"),any(StartRun.class),eq("gateway-request"));
        when(agent.profile()).thenReturn(new Agent(BuiltinAgents.CODING,"编码 Agent","agent-model"));
        String result=AgentScope.run("planner",()->facade.assistantTool("alice",json.createObjectNode()
                .put("action","start").put("repositoryId","p1").put("prompt","goal"),"gateway-request","chat-session"));
        assertEquals("r1",json.readTree(result).path("run").path("id").asText());
        assertEquals("#/coding?project=p1&run=r1",json.readTree(result).path("url").asText());
        verify(store).linkConversation("alice","r1","chat-session","planner");
    }

    @Test void missingUserAndCommandModeAreRejectedByTheGateway(){
        assertTrue(facade.assistantTool(null,json.createObjectNode().put("action","list"),null).contains("已认证用户"));
        assertTrue(facade.assistantTool("alice",json.createObjectNode().put("action","start").put("mode","EXECUTE"),null).contains("仅支持 PLAN/EDIT"));
        verify(store,never()).create(anyString(),any(),anyInt(),any(),any(),any());
    }

    @Test void continuingACommandTaskCannotBypassWorkbenchAuthorization(){
        Run command=new Run("r1",1,"alice","p1","goal",null,Mode.EXECUTE,Runner.WORKSPACE,"PAUSED",1,0,null,null,"NOT_RUN",null,0,0);
        when(store.run("r1","alice")).thenReturn(command);
        assertTrue(facade.assistantTool("alice",json.createObjectNode().put("action","resume").put("runId","r1"),null).contains("工作台操作"));
        verify(store,never()).nextAttempt(anyString());
    }
    @Test void readingAnotherUsersTaskDoesNotExposeIt(){
        assertTrue(facade.assistantTool("bob",json.createObjectNode().put("action","read").put("runId","r1"),null).contains("不存在"));
        verify(store).run("r1","bob");
    }
}
