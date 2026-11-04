package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.ai.computer.terminal.TerminalService;
import cc.ginpika.minis.ai.computer.terminal.store.TerminalStore;
import cc.ginpika.minis.coding.api.CodingDtos;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.config.TerminalProps;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.project.ProjectWorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class CollaborationWorkspaceBindingTest {
    final ObjectMapper json=new ObjectMapper();
    class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final ProjectWorkspaceStore store=new ProjectWorkspaceStore(jdbc);
        final ProjectStore projects=new ProjectStore(jdbc);
        final WorkspaceProjects workspace=mock(WorkspaceProjects.class);
        final CollaborationWorkspaceBinding service=new CollaborationWorkspaceBinding(store,workspace,projects);
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("coding/schema.sql"));
            jdbc.execute("PRAGMA foreign_keys=ON");
            projects.insert("project",null,"新项目","software",false,"active",null);
            jdbc.update("INSERT INTO coding_repository VALUES('repo','alice','代码','workspace://w/new-app',NULL,NULL,0)");
            jdbc.update("INSERT INTO hub_session(id,mode,project_id,created_at) VALUES('main','multi-agent','project',0),('issue','multi-agent','project',0),('chat','ai','project',0)");
            when(workspace.project("alice","repo")).thenReturn(new CodingDtos.CodingProject("repo","代码","w","研发环境","ssh","new-app",null,true,0));
            when(workspace.binding("repo")).thenReturn(new WorkspaceProjects.Binding("repo","w","new-app","/workspace/new-app","fingerprint"));
        }
        public void close() { ds.destroy(); }
    }
    com.fasterxml.jackson.databind.JsonNode call(String name,String args) {
        var call=json.createObjectNode(); call.putObject("function").put("name",name).put("arguments",args); return call;
    }
    @Test void unboundProjectsCannotBorrowATerminalOrRepositoryAndDoNotResolveAnotherProject() throws Exception {
        try(var f=new Fixture();var user=UserScope.bind("alice")) {
            var terminal=mock(TerminalService.class);
            assertTrue(f.service.context("main").contains("未绑定"));
            assertTrue(f.service.terminal(call("run_command","{}"),"main",Set.of("w"),terminal).contains("未执行"));
            assertTrue(f.store.scope("main").constrained()); assertNull(f.store.scope("main").repositoryId());
            assertFalse(f.store.scope("chat").constrained());
            verifyNoInteractions(terminal,f.workspace);
        }
    }
    @Test void explicitSelectionPersistsExactDirectoryAndIsSharedByMainAndIssue() throws Exception {
        try(var f=new Fixture();var user=UserScope.bind("alice")) {
            var request=new CodingDtos.OpenProject("w","new-app",null,false,null);
            var project=f.workspace.project("alice","repo");
            when(f.workspace.open(eq("alice"),any())).thenReturn(project);
            var selected=f.service.bind("project",request);
            assertEquals("/workspace/new-app",selected.root());
            assertEquals("repo",f.store.scope("issue").repositoryId());
            assertEquals(f.service.context("main"),f.service.context("issue"));
            assertTrue(f.service.context("main").contains("repositoryId=repo"));
            verify(f.workspace).open(eq("alice"),argThat(r -> r.workspaceId().equals("w") && r.directory().equals("new-app") && !r.create()));
            when(f.workspace.open(eq("alice"),any())).thenThrow(new IllegalArgumentException("目录不存在"));
            assertThrows(IllegalArgumentException.class,() -> f.service.bind("project",request));
            assertEquals("repo",f.store.find("project").orElseThrow().repositoryId());
        }
    }
    @Test void unavailableBindingNeverFallsBackToAnotherWorkspace() throws Exception {
        try(var f=new Fixture();var user=UserScope.bind("alice")) {
            f.store.bind("project","repo");
            when(f.workspace.project("alice","repo")).thenReturn(new CodingDtos.CodingProject("repo","代码","w","研发环境","ssh","new-app",null,false,0));
            var terminal=mock(TerminalService.class);
            assertTrue(f.service.terminal(call("run_command","{}"),"main",Set.of("w"),terminal).contains("失效"));
            verifyNoInteractions(terminal);
        }
    }
    @Test void scopedTerminalPreservesMemberAuthorizationAndStartsInTheBoundProjectDirectory() {
        var targets=mock(TerminalStore.class); var exec=mock(ExecTarget.class);
        var own=mock(TerminalStore.TargetRow.class); var other=mock(TerminalStore.TargetRow.class);
        when(own.id()).thenReturn("w"); when(own.name()).thenReturn("研发环境"); when(own.kind()).thenReturn("ssh"); when(own.enabled()).thenReturn(true);
        when(own.workdir()).thenReturn("/workspace"); when(other.id()).thenReturn("other");
        when(targets.targetByName("研发环境")).thenReturn(Optional.of(own)); when(targets.targetByName("其他环境")).thenReturn(Optional.of(other));
        when(targets.targetById("w")).thenReturn(Optional.of(own)); when(exec.kind()).thenReturn("ssh");
        when(exec.run(any(),anyString(),any())).thenReturn(new ExecTarget.ExecOutcome(0,"ok","",1,false,false));
        var terminal=new TerminalService(new TerminalProps(),targets,json,List.of(exec));
        assertTrue(terminal.runScoped(call("run_command","{\"target\":\"其他环境\",\"command\":\"pwd\"}"),"chat",Set.of("w","other"),"w","/workspace/new-app").content().contains("不是本项目"));
        terminal.runScoped(call("run_command","{\"target\":\"研发环境\",\"command\":\"pwd\"}"),"chat",Set.of("other"),"w","/workspace/new-app");
        verify(exec,never()).run(any(),anyString(),any());
        terminal.runScoped(call("run_command","{\"target\":\"研发环境\",\"command\":\"pwd\"}"),"chat",Set.of("w"),"w","/workspace/new-app");
        verify(exec).run(eq(own),contains("pwd -P"),argThat(o -> "/workspace/new-app".equals(o.workdir())));
        String list=terminal.runScoped(call("list_terminals","{}"),"chat",Set.of("w","other"),"w","/workspace/new-app").content();
        assertTrue(list.contains("/workspace/new-app")); assertFalse(list.contains("其他环境"));
    }
}
