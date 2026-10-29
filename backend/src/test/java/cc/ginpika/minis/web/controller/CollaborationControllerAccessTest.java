package cc.ginpika.minis.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ginpika.minis.agent.roundtable.*;
import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.config.DesktopProps;
import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.web.sso.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** The real HTTP boundary must carry SSO identity into workspace ownership and autonomous continuations. */
class CollaborationControllerAccessTest {
    final CollaborationService service=mock(CollaborationService.class);
    final CollaborationTaskLifecycle lifecycle=mock(CollaborationTaskLifecycle.class);
    final CollaborationController controller=new CollaborationController(service,mock(RoundtableRewindService.class),lifecycle);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
    final SsoUser alice=new SsoUser("alice","alice","用户","","user","");
    MockHttpServletRequestBuilder binding() {
        return put("/api/projects/p/collaboration-workspace").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":\"w\",\"directory\":\"apps/new\",\"create\":false,\"ownerId\":\"another-user\"}");
    }
    @Test void taskLifecycleRequiresIdentityAndCarriesTheAuthenticatedActor() throws Exception {
        mvc.perform(delete("/api/agents/collaboration/tasks/task")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/agents/collaboration/tasks/task/archive").contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\":true}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(lifecycle);
        doAnswer(i->{assertEquals("alice",UserScope.get());return null;}).when(lifecycle).archive("task",true);
        doAnswer(i->{assertEquals("alice",UserScope.get());return null;}).when(lifecycle).delete("task");
        mvc.perform(put("/api/agents/collaboration/tasks/task/archive").requestAttr(SsoInterceptor.ATTR,alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\":true}")).andExpect(status().isOk());
        mvc.perform(delete("/api/agents/collaboration/tasks/task").requestAttr(SsoInterceptor.ATTR,alice)).andExpect(status().isOk());
        verify(lifecycle).archive("task",true);verify(lifecycle).delete("task");assertNull(UserScope.get());
    }
    @Test void loggedInRequestPassesItsOwnerAndRestoresThePreviousThreadScope() throws Exception {
        when(service.bindWorkspace(eq("p"),any())).thenAnswer(inv -> {
            assertEquals("alice",UserScope.get());
            return new CollaborationWorkspaceBinding.Location("p","repo","w","环境","apps/new","/workspace/apps/new",true);
        });
        try(var previous=UserScope.bind("outer-scope")) {
            mvc.perform(binding().requestAttr(SsoInterceptor.ATTR,alice)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.root").value("/workspace/apps/new"));
            assertEquals("outer-scope",UserScope.get());
        }
        assertNull(UserScope.get());
    }
    @Test void actualSsoInterceptorLocalUserCanBindWithoutAnyPreexistingThreadScope() throws Exception {
        var interceptor=new SsoInterceptor(new SsoProps(),new DesktopProps(),mock(SsoService.class),new ObjectMapper());
        var localMvc=MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
        when(service.bindWorkspace(eq("p"),any())).thenAnswer(inv -> {
            assertEquals("local-dev",UserScope.get());
            return new CollaborationWorkspaceBinding.Location("p","repo","w","环境","apps/new","/workspace/apps/new",true);
        });
        assertNull(UserScope.get());
        localMvc.perform(binding().with(req -> { req.setRemoteAddr("127.0.0.1"); return req; })).andExpect(status().isOk());
        assertNull(UserScope.get());
    }
    @Test void missingAndInvalidIdentityCannotBindAndDevelopmentIdentityStillRequiresLoopback() throws Exception {
        mvc.perform(binding()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("需要登录"));
        mvc.perform(binding().requestAttr(SsoInterceptor.ATTR,new SsoUser(" ","bad","","","admin",""))).andExpect(status().isUnauthorized());
        mvc.perform(binding().requestAttr(SsoInterceptor.ATTR,SsoUser.dev()).with(req -> { req.setRemoteAddr("192.0.2.10"); return req; }))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void metadataAndIssueConclusionCarryIdentityAndFailuresCleanUpScope() throws Exception {
        when(service.workspace()).thenAnswer(inv -> {
            assertEquals("alice",UserScope.get());
            return new CollaborationService.Workspace(List.of(),Map.of(),List.of(),List.of(),List.of());
        });
        when(service.resolve("issue","采用此方案")).thenAnswer(inv -> { assertEquals("alice",UserScope.get()); return null; });
        mvc.perform(get("/api/agents/collaboration").requestAttr(SsoInterceptor.ATTR,alice)).andExpect(status().isOk());
        mvc.perform(put("/api/agents/issues/issue/conclusion").requestAttr(SsoInterceptor.ATTR,alice)
                .contentType(MediaType.APPLICATION_JSON).content("{\"conclusion\":\"采用此方案\"}")).andExpect(status().isOk());
        assertNull(UserScope.get());
        when(service.bindWorkspace(anyString(),any())).thenAnswer(inv -> {
            assertEquals("alice",UserScope.get());
            throw new ResponseStatusException(HttpStatus.CONFLICT,"目录需要重新核对");
        });
        mvc.perform(binding().requestAttr(SsoInterceptor.ATTR,alice)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("目录需要重新核对"));
        assertNull(UserScope.get());
    }
    @Test void manualTodoInterventionCapturesTheAuthenticatedActorForRescheduling() throws Exception {
        var work=mock(RoundtableWorkRuntime.class);
        var board=mock(RoundtableTodoStore.class);
        var agents=new AgentController(null,null,null,null,null,null,new ObjectMapper(),null,null,null,null,null,null,null,null,null,work,board,lifecycle);
        var agentMvc=MockMvcBuilders.standaloneSetup(agents).build();
        when(work.checkNow("todo")).thenAnswer(inv -> { assertEquals("alice",UserScope.get()); return true; });
        when(work.drop("todo")).thenAnswer(inv -> { assertEquals("alice",UserScope.get()); return true; });
        when(work.defer("todo")).thenAnswer(inv -> { assertEquals("alice",UserScope.get()); return true; });
        for(String action:List.of("check","drop","defer")) {
            agentMvc.perform(post("/api/agents/todos/todo/"+action).requestAttr(SsoInterceptor.ATTR,alice)).andExpect(status().isOk());
            assertNull(UserScope.get());
        }
    }
}
