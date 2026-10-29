package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared conclusion command for the UI and issue participants, without runtime dependencies. */
@Service
public class CollaborationIssueActions {
    private final CollaborationStore store;
    private final RoundtableTodoStore board;
    private final RoundtableMessageStore messages;
    private final RoundtableSessionStore sessions;
    private final RoundtableProgress progress;
    private final RuntimeEventBus events;
    private final CollaborationPlan plans;

    public CollaborationIssueActions(CollaborationStore store, RoundtableTodoStore board,
            RoundtableMessageStore messages, RoundtableSessionStore sessions, RoundtableProgress progress, RuntimeEventBus events,
            CollaborationPlan plans) {
        this.store = store; this.board = board; this.messages = messages; this.sessions = sessions;
        this.progress = progress; this.events = events;
        this.plans = plans;
    }

    @Transactional
    public boolean resolve(CollaborationStore.Issue issue, String conclusion) {
        if (!store.resolve(issue.id(), conclusion)) return false;
        messages.insertNotice(issue.parentSessionId(), null,
                "议题「" + issue.title() + "」的结论：\n" + conclusion, "issue_resolved");
        sessions.touch(issue.parentSessionId(), null);
        progress.pause(issue.sessionId(), "议题已形成结论");
        store.workOf(issue.id()).flatMap(board::todo).filter(t -> issue.parentSessionId().equals(t.sessionId()))
                .ifPresent(t -> {
                    if (plans.find(t.sessionId()).isPresent())
                        board.update(t.id(), null, null, null, null, (t.detail() == null ? "" : t.detail()) + "\n议题结论（待主控决定）：" + conclusion);
                    else if (board.transition(t.id(), t.sessionId(), "blocked", "pending", null, null))
                        board.update(t.id(), null, null, null, null,
                                (t.detail() == null ? "" : t.detail()) + "\n议题结论：" + conclusion);
                });
        events.publish(RuntimeEvent.of(RuntimeEventType.ISSUE_RESOLVED, issue.parentSessionId(), "runtime",
                Map.of("issueId", issue.id(), "conclusion", conclusion)));
        return true;
    }

    public boolean heardAnotherMember(String session, String peer) {
        return messages.bySession(session).stream().anyMatch(m -> "peer".equals(m.role())
                && m.peerId() != null && !peer.equals(m.peerId()));
    }

    public String context(String session) {
        StringBuilder result = new StringBuilder();
        messages.bySession(session).stream().filter(m -> "task_context".equals(m.eventKind())).findFirst()
                .ifPresent(m -> result.append(m.content()).append('\n'));
        for (var todo : board.list(session)) {
            result.append("- ").append(todo.id()).append(" [").append(todo.status()).append("] ").append(todo.title()).append('\n');
            if (todo.result() != null) result.append("成果：").append(todo.result()).append('\n');
            if (todo.blockReason() != null) result.append("问题：").append(todo.blockReason()).append('\n');
        }
        return result.toString();
    }
}
