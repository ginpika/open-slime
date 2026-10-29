package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.store.MemoryRetractionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationTaskLifecycleTest {
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(source);
        final RoundtableSessionStore sessions=new RoundtableSessionStore(jdbc);
        final RoundtableDiscussionRuntime runtime=mock(RoundtableDiscussionRuntime.class);
        final RoundtableWorkRuntime work=mock(RoundtableWorkRuntime.class);
        final MemoryRetractionStore memories=mock(MemoryRetractionStore.class);
        final MemoryIngest ingest=mock(MemoryIngest.class);
        final RoundtableProgress progress=new RoundtableProgress(jdbc);
        final CollaborationTaskLifecycle service=new CollaborationTaskLifecycle(jdbc,sessions,runtime,work,progress,new RoundtableRevision(),memories,ingest);
        final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        final String root,child,other;
        Fixture() {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            jdbc.execute("PRAGMA foreign_keys=ON");
            root=sessions.insert("[\"a\",\"b\"]");child=sessions.insert("[\"a\",\"b\"]");other=sessions.insert("[\"a\",\"b\"]");
            var messages=new RoundtableMessageStore(jdbc);
            long notice=messages.insertNotice(root,null,"议题","issue_open");messages.insertNotice(child,null,"独立记录","issue_context");
            new CollaborationStore(jdbc).insert(new CollaborationStore.Issue("issue",root,child,"问题","open",null,notice,1L,null));
            var board=new RoundtableTodoStore(jdbc);String todo=board.add(root,null,"a","实验","验收","a",null);
            board.add(child,null,"b","历史工作","证据","a",null);
            jdbc.update("INSERT INTO roundtable_work_run(todo_id,session_id,status,started_at) VALUES(?,?,'done',1)",todo,root);
            new CollaborationPlan(jdbc, new cc.ginpika.minis.project.ProjectKnowledgeStore(jdbc)).create(root,"a");
            new RoundtableTopicStore(jdbc).insert(new RoundtableTopicStore.TopicRow("topic",root,"user",null,"closed",null,0,1L,2L));
            jdbc.update("INSERT INTO roundtable_topic_event(topic_id,seq,actor,kind,created_at) VALUES('topic',1,'user','topic_open',1)");
            new CollaborationStore(jdbc).linkWork("issue",todo);
            jdbc.update("INSERT INTO collaboration_coding_run(run_id,session_id,todo_id,executor_id,assignment_version) VALUES('retained-copy',?,?,'a',1)",root,todo);
            jdbc.update("INSERT INTO task(kind,status,session_id,memory_checkpoint,created_at,updated_at) VALUES('code','suspended',?,'old-checkpoint',1,1)",root);
            when(runtime.snapshot(anyString())).thenAnswer(i->new RoundtableDiscussionRuntime.ConversationSnapshot(
                    i.getArgument(0),"multi-agent",false,null,null,null,Map.of(),Map.of()));
        }
        void archive(boolean value){tx.executeWithoutResult(s->service.archive(root,value));}
        void delete(){tx.executeWithoutResult(s->service.delete(root));}
        int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
        @Override public void close(){source.destroy();}
    }

    @Test void archiveKeepsHistoryAndIssuesReadOnlyAndRestoreDoesNotRestartWork() {
        try(var f=new Fixture()) {
            int messages=f.count("chat_message");
            f.archive(true);
            assertEquals(List.of(f.root),f.sessions.archivedIds());
            assertTrue(f.sessions.archived(f.child));assertFalse(f.sessions.archived(f.other));
            assertThrows(ResponseStatusException.class,()->f.sessions.requireWritable(f.child));
            assertEquals(messages,f.count("chat_message"));assertEquals(1,f.count("collaboration_issue"));
            assertFalse(f.progress.allowed(f.root));assertFalse(f.progress.allowed(f.child));
            f.archive(false);assertTrue(f.sessions.archivedIds().isEmpty());f.sessions.requireWritable(f.child);
            assertFalse(f.progress.allowed(f.root),"恢复可见性不自动恢复副作用执行");
            verifyNoInteractions(f.memories,f.ingest);
        }
    }
    @Test void runningIssueOrQueuedCodingTaskPreventsArchiveAndDeletion() {
        try(var f=new Fixture()) {
            when(f.work.workingCount(f.child)).thenReturn(1);
            assertThrows(ResponseStatusException.class,()->f.archive(true));
            assertThrows(ResponseStatusException.class,f::delete);
            assertTrue(f.sessions.archivedIds().isEmpty());assertEquals(3,f.count("hub_session"));
            when(f.work.workingCount(f.child)).thenReturn(0);
            f.jdbc.update("UPDATE task SET status='pending'");
            assertThrows(ResponseStatusException.class,f::delete);
            verifyNoInteractions(f.memories);
        }
    }
    @Test void deletingMainTaskCleansOwnedSessionsWorkAndGrantsButPreservesOtherSessions() {
        try(var f=new Fixture()) {
            f.jdbc.update("INSERT INTO session_continuation VALUES(?,?,'a',1,'granted')",f.other,f.root);
            f.archive(true);f.delete();
            assertTrue(f.sessions.findById(f.root).isEmpty());assertTrue(f.sessions.findById(f.child).isEmpty());
            assertTrue(f.sessions.findById(f.other).isPresent());
            for(String table:List.of("collaboration_issue","collaboration_issue_work","chat_message","roundtable_todo","roundtable_work_run","roundtable_topic_event","roundtable_topic",
                    "collaboration_coding_run","collaboration_plan","collaboration_task_archive","session_continuation"))
                assertEquals(0,f.count(table),table);
            assertEquals(1,f.count("task"),"独立编码执行记录保留，但不再引用已删会话");
            assertNull(f.jdbc.queryForObject("SELECT session_id FROM task",String.class));
            assertNull(f.jdbc.queryForObject("SELECT memory_checkpoint FROM task",String.class));
            verify(f.memories).retractChat(eq(f.root),anyList(),anyList(),eq(List.of()),eq(0L));
            verify(f.memories).retractChat(eq(f.child),anyList(),anyList(),eq(List.of()),eq(0L));
            verify(f.ingest).rewind(eq(f.root),anySet());verify(f.ingest).rewind(eq(f.child),anySet());
        }
    }
    @Test void cleanupFailureRollsBackWholeTaskAndDoesNotClearLiveCaches() {
        try(var f=new Fixture()) {
            doThrow(new IllegalStateException("撤回失败")).when(f.memories).retractChat(eq(f.child),anyList(),anyList(),anyList(),anyLong());
            assertThrows(IllegalStateException.class,f::delete);
            assertEquals(3,f.count("hub_session"));assertEquals(1,f.count("collaboration_issue"));
            assertEquals(2,f.count("roundtable_todo"));assertEquals(1,f.count("roundtable_work_run"));
            verifyNoInteractions(f.ingest);verify(f.runtime,never()).resetAfterRewind(anyString());
        }
    }
    @Test void issueAndOrdinaryChatAreNotIndependentCollaborationTasks() {
        try(var f=new Fixture()) {
            assertThrows(ResponseStatusException.class,()->f.service.archive(f.child,true));
            assertThrows(ResponseStatusException.class,()->f.service.delete(f.child));
            f.jdbc.update("UPDATE hub_session SET mode='ai' WHERE id=?",f.other);
            assertEquals(404,assertThrows(ResponseStatusException.class,()->f.service.delete(f.other)).getStatusCode().value());
        }
    }
}
