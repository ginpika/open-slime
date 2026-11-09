package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.store.MemoryRetractionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;

class RoundtableRewindTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        final JdbcTemplate jdbc = new JdbcTemplate(ds);
        final RoundtableSessionStore sessions = new RoundtableSessionStore(jdbc);
        final RoundtableMessageStore messages = new RoundtableMessageStore(jdbc);
        final RoundtableTopicStore topics = new RoundtableTopicStore(jdbc);
        final RoundtableProgress progress = new RoundtableProgress(jdbc);
        final CollaborationStore issues = new CollaborationStore(jdbc);
        final RoundtableDiscussionRuntime discussions = mock(RoundtableDiscussionRuntime.class);
        final RoundtableWorkRuntime work = mock(RoundtableWorkRuntime.class);
        final RoundtableRevision revisions = new RoundtableRevision();
        final MemoryRetractionStore memories = mock(MemoryRetractionStore.class);
        final MemoryIngest ingest = mock(MemoryIngest.class);
        final cc.ginpika.minis.agent.runtime.TaskStore tasks = new cc.ginpika.minis.agent.runtime.TaskStore(jdbc);
        final cc.ginpika.minis.agent.runtime.TaskCheckpointService checkpoints = mock(cc.ginpika.minis.agent.runtime.TaskCheckpointService.class);
        final RoundtableRewindService service = new RoundtableRewindService(sessions, messages, topics, discussions,
                work, progress, revisions, issues, memories, ingest, tasks, checkpoints);
        final String root;
        Fixture() throws Exception {
            ScriptUtils.executeSqlScript(ds.getConnection(), new ClassPathResource("schema.sql"));
            jdbc.execute("PRAGMA foreign_keys=ON");
            root = sessions.insert("[]");
            when(discussions.snapshot(anyString())).thenAnswer(inv -> new RoundtableDiscussionRuntime.ConversationSnapshot(
                    inv.getArgument(0), "multi-agent", false, null, null, null, Map.of(), Map.of()));
        }
        long user(String session, String text) { return messages.insert(session, null, "user", null, null, text, null, null, null, null); }
        long peer(String session) { return messages.insert(session, null, "peer", "a", "甲", "回答", null, null, null, null); }
        CollaborationStore.Issue issue() {
            String child = sessions.insert("[]"); long marker = messages.insertNotice(root, null, "议题入口", "issue_open");
            var issue = new CollaborationStore.Issue("issue", root, child, "局部问题", "open", null, marker, 1, null);
            issues.insert(issue); return issue;
        }
        public void close() { ds.destroy(); }
    }

    @Test void rewindsConversationPreservingProjectRecordsAndClearingWaitState() throws Exception {
        try (var f = new Fixture()) {
            f.messages.insertNotice(f.root, null, "项目目标", "task_context");
            long old = f.user(f.root, "之前的问题"); f.peer(f.root);
            long target = f.user(f.root, "希望重新发送的问题"); long reply = f.peer(f.root);
            var issue = f.issue();
            long conclusion = f.messages.insertNotice(f.root, null, "独立议题结论", "issue_resolved");
            f.topics.insert(new RoundtableTopicStore.TopicRow("topic", f.root, "user", null, "waiting", "a", 0, 1, null));
            var board = new RoundtableTodoStore(f.jdbc); String todo = board.add(f.root, null, "a", "已执行工作", null, "a", null);
            board.update(todo, "done", "产出文件", null, null, null);
            assertEquals(2, f.service.rewind(f.root, target).removed());
            assertTrue(f.messages.message(target).isEmpty()); assertTrue(f.messages.message(reply).isEmpty());
            assertTrue(f.messages.message(old).isPresent()); assertTrue(f.messages.message(conclusion).isPresent());
            assertTrue(f.messages.message(issue.messageId()).isPresent());
            assertEquals("done", board.todo(todo).orElseThrow().status());
            assertEquals("closed", f.topics.topic("topic").orElseThrow().status());
            assertFalse(f.progress.allowed(f.root)); assertEquals(1, f.revisions.current(f.root));
            verify(f.memories).retractChat(eq(f.root), eq(List.of(target, reply)), eq(List.of()), eq(List.of()), anyLong());
            verify(f.ingest).rewind(f.root, java.util.Set.of(target, reply));
            verify(f.discussions).resetAfterRewind(f.root);
        }
    }

    @Test void onlyLocalUserQuestionsCanBeRewound() throws Exception {
        try (var f = new Fixture()) {
            long peer = f.peer(f.root); String other = f.sessions.insert("[]"); long foreign = f.user(other, "其他会话");
            for (Long id : java.util.Arrays.asList(peer, foreign, null)) {
                assertEquals(400, assertThrows(ResponseStatusException.class, () -> f.service.rewind(f.root, id)).getStatusCode().value());
            }
            assertEquals(1, f.messages.bySession(f.root).size());
            verifyNoInteractions(f.memories, f.ingest);
        }
    }

    @Test void relatedActiveIssueOrWorkPreventsRewind() throws Exception {
        try (var f = new Fixture()) {
            long target = f.user(f.root, "问题"); var issue = f.issue();
            when(f.work.workingCount(issue.sessionId())).thenReturn(1);
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> f.service.rewind(f.root, target)).getStatusCode().value());
            assertTrue(f.messages.message(target).isPresent()); verifyNoInteractions(f.memories);
            when(f.work.workingCount(issue.sessionId())).thenReturn(0);
            when(f.discussions.snapshot(f.root)).thenReturn(new RoundtableDiscussionRuntime.ConversationSnapshot(
                    f.root, "multi-agent", true, null, null, null, Map.of(), Map.of()));
            assertThrows(ResponseStatusException.class, () -> f.service.rewind(f.root, target));
        }
    }

    @Test void issueRewindDoesNotChangeParentConversationAndResolvedIssuesStayReadOnly() throws Exception {
        try (var f = new Fixture()) {
            long parent = f.user(f.root, "主任务问题"); var issue = f.issue();
            long target = f.user(issue.sessionId(), "议题问题"); f.peer(issue.sessionId());
            assertEquals(2, f.service.rewind(issue.sessionId(), target).removed());
            assertTrue(f.messages.message(parent).isPresent()); assertTrue(f.progress.allowed(f.root));
            long next = f.user(issue.sessionId(), "新的问题"); f.issues.resolve(issue.id(), "结论");
            assertEquals(409, assertThrows(ResponseStatusException.class, () -> f.service.rewind(issue.sessionId(), next)).getStatusCode().value());
            assertTrue(f.messages.message(next).isPresent());
        }
    }

    @Test void rollbackDoesNotClearRuntimeOrIngestState() throws Exception {
        try (var f = new Fixture()) {
            long target = f.user(f.root, "问题");
            var transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.ds));
            transaction.executeWithoutResult(status -> { f.service.rewind(f.root, target); status.setRollbackOnly(); });
            assertTrue(f.messages.message(target).isPresent()); assertTrue(f.progress.allowed(f.root));
            assertEquals(0, f.revisions.current(f.root)); verifyNoInteractions(f.ingest);
            verify(f.discussions, never()).resetAfterRewind(anyString());
        }
    }

    @Test void replacementTopicIsChosenByCreationTimeRatherThanUuid() throws Exception {
        try (var f = new Fixture()) {
            f.topics.insert(new RoundtableTopicStore.TopicRow("zz-old", f.root, "user", null, "closed", null, 0, 1, null));
            f.topics.insert(new RoundtableTopicStore.TopicRow("aa-new", f.root, "user", null, "waiting", null, 0, 2, null));
            f.jdbc.update("UPDATE roundtable_topic SET created_at=1 WHERE id='zz-old'");
            f.jdbc.update("UPDATE roundtable_topic SET created_at=2 WHERE id='aa-new'");
            assertEquals("aa-new", f.topics.latestBySession(f.root).orElseThrow().id());
            assertEquals(List.of("zz-old", "aa-new"), f.topics.topicsBySession(f.root).stream().map(RoundtableTopicStore.TopicRow::id).toList());
        }
    }

    @Test void regularChatEndpointCannotBypassMultiAgentRuntimeChecks() {
        var sessions = mock(cc.ginpika.minis.agent.chat.store.HubSessionStore.class);
        var row = mock(cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow.class);
        when(row.mode()).thenReturn("multi-agent");
        when(sessions.findById("session")).thenReturn(java.util.Optional.of(row));
        var service = new cc.ginpika.minis.agent.chat.ChatSessionService(
                sessions, null, null, null, null, null, null, null, null, null);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.rewind("session", 1L, null)).getStatusCode().value());
    }

    @Test void retractsExecutionMemoryAndCheckpointsWithoutDeletingAuditOrOtherSessions() throws Exception {
        try (var f = new Fixture()) {
            long target = f.user(f.root, "重写要求");
            long task = f.tasks.insert("session", null, "发言", f.root, "a", null);
            String other = f.sessions.insert("[]"); f.tasks.insert("session", null, "其他会话", other, "a", null);
            f.service.rewind(f.root, target);
            verify(f.memories).retractChat(eq(f.root), eq(List.of(target)), eq(List.of(task)), eq(List.of()), anyLong());
            verify(f.checkpoints).save(task, null);
            assertTrue(f.tasks.task(task).isPresent());
        }
    }
}
