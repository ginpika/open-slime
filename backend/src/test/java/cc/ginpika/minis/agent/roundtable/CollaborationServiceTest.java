package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.config.RoundtableProps;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationServiceTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        final JdbcTemplate jdbc = new JdbcTemplate(ds);
        final CollaborationStore store = new CollaborationStore(jdbc);
        final RoundtableSessionStore sessions = new RoundtableSessionStore(jdbc);
        final RoundtableMessageStore messages = new RoundtableMessageStore(jdbc);
        final ProjectStore projects = new ProjectStore(jdbc);
        final RoundtableChatService chat = mock(RoundtableChatService.class);
        final AgentDirectory directory = mock(AgentDirectory.class);
        final RoundtableDiscussionRuntime runtime = mock(RoundtableDiscussionRuntime.class);
        final RoundtableWorkRuntime work = mock(RoundtableWorkRuntime.class);
        final CollaborationService service = new CollaborationService(store, chat, sessions, messages, directory, projects, runtime, work,
                new CollaborationIssueActions(store, new RoundtableTodoStore(jdbc), messages, sessions,
                        new RoundtableProgress(jdbc), mock(cc.ginpika.minis.agent.runtime.event.RuntimeEventBus.class), new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc))), new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc)), mock(CollaborationWorkspaceBinding.class));
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(), new ClassPathResource("schema.sql"));
            jdbc.execute("PRAGMA foreign_keys = ON");
            projects.insert("project", null, "协作项目", "software", false, "active", "持续核对真实成果");
            AgentRow a = mock(AgentRow.class), b = mock(AgentRow.class), c = mock(AgentRow.class);
            when(a.id()).thenReturn("a"); when(b.id()).thenReturn("b"); when(c.id()).thenReturn("c");
            when(directory.peers()).thenReturn(List.of(a, b, c));
            when(directory.peers(anyString())).thenReturn(List.of(a, b));
            when(chat.createSession(anyList(), nullable(String.class))).thenAnswer(inv -> {
                String id = sessions.insert(new ObjectMapper().writeValueAsString(inv.getArgument(0)));
                jdbc.update("UPDATE hub_session SET project_id=? WHERE id=?", inv.getArgument(1), id);
                return new AgentDtos.NewSession(id);
            });
            when(chat.session(anyString())).thenAnswer(inv -> {
                var row = sessions.findById(inv.getArgument(0)).orElseThrow();
                return new AgentDtos.SessionView(row.id(), row.title(), null, null, "closed", null,
                        List.of("a", "b"), row.createdAt(), row.updatedAt(), row.projectId());
            });
            when(runtime.snapshot(anyString())).thenAnswer(inv -> new RoundtableDiscussionRuntime.ConversationSnapshot(
                    inv.getArgument(0), "multi-agent", false, null, null, null, Map.of(), Map.of()));
        }
        String task() { return service.task("project", "实现首个可验收版本", List.of("a", "b")).sessionId(); }
        public void close() { ds.destroy(); }
    }

    @Test void taskPersistsSelectedControllerAndRequiresIndependentMembers() throws Exception {
        try (var f = new Fixture()) {
            assertThrows(ResponseStatusException.class, () -> f.service.task("project", "单人成果", List.of("a"), "a"));
            assertThrows(ResponseStatusException.class, () -> f.service.task("project", "无效主控", List.of("a", "b"), "c"));
            String task = f.service.task("project", "由乙主控", List.of("a", "b"), "b").sessionId();
            var plan = f.service.workspace().plans().stream().filter(p -> task.equals(p.sessionId())).findFirst().orElseThrow();
            assertEquals("b", plan.coordinatorId()); assertEquals("planning", plan.phase());
        }
    }

    @Test void issuesHaveTheirOwnHistoryAndReturnOneDurableConclusionToTheMainTask() throws Exception {
        try (var f = new Fixture()) {
            String main = f.task();
            f.messages.insert(main, null, "user", null, null, "请覆盖断线重连", null, null, null, null);
            var issue = f.service.createIssue(main, "重连方案如何选择？", List.of("a", "b"));
            assertNotEquals(main, issue.sessionId());
            assertEquals("project", f.sessions.findById(issue.sessionId()).orElseThrow().projectId());
            assertEquals("实现首个可验收版本", f.sessions.findById(main).orElseThrow().title());
            assertTrue(f.messages.bySession(issue.sessionId()).getFirst().content().contains("请覆盖断线重连"));
            f.messages.insert(issue.sessionId(), null, "peer", "a", "成员 A", "仅属于议题的详细推演", null, null, null, null);
            assertTrue(f.messages.bySession(main).stream().noneMatch(m -> m.content().contains("详细推演")));
            var ended = f.service.resolve(issue.id(), "采用重连令牌，增加断线验证");
            assertEquals("resolved", ended.status());
            assertTrue(f.sessions.isResolvedIssue(issue.sessionId()));
            assertFalse(f.sessions.isResolvedIssue(main));
            assertTrue(f.sessions.hasIssueRelations(main));
            f.service.resolve(issue.id(), "重复提交不改写已确认结论");
            var mainMessages = f.messages.bySession(main);
            assertEquals(1, mainMessages.stream().filter(m -> "issue_resolved".equals(m.eventKind())).count());
            assertTrue(mainMessages.stream().anyMatch(m -> m.content().contains("增加断线验证")));
            assertEquals("采用重连令牌，增加断线验证", f.store.issues().getFirst().conclusion());
        }
    }

    @Test void invalidParticipantsAndNestedIssuesCannotCreateStraySessions() throws Exception {
        try (var f = new Fixture()) {
            String main = f.task();
            int before = f.sessions.listAll().size();
            assertThrows(ResponseStatusException.class, () -> f.service.createIssue(main, "问题", List.of("c")));
            assertThrows(ResponseStatusException.class, () -> f.service.createIssue(main, "问题", List.of()));
            assertEquals(before, f.sessions.listAll().size());
            var issue = f.service.createIssue(main, "有效问题", List.of("a"));
            assertThrows(ResponseStatusException.class, () -> f.service.createIssue(issue.sessionId(), "嵌套问题", List.of("a")));
            assertEquals(before + 1, f.sessions.listAll().size());
        }
    }

    @Test void resolvingWhileDiscussionOrExecutionIsRunningPreservesOpenState() throws Exception {
        try (var f = new Fixture()) {
            var issue = f.service.createIssue(f.task(), "待解决问题", List.of("a"));
            when(f.runtime.snapshot(issue.sessionId())).thenReturn(new RoundtableDiscussionRuntime.ConversationSnapshot(
                    issue.sessionId(), "multi-agent", true, null, null, null, Map.of(), Map.of()));
            assertThrows(ResponseStatusException.class, () -> f.service.resolve(issue.id(), "过早结论"));
            assertEquals("open", f.store.issues().getFirst().status());
            when(f.runtime.snapshot(issue.sessionId())).thenReturn(new RoundtableDiscussionRuntime.ConversationSnapshot(
                    issue.sessionId(), "multi-agent", false, null, null, null, Map.of(), Map.of()));
            when(f.work.isWorking(issue.sessionId(), "a")).thenReturn(true);
            assertThrows(ResponseStatusException.class, () -> f.service.resolve(issue.id(), "仍在执行"));
            assertEquals("open", f.store.issues().getFirst().status());
        }
    }

    @Test void projectMembersArePersistentAndExistingTaskMembershipDoesNotChange() throws Exception {
        try (var f = new Fixture()) {
            String main = f.task();
            String original = f.sessions.findById(main).orElseThrow().memberIds();
            f.service.members("project", List.of("b", "b", "c"));
            assertEquals(List.of("b", "c"), new CollaborationStore(f.jdbc).projectMembers().get("project"));
            assertEquals(original, f.sessions.findById(main).orElseThrow().memberIds());
            assertThrows(ResponseStatusException.class, () -> f.service.members("missing", List.of("a")));
            assertThrows(ResponseStatusException.class, () -> f.service.members("project", List.of("deleted-agent")));
        }
    }
}
