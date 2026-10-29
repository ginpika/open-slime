package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.store.MemoryRetractionStore;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Rewind conversation only; project work, issues and already executed effects remain explicit records. */
@Service
public class RoundtableRewindService {
    private final RoundtableSessionStore sessions;
    private final RoundtableMessageStore messages;
    private final RoundtableTopicStore topics;
    private final RoundtableDiscussionRuntime discussions;
    private final RoundtableWorkRuntime work;
    private final RoundtableProgress progress;
    private final RoundtableRevision revisions;
    private final CollaborationStore collaboration;
    private final MemoryRetractionStore memories;
    private final MemoryIngest ingest;
    private final cc.ginpika.minis.agent.runtime.TaskStore tasks;
    private final cc.ginpika.minis.agent.runtime.TaskCheckpointService checkpoints;

    public RoundtableRewindService(RoundtableSessionStore sessions, RoundtableMessageStore messages,
            RoundtableTopicStore topics, RoundtableDiscussionRuntime discussions, RoundtableWorkRuntime work,
            RoundtableProgress progress, RoundtableRevision revisions, CollaborationStore collaboration,
            MemoryRetractionStore memories, MemoryIngest ingest, cc.ginpika.minis.agent.runtime.TaskStore tasks,
            cc.ginpika.minis.agent.runtime.TaskCheckpointService checkpoints) {
        this.sessions = sessions; this.messages = messages; this.topics = topics; this.discussions = discussions;
        this.work = work; this.progress = progress; this.revisions = revisions; this.collaboration = collaboration;
        this.memories = memories; this.ingest = ingest;
        this.tasks = tasks; this.checkpoints = checkpoints;
    }

    @Transactional
    public ChatDtos.Rewind rewind(String sessionId, Long messageId) {
        sessions.findById(sessionId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "会话不存在"));
        sessions.requireWritable(sessionId);
        var issue = collaboration.issueBySession(sessionId).orElse(null);
        if (issue != null && !"open".equals(issue.status())) throw error(HttpStatus.CONFLICT, "已形成结论的议题为只读，请在主任务继续");
        var target = messageId == null ? null : messages.message(messageId).orElse(null);
        if (target == null || !sessionId.equals(target.sessionId()) || !"user".equals(target.role()))
            throw error(HttpStatus.BAD_REQUEST, "回退位置必须为当前会话的一条用户提问");
        // Related discussions may still write project results even when this session itself is idle.
        String root = issue == null ? sessionId : issue.parentSessionId();
        var related = new java.util.ArrayList<String>();
        related.add(root);
        collaboration.issues().stream().filter(i -> root.equals(i.parentSessionId()))
                .forEach(i -> related.add(i.sessionId()));
        for (String id : related) {
            if (discussions.snapshot(id).running() || work.workingCount(id) > 0)
                throw error(HttpStatus.CONFLICT, "请先停止当前任务及议题的讨论和执行，再回退");
        }
        var discarded = messages.rewindRows(sessionId, messageId).stream().map(RoundtableMessageStore.MessageRow::id).toList();
        long fromTime = target.createdAt() == null ? 0 : target.createdAt();
        var taskIds = tasks.idsSince(sessionId, fromTime);
        memories.retractChat(sessionId, discarded, taskIds, List.of(), fromTime);
        taskIds.forEach(id -> checkpoints.save(id, null));
        int removed = messages.rewind(sessionId, messageId);
        topics.topicsBySession(sessionId).forEach(t -> topics.close(t.id()));
        progress.pause(sessionId, "会话已回退，等待重新发送；项目工作和议题记录保留");
        sessions.touch(sessionId, null);
        Runnable committed = () -> {
            revisions.bump(sessionId);
            discussions.resetAfterRewind(sessionId);
            ingest.rewind(sessionId, new HashSet<>(discarded));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed.run(); }
            });
        } else committed.run();
        return new ChatDtos.Rewind(sessionId, removed);
    }

    private static ResponseStatusException error(HttpStatus status, String text) { return new ResponseStatusException(status, text); }
}
