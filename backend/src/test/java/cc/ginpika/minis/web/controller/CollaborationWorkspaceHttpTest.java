package cc.ginpika.minis.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ginpika.minis.agent.roundtable.*;
import cc.ginpika.minis.agent.workspace.WorkspaceService;
import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import cc.ginpika.minis.coding.config.CodingProps;
import cc.ginpika.minis.coding.repository.CodingWorkspace;
import cc.ginpika.minis.coding.store.CodingStore;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.config.*;
import cc.ginpika.minis.project.*;
import cc.ginpika.minis.web.sso.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** Only the remote transport is replaced: HTTP, SSO, creation, ownership and bindings execute together. */
class CollaborationWorkspaceHttpTest {
    @Test void defaultNewDirectoryCreationKeepsAuthenticatedOwnerThroughTheWholeRequest() throws Exception {
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("coding/schema.sql")).execute(source);
            var jdbc=new JdbcTemplate(source); jdbc.execute("PRAGMA foreign_keys=ON");
            var json=new ObjectMapper(); var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            var coding=new CodingStore(jdbc,tx,json); var projects=new ProjectStore(jdbc);
            projects.insert("project",null,"新项目","software",false,"active",null);
            var targets=mock(TerminalStore.class); var target=mock(TerminalStore.TargetRow.class); var exec=mock(ExecTarget.class);
            when(target.id()).thenReturn("workspace"); when(target.name()).thenReturn("研发环境"); when(target.kind()).thenReturn("ssh");
            when(target.enabled()).thenReturn(true); when(target.workdir()).thenReturn("/workspace");
            when(targets.targetById("workspace")).thenReturn(Optional.of(target)); when(exec.kind()).thenReturn("ssh");
            when(exec.run(any(),anyString(),any())).thenReturn(new ExecTarget.ExecOutcome(0,"/workspace/新项目\n","",1,false,false));
            var workspaceProjects=new WorkspaceProjects(targets,mock(WorkspaceService.class),List.of(exec),jdbc,tx,coding,mock(CodingWorkspace.class),new CodingProps(),json);
            var bindings=new ProjectWorkspaceStore(jdbc);
            var bindingService=new CollaborationWorkspaceBinding(bindings,workspaceProjects,projects);
            var sessions=new RoundtableSessionStore(jdbc);
            var service=new CollaborationService(new CollaborationStore(jdbc),mock(RoundtableChatService.class),sessions,new RoundtableMessageStore(jdbc),
                    mock(cc.ginpika.minis.agent.member.AgentDirectory.class),projects,mock(RoundtableDiscussionRuntime.class),mock(RoundtableWorkRuntime.class),
                    mock(CollaborationIssueActions.class),new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc)),bindingService);
            var controller=new CollaborationController(service,mock(RoundtableRewindService.class),mock(cc.ginpika.minis.agent.roundtable.CollaborationTaskLifecycle.class));
            var interceptor=new SsoInterceptor(new SsoProps(),new DesktopProps(),mock(SsoService.class),json);
            var mvc=MockMvcBuilders.standaloneSetup(controller).addInterceptors(interceptor).build();
            assertNull(UserScope.get());
            mvc.perform(put("/api/projects/project/collaboration-workspace").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workspaceId\":\"workspace\",\"directory\":\".\",\"name\":\"新项目\",\"create\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.root").value("/workspace/新项目"))
                    .andExpect(jsonPath("$.directory").value("新项目"));
            assertNull(UserScope.get());
            var saved=bindings.find("project").orElseThrow();
            assertNotNull(coding.repository(saved.repositoryId(),"local-dev"));
            assertNull(coding.repository(saved.repositoryId(),"another-user"));
            verify(exec,times(1)).run(eq(target),contains("mkdir -- "+ExecTarget.quote("新项目")),any());
            mvc.perform(get("/api/agents/collaboration")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.workspaceBindings[0].enabled").value(true))
                    .andExpect(jsonPath("$.workspaceBindings[0].root").value("/workspace/新项目"));
            assertNull(UserScope.get());
        } finally { source.destroy(); }
    }
}
