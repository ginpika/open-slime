package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Converts execution disagreements into isolated, durable issues instead of flooding the main chat. */
@Component
public class CollaborationCoordinator {
    private final CollaborationService collaboration;
    private final CollaborationStore store;
    private final RoundtableTodoStore board;
    private final RoundtableDiscussionRuntime discussions;
    private final cc.ginpika.minis.agent.member.AgentDirectory directory;
    private final CollaborationPlan plans;

    public CollaborationCoordinator(CollaborationService collaboration, CollaborationStore store,
            RoundtableTodoStore board, RoundtableDiscussionRuntime discussions,
            cc.ginpika.minis.agent.member.AgentDirectory directory, RuntimeEventBus events, CollaborationPlan plans) {
        this.collaboration = collaboration; this.store = store; this.board = board;
        this.discussions = discussions; this.directory = directory;
        this.plans = plans;
        events.subscribeAll(row -> {
            if (List.of("TASK_BLOCKED", "DISCUSSION_REQUESTED").contains(row.type())) request(RuntimeEventBus.event(row));
            return true;
        });
    }

    private synchronized void request(RuntimeEvent event) {
        // Managed tasks return blockers to the controller; only its explicit consultation creates an issue.
        var plan = plans.find(event.sessionId()).orElse(null);
        if (plan != null) {
            if (event.taskId() != null || !plan.coordinatorId().equals(event.source())
                    || event.target() == null || event.target().contains("user")) return;
            if (store.issues().stream().anyMatch(i -> event.sessionId().equals(i.parentSessionId()) && "open".equals(i.status()))) return;
            List<String> members = new ArrayList<>(); members.add(plan.coordinatorId());
            for (String target : event.target().split(",")) if (!members.contains(target)) members.add(target);
            if (members.size() < 2) return;
            String reason = event.text("reason", "");
            var issue = collaboration.createIssue(event.sessionId(), reason.length() > 160 ? reason.substring(0, 160) : reason, members);
            discussions.submitUserMessage(issue.sessionId(), reason + "\n请听取成员意见，用 resolve_issue 回传可执行结论供主控决策。", null, null, UUID.randomUUID().toString());
            return;
        }
        if (event.taskId() == null || Arrays.asList((event.target() == null ? "" : event.target()).split(",")).contains("user")) return;
        var todo = board.todo(event.taskId()).filter(t -> event.sessionId().equals(t.sessionId())).orElse(null);
        if (todo == null || !"blocked".equals(todo.status())) return;
        // Issues communicate a local decision; they never recursively create more issues.
        if (store.issues().stream().anyMatch(i -> i.sessionId().equals(event.sessionId()))) {
            discussions.report(event.sessionId(), event.source(), "议题受阻，请形成结论或请求用户：" + event.text("reason", ""), null, "user");
            return;
        }
        if (store.issues().stream().anyMatch(i -> "open".equals(i.status()) && store.workOf(i.id()).filter(todo.id()::equals).isPresent())) return;
        List<String> all = directory.peers(event.sessionId()).stream().map(p -> p.id()).toList();
        List<String> members = new ArrayList<>();
        if (all.contains(event.source())) members.add(event.source());
        if (event.target() == null || event.target().isBlank()) members = new ArrayList<>(all);
        else for (String id : event.target().split(",")) if (all.contains(id) && !members.contains(id)) members.add(id);
        if (members.size() < 2) {
            discussions.report(event.sessionId(), event.source(), "缺少可协商成员：" + event.text("reason", ""), null, "user");
            return;
        }
        String title = "处理受阻：" + todo.title();
        var issue = collaboration.createIssue(event.sessionId(), title.length() > 160 ? title.substring(0, 160) : title, members);
        store.linkWork(issue.id(), todo.id());
        discussions.submitUserMessage(issue.sessionId(), "待办：" + todo.title() + "\n问题：" + event.text("reason", "")
                + "\n围绕此问题沟通并用 resolve_issue 提交可执行结论；无法解决时用 request_discussion 找用户。", null, null, UUID.randomUUID().toString());
    }
}
