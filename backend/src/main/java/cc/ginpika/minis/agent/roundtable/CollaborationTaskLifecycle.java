package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.store.MemoryRetractionStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Lifecycle of a main task and its owned issue sessions. Project files and coding artifacts remain independent. */
@Service
public class CollaborationTaskLifecycle {
    private final JdbcTemplate jdbc;
    private final RoundtableSessionStore sessions;
    private final RoundtableDiscussionRuntime discussions;
    private final RoundtableWorkRuntime work;
    private final RoundtableProgress progress;
    private final RoundtableRevision revisions;
    private final MemoryRetractionStore memories;
    private final MemoryIngest ingest;

    public CollaborationTaskLifecycle(JdbcTemplate jdbc,RoundtableSessionStore sessions,RoundtableDiscussionRuntime discussions,
            RoundtableWorkRuntime work,RoundtableProgress progress,RoundtableRevision revisions,MemoryRetractionStore memories,MemoryIngest ingest) {
        this.jdbc=jdbc;this.sessions=sessions;this.discussions=discussions;this.work=work;
        this.progress=progress;this.revisions=revisions;this.memories=memories;this.ingest=ingest;
    }

    @Transactional
    public void archive(String id,boolean archived) {
        List<String> related=related(id);requireIdle(related);
        if(archived)jdbc.update("INSERT INTO collaboration_task_archive VALUES(?,?) ON CONFLICT(session_id) DO NOTHING",id,System.currentTimeMillis());
        else jdbc.update("DELETE FROM collaboration_task_archive WHERE session_id=?",id);
        for(String session:related) {
            // Restoring exposes records only. A fresh user instruction is still needed to resume execution.
            progress.pause(session,archived?"任务已归档":"任务已恢复，发言后继续");
            jdbc.update("UPDATE collaboration_plan SET version=version+1 WHERE session_id=?",session);
            afterCommit(()->{revisions.bump(session);discussions.resetAfterRewind(session);});
        }
    }

    @Transactional
    public void delete(String id) {
        List<String> related=related(id);requireIdle(related);
        // Remove ownership edges first; each issue session is deleted together with its main task.
        jdbc.update("DELETE FROM collaboration_issue WHERE parent_session_id=?",id);
        for(String session:related) {
            var messageIds=jdbc.queryForList("SELECT id FROM chat_message WHERE session_id=?",Long.class,session);
            var taskIds=jdbc.queryForList("SELECT id FROM task WHERE session_id=?",Long.class,session);
            memories.retractChat(session,messageIds,taskIds,List.of(),0);
            jdbc.update("UPDATE task SET memory_checkpoint=NULL,session_id=NULL WHERE session_id=?",session);
            jdbc.update("DELETE FROM collaboration_task_quality WHERE todo_id IN (SELECT id FROM roundtable_todo WHERE session_id=?)",session);
            jdbc.update("DELETE FROM collaboration_assignment WHERE session_id=?",session);
            jdbc.update("DELETE FROM roundtable_topic_event WHERE topic_id IN (SELECT id FROM roundtable_topic WHERE session_id=?)",session);
            for(String table:List.of("roundtable_topic","roundtable_work_run","roundtable_todo","runtime_event","tool_execution",
                    "chat_todo","subagent_scratch","project_proposal","chat_message")) {
                jdbc.update("DELETE FROM "+table+" WHERE session_id=?",session);
            }
            jdbc.update("DELETE FROM session_continuation WHERE source_session_id=? OR target_session_id=?",session,session);
            sessions.delete(session);
            afterCommit(()->{
                revisions.bump(session);discussions.resetAfterRewind(session);
                ingest.rewind(session,new HashSet<>(messageIds));
            });
        }
    }

    private List<String> related(String id) {
        sessions.findById(id).orElseThrow(()->error(HttpStatus.NOT_FOUND,"协作任务不存在"));
        Boolean child=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM collaboration_issue WHERE session_id=?)",Boolean.class,id);
        if(Boolean.TRUE.equals(child))throw error(HttpStatus.CONFLICT,"请在主任务上归档或删除，议题随主任务统一管理");
        var result=new ArrayList<String>();result.add(id);
        result.addAll(jdbc.queryForList("SELECT session_id FROM collaboration_issue WHERE parent_session_id=?",String.class,id));
        return result;
    }

    private void requireIdle(List<String> related) {
        for(String id:related) {
            Integer active=jdbc.queryForObject("SELECT count(*) FROM task WHERE session_id=? AND status IN ('pending','running')",Integer.class,id);
            if(discussions.snapshot(id).running() || work.workingCount(id)>0 || active!=null && active>0)
                throw error(HttpStatus.CONFLICT,"任务或关联议题仍在讨论、排队或执行，请先停止后再操作");
        }
    }
    private static void afterCommit(Runnable action) {
        if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization(){@Override public void afterCommit(){action.run();}});
        else action.run();
    }
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
