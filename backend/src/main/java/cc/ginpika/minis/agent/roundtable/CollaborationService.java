package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.project.ProjectStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A task owns a main session; an issue owns a separate discussion and returns its conclusion to that task. */
@Service
public class CollaborationService {
    public record Workspace(List<CollaborationStore.Issue> issues, Map<String, List<String>> projectMembers,
                            List<CollaborationPlan.Plan> plans, List<CollaborationWorkspaceBinding.Location> workspaceBindings,
                            List<String> archivedTasks) {}
    private final CollaborationPlan plans;
    private final CollaborationWorkspaceBinding bindings;
    private final CollaborationStore store;
    private final RoundtableChatService chat;
    private final RoundtableSessionStore sessions;
    private final RoundtableMessageStore messages;
    private final AgentDirectory directory;
    private final ProjectStore projects;
    private final RoundtableDiscussionRuntime discussions;
    private final RoundtableWorkRuntime work;
    private final CollaborationIssueActions issueActions;

    public CollaborationService(CollaborationStore store, RoundtableChatService chat, RoundtableSessionStore sessions,
                                RoundtableMessageStore messages, AgentDirectory directory, ProjectStore projects,
                                RoundtableDiscussionRuntime discussions, RoundtableWorkRuntime work,
                                CollaborationIssueActions issueActions, CollaborationPlan plans, CollaborationWorkspaceBinding bindings) {
        this.store = store; this.chat = chat; this.sessions = sessions; this.messages = messages;
        this.directory = directory; this.projects = projects; this.discussions = discussions; this.work = work;
        this.issueActions = issueActions;
        this.plans = plans;
        this.bindings = bindings;
    }

    public Workspace workspace() { return new Workspace(store.issues(), store.projectMembers(), plans.list(), bindings.list(),sessions.archivedIds()); }

    public List<cc.ginpika.minis.coding.api.CodingDtos.Workspace> workspaces() { return bindings.workspaces(); }
    public CollaborationWorkspaceBinding.Location bindWorkspace(String project, cc.ginpika.minis.coding.api.CodingDtos.OpenProject request) {
        if (sessions.listAll().stream().filter(s -> project.equals(s.projectId())).anyMatch(s -> discussions.snapshot(s.id()).running() || work.workingCount(s.id())>0))
            throw error(HttpStatus.CONFLICT,"项目仍在讨论或执行，请先停止，再调整工作目录");
        var bound=bindings.bind(project,request);
        for(var session:sessions.listAll()) if(project.equals(session.projectId())) {
            messages.insertNotice(session.id(),null,"项目工作目录已明确为「"+bound.workspaceName()+" / "+bound.directory()
                    +"」。请依据此目录重新核对目标与已有成果；此前从其他目录得到的信息不能作为当前项目已实现或已验证的证据。","workspace_binding");
            plans.reopen(session.id());
        }
        return bound;
    }

    @Transactional
    public List<String> members(String projectId, List<String> ids) {
        project(projectId);
        List<String> members = validateMembers(ids, directory.peers().stream().map(p -> p.id()).toList());
        store.members(projectId, members);
        return members;
    }

    @Transactional
    public AgentDtos.NewSession task(String projectId, String title, List<String> memberIds) {
        return task(projectId, title, memberIds, null);
    }

    @Transactional
    public AgentDtos.NewSession task(String projectId, String title, List<String> memberIds, String coordinatorId) {
        var project = project(projectId);
        String name = required(title, 160, "任务名称");
        List<String> members = validateMembers(memberIds, directory.peers().stream().map(p -> p.id()).toList());
        if (members.size() < 2) throw error(HttpStatus.BAD_REQUEST, "协作任务至少需要两位成员，分别负责执行与独立检查");
        String coordinator = coordinatorId == null || coordinatorId.isBlank() ? members.getFirst() : coordinatorId;
        if (!members.contains(coordinator)) throw error(HttpStatus.BAD_REQUEST, "主控必须是本任务的参与成员");
        var created = chat.createSession(members, projectId);
        plans.create(created.sessionId(), coordinator);
        sessions.touch(created.sessionId(), name);
        messages.insertNotice(created.sessionId(), null, "项目：" + project.name() + "\n工作方向："
                + (project.note() == null ? "尚未补充" : project.note()) + "\n本次推进目标：" + name, "task_context");
        return created;
    }

    @Transactional
    public CollaborationStore.Issue createIssue(String parentId, String title, List<String> memberIds) {
        sessions.requireWritable(parentId);
        var parent = chat.session(parentId);
        if (store.issues().stream().anyMatch(i -> i.sessionId().equals(parentId)))
            throw error(HttpStatus.CONFLICT, "请在主任务内发起议题，议题不再嵌套");
        String name = required(title, 160, "议题问题");
        List<String> members = validateMembers(memberIds, parent.memberIds());
        String child = chat.createSession(members, parent.projectId()).sessionId();
        sessions.touch(child, name);
        String id = UUID.randomUUID().toString();
        long messageId = messages.insertNotice(parentId, null, "发起议题：" + name, "issue_open");
        var issue = new CollaborationStore.Issue(id, parentId, child, name, "open", null,
                messageId, System.currentTimeMillis(), null);
        store.insert(issue);
        // A bounded snapshot gives participants the reason for this discussion without mixing later messages.
        var recent = messages.recentActivity(parentId, 12);
        StringBuilder context = new StringBuilder("主任务：").append(parent.title()).append("\n议题：").append(name)
                .append("\n围绕这个问题沟通，用 resolve_issue 回传结论；议题不另建任务板。\n主任务目标与成果快照：\n")
                .append(issueActions.context(parentId)).append("\n发起时的主任务近况：\n");
        for (int n = recent.size() - 1; n >= 0; n--) {
            var m = recent.get(n);
            context.append(m.peerName() == null ? m.role() : m.peerName()).append("：").append(m.content()).append('\n');
        }
        messages.insertNotice(child, null, context.toString(), "issue_context");
        sessions.touch(parentId, null);
        return issue;
    }

    @Transactional
    public CollaborationStore.Issue resolve(String id, String text) {
        var issue = store.issues().stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "议题不存在"));
        sessions.requireWritable(issue.sessionId());
        if ("resolved".equals(issue.status())) return issue;
        String conclusion = required(text, 8000, "议题结论");
        if (discussions.snapshot(issue.sessionId()).running() || directory.peers(issue.sessionId()).stream()
                .anyMatch(p -> work.isWorking(issue.sessionId(), p.id())))
            throw error(HttpStatus.CONFLICT, "议题仍在推进，请等成员结束后提交结论");
        issueActions.resolve(issue, conclusion);
        return store.issues().stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    private ProjectStore.ProjectRow project(String id) {
        return projects.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "项目不存在"));
    }
    private List<String> validateMembers(List<String> ids, List<String> allowed) {
        if (ids == null || ids.isEmpty() || !allowed.containsAll(ids))
            throw error(HttpStatus.BAD_REQUEST, "请选择至少一位有效的参与成员");
        return ids.stream().distinct().toList();
    }
    private static String required(String value, int limit, String label) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty() || text.length() > limit) throw error(HttpStatus.BAD_REQUEST, label + "不能为空，且不能超过 " + limit + " 字");
        return text;
    }
    private static ResponseStatusException error(HttpStatus status, String text) { return new ResponseStatusException(status, text); }
}
