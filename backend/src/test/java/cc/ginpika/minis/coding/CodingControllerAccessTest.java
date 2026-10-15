package cc.ginpika.minis.coding;

import cc.ginpika.minis.coding.api.CodingController;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CodingControllerAccessTest {
    private final CodingFacade coding = mock(CodingFacade.class);
    private final WorkspaceProjects projects = mock(WorkspaceProjects.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CodingController(coding, projects)).build();
        when(coding.capabilities()).thenReturn(new Capabilities(true, false, "image", 60, 30, "workspace"));
        when(projects.open(eq("ordinary-user"), any())).thenReturn(
                new CodingProject("p1", "app", "w1", "workspace", "ssh", "app", null, true, 0));
    }

    private MockHttpServletRequestBuilder project(boolean create) {
        return post("/api/coding/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspaceId\":\"w1\",\"directory\":\".\",\"name\":\"app\",\"create\":" + create + "}")
                .with(request -> { request.setRemoteAddr("192.168.1.20"); return request; });
    }

    private SsoUser actor(String id, String role) {
        return new SsoUser(id, "user", "user", "", role, "");
    }

    @Test void ordinaryAuthenticatedUserCanCreateWorkspaceProject() throws Exception {
        mvc.perform(project(true).requestAttr(SsoInterceptor.ATTR, actor("ordinary-user", "user")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("p1"));
        verify(projects).open("ordinary-user", new OpenProject("w1", ".", "app", true, null));
    }
    @Test void compositionEndpointsRequireAuthenticationAndPropagateTheOwner() throws Exception {
        for(String path:java.util.List.of("/api/coding/roles","/api/coding/sessions","/api/coding/runs/r1/session","/api/coding/runs/r1/executions")){
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/coding/runs/r1/executions").requestAttr(SsoInterceptor.ATTR,actor("ordinary-user","user"))).andExpect(status().isOk());
        verify(coding).children("ordinary-user","r1");
        mvc.perform(put("/api/coding/roles/explore").contentType(MediaType.APPLICATION_JSON).content("{\"instructions\":\"查找相关入口\",\"modelId\":null}")
                .requestAttr(SsoInterceptor.ATTR,actor("ordinary-user","user"))).andExpect(status().isOk());
        verify(coding).saveRole("ordinary-user","explore",new RoleConfig(null,"查找相关入口"));
    }

    @Test void ordinaryAuthenticatedUserCanOpenExistingWorkspaceProject() throws Exception {
        mvc.perform(project(false).requestAttr(SsoInterceptor.ATTR, actor("ordinary-user", "user")))
                .andExpect(status().isOk());
        verify(projects).open("ordinary-user", new OpenProject("w1", ".", "app", false, null));
    }

    @Test void unauthenticatedRequestCannotOpenProject() throws Exception {
        mvc.perform(project(true)).andExpect(status().isUnauthorized());
        verifyNoInteractions(projects);
    }

    @Test void emptyIdentityCannotOpenProject() throws Exception {
        mvc.perform(project(true).requestAttr(SsoInterceptor.ATTR, actor("", "admin")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(projects);
    }

    @Test void disabledModuleCannotCreateProject() throws Exception {
        when(coding.capabilities()).thenReturn(new Capabilities(false, false, "image", 60, 30, "workspace"));
        mvc.perform(project(true).requestAttr(SsoInterceptor.ATTR, actor("ordinary-user", "user")))
                .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(projects);
    }

    @Test void developmentIdentityRemainsLimitedToLoopback() throws Exception {
        mvc.perform(project(true).requestAttr(SsoInterceptor.ATTR, actor("local-dev", "admin")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(projects);
    }

    @Test void registeringBackendHostPathsStillRequiresAdmin() throws Exception {
        mvc.perform(post("/api/coding/repositories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"host\",\"path\":\"/root/project\"}")
                        .requestAttr(SsoInterceptor.ATTR, actor("ordinary-user", "user")))
                .andExpect(status().isForbidden());
        verify(coding, never()).register(anyString(), any());
    }
}
