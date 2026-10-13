package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.project.ProjectProgressStore;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.project.ProjectStore.ProjectRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** User and Agent entry points share validation, binding and continuation semantics. */
@Service
public class ProjectWorkflowService {
    public record Continuation(String id, String projectId, String agentId, String sourceSessionId) {}
    private final ProjectStore projects;
    private final HubSessionStore sessions;
    private final ProjectProgressStore progress;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private org.springframework.transaction.support.TransactionTemplate transactions;
    private ChatLiveStore live;
    @org.springframework.beans.factory.annotation.Autowired
    public void setLive(ChatLiveStore live) { this.live=live; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactions(org.springframework.transaction.PlatformTransactionManager manager) {
        transactions=new org.springframework.transaction.support.TransactionTemplate(manager);
    }
    public ProjectWorkflowService(ProjectStore projects, HubSessionStore sessions,
                                  ProjectProgressStore progress, JdbcTemplate jdbc, ObjectMapper om) {
        this.projects=projects; this.sessions=sessions; this.progress=progress; this.jdbc=jdbc; this.om=om;
    }

    @Transactional
    public ProjectRow createProject(String name, String kind, Boolean narrative, String note) {
        String title=blank(name), type=blank(kind);
        if (title==null) { throw bad("项目名不能为空"); }
        if (title.length()>120) { throw bad("项目名最多 120 字"); }
        type=type==null ? "other" : type.toLowerCase(Locale.ROOT);
        boolean fiction=narrative!=null ? narrative : List.of("novel","fiction","rpg","story").contains(type);
        List<ProjectRow> matches=projects.list().stream().filter(p->p.name().equalsIgnoreCase(title)).toList();
        if (!matches.isEmpty()) {
            ProjectRow p=matches.get(0);
            if (matches.size()>1 || !"active".equals(p.status()) || p.narrative()!=fiction) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,"已有同名项目，请选择已有项目或使用不同名称");
            }
            return p;
        }
        String id=UUID.randomUUID().toString();
        projects.insert(id,null,title,type,fiction,"active",note);
        return project(id);
    }

    @Transactional
    public ProjectRow organize(String sessionId, String name, String kind, String note) {
        requireRemembered(sessionId);
        ProjectRow p=createProject(name,kind,null,note);
        bind(sessionId,p.id());
        return p;
    }

    @Transactional
    public ProjectRow organizeExisting(String sessionId, String projectId) {
        requireRemembered(sessionId);
        ProjectRow p=project(projectId);
        bind(sessionId,p.id());
        return p;
    }

    @Transactional
    public void bind(String sessionId, String projectId) {
        session(sessionId);
        if (live!=null && live.isRunning(sessionId) && (MemoryScopeCtx.get()==null
                || !sessionId.equals(MemoryScopeCtx.get().sessionId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"当前会话正在生成，请结束本轮后再切换项目");
        }
        String pid=blank(projectId);
        if (pid!=null) { project(pid); }
        sessions.setProject(sessionId,pid);
        // A new binding starts a new authorization context; old explicit links do not carry across it.
        jdbc.update("DELETE FROM session_continuation WHERE target_session_id=?",sessionId);
    }

    @Transactional
    public Continuation continueInNewSession(String sourceId) {
        HubRow source=requireRemembered(sourceId);
        if (source.projectId()!=null) { project(source.projectId()); }
        String id=UUID.randomUUID().toString();
        sessions.insert(id,source.title()==null ? "继续会话" : "继续 · "+source.title(),source.projectId());
        sessions.updateConfig(id,source.modelId(),source.promptId(),source.agentId(),source.showCot(),
                source.contextTrace(),source.maxRounds(),source.showRounds());
        progress.grant(id,sourceId,ChatSessionService.ownerOf(source.agentId()));
        return new Continuation(id,source.projectId(),source.agentId(),sourceId);
    }

    @Transactional
    public void link(String targetId, String sourceId, String agent) {
        HubRow target=requireRemembered(targetId), source=requireRemembered(sourceId);
        if (targetId.equals(sourceId)) { throw bad("不能续接当前会话自身"); }
        if (!agent.equals(ChatSessionService.ownerOf(target.agentId()))
                || !agent.equals(ChatSessionService.ownerOf(source.agentId()))) { throw bad("只能续接同一个 Agent 的会话"); }
        if (!Objects.equals(target.projectId(),source.projectId())) {
            throw bad("来源会话属于不同项目，请先选择对应项目");
        }
        // Restore only the explicitly selected source after the user stopped all continuation.
        Integer stopped=jdbc.queryForObject("SELECT COUNT(*) FROM session_continuation WHERE target_session_id=? AND source_session_id='*' AND agent_id=? AND status='denied'",
                Integer.class,targetId,agent);
        if (stopped!=null && stopped>0) {
            for (HubRow other : sessions.listAll()) {
                if (!other.id().equals(targetId) && !other.id().equals(sourceId)
                        && agent.equals(ChatSessionService.ownerOf(other.agentId())) && Objects.equals(other.projectId(),target.projectId())) {
                    progress.revoke(targetId,other.id(),agent);
                }
            }
            jdbc.update("DELETE FROM session_continuation WHERE target_session_id=? AND source_session_id='*' AND agent_id=?",targetId,agent);
        }
        progress.grant(targetId,sourceId,agent);
    }

    public void revokeAll(String targetId) {
        HubRow target=session(targetId);
        progress.revoke(targetId,"*",ChatSessionService.ownerOf(target.agentId()));
    }

    public String runTool(String hubId, JsonNode args) {
        try {
            return transactions.execute(status -> {
                try { return executeTool(hubId,args); }
                catch (Exception e) { status.setRollbackOnly(); return "项目操作未完成："+e.getMessage(); }
            });
        } catch (Exception e) { return "项目操作未完成："+e.getMessage(); }
    }

    private String executeTool(String hubId, JsonNode args) throws Exception {
        MemoryExec exec=MemoryScopeCtx.get();
        HubRow current=session(hubId);
        if (exec==null || !Objects.equals(exec.sessionId(),hubId)
                || !exec.agentOrBuiltin().equals(ChatSessionService.ownerOf(current.agentId()))) {
            throw bad("项目工具只用于当前用户与 Agent 的对话");
        }
        String action=args.path("action").asText("list");
        if (action.equals("list")) {
            var result=om.createObjectNode();
            result.set("projects",om.valueToTree(projects.list().stream().filter(p->"active".equals(p.status())).toList()));
            result.set("sessions",om.valueToTree(sessions.listAll().stream()
                    .filter(s->!s.id().equals(hubId) && !sessions.memoryOff(s.id())
                            && exec.agentOrBuiltin().equals(ChatSessionService.ownerOf(s.agentId())))
                    .limit(30).map(s->java.util.Map.of("id",s.id(),"title",s.title()==null ? "未命名会话" : s.title(),
                            "projectId",s.projectId()==null ? "" : s.projectId())).toList()));
            return om.writeValueAsString(result);
        }
        if (action.equals("suggest")) {
            var proposed=args.deepCopy();
            String intended=args.path("proposed_action").asText("create");
            if (!List.of("create","bind","continue").contains(intended)) { throw bad("建议只能是创建、绑定或续接"); }
            ((com.fasterxml.jackson.databind.node.ObjectNode)proposed).put("action",intended);
            ((com.fasterxml.jackson.databind.node.ObjectNode)proposed).remove("proposed_action");
            Long userId=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM chat_message WHERE session_id=? AND role='user'",Long.class,hubId);
            jdbc.update("INSERT INTO project_proposal(session_id,agent_id,args_json,user_message_id,created_at) VALUES(?,?,?,?,?) "
                    +"ON CONFLICT(session_id) DO UPDATE SET agent_id=excluded.agent_id,args_json=excluded.args_json,user_message_id=excluded.user_message_id,created_at=excluded.created_at",
                    hubId,exec.agentOrBuiltin(),om.writeValueAsString(proposed),userId,System.currentTimeMillis());
            return om.writeValueAsString(java.util.Map.of("requiresUserRequest",true,"name",args.path("name").asText(""),
                    "kind",args.path("kind").asText("other"),"proposedAction",intended,"message","向用户展示具体建议；用户确认后按完全相同的参数操作。"));
        }
        if (action.equals("progress")) { return om.writeValueAsString(progress.visible(exec)); }
        if (action.equals("read")) {
            String source=args.path("source_session_id").asText("");
            if (!progress.allowed(exec,source)) { throw bad("该来源会话尚未授权续接"); }
            if (args.path("message_id").isIntegralNumber()) {
                long id=args.path("message_id").asLong();
                int offset=Math.max(0,args.path("offset").asInt(0));
                var chunks=jdbc.queryForList("SELECT id,role,substr(content,?,8000) content,length(content) totalChars FROM chat_message "
                        +"WHERE session_id=? AND id=? AND kind IS NULL",offset+1,source,id);
                if (chunks.isEmpty()) { throw bad("来源会话中不存在这条消息"); }
                return om.writeValueAsString(java.util.Map.of("sourceSessionId",source,"offset",offset,"nextOffset",offset+8000,"message",chunks.get(0)));
            }
            int page=Math.max(1,args.path("page").asInt(1));
            if (page>10000) { throw bad("页码过大"); }
            var rows=jdbc.queryForList("SELECT id,role,substr(content,1,12000) content,length(content) totalChars,created_at FROM chat_message "
                    +"WHERE session_id=? AND kind IS NULL ORDER BY id DESC LIMIT 4 OFFSET ?",source,(page-1)*4);
            return om.writeValueAsString(java.util.Map.of("sourceSessionId",source,"page",page,"newestFirst",true,"messages",rows));
        }
        String user=jdbc.query("SELECT content FROM chat_message WHERE session_id=? AND role='user' ORDER BY id DESC LIMIT 1",
                (rs,n)->rs.getString(1),hubId).stream().findFirst().orElse("");
        boolean confirmation=confirmation(user);
        if (!(confirmation ? confirmedProposal(hubId,exec.agentOrBuiltin(),args) : explicitRequest(user,action))) {
            return "需要用户明确要求创建、绑定或继续。请先提出具体建议；不要把建议当作授权。";
        }
        if (action.equals("create")) {
            ProjectRow p=organize(hubId,args.path("name").asText(""),args.path("kind").asText("other"),args.path("note").asText(null));
            clearProposal(hubId);
            return om.writeValueAsString(java.util.Map.of("project",p,"boundSessionId",hubId,"takesEffect","下一轮对话"));
        }
        if (action.equals("bind")) {
            String pid=args.path("project_id").asText("");
            if (pid.isBlank()) { throw bad("请给出 project_id；先 list 查找已有项目"); }
            bind(hubId,pid);
            clearProposal(hubId);
            MemoryExec next=MemoryExec.chat(exec.agentId(),pid,hubId,null,false);
            return om.writeValueAsString(java.util.Map.of("project",project(pid),"progress",progress.visible(next),"takesEffect","下一轮对话"));
        }
        if (action.equals("continue")) {
            String source=args.path("source_session_id").asText("");
            link(hubId,source,exec.agentOrBuiltin());
            clearProposal(hubId);
            return om.writeValueAsString(progress.read(source));
        }
        if (action.equals("revoke")) { progress.revoke(hubId,args.path("source_session_id").asText("*"),exec.agentOrBuiltin()); return "已取消该会话的续接授权"; }
        throw bad("未知项目操作");
    }

    public void validateProject(String id) { project(id); }
    public void clearProposal(String id) { jdbc.update("DELETE FROM project_proposal WHERE session_id=?",id); }

    private static boolean confirmation(String text) {
        return text!=null && text.toLowerCase(Locale.ROOT).replaceAll("[\\s，,。.!！]","")
                .matches("(好|好的|可以|行|同意|就这样|创建吧|绑定吧|继续吧|好创建吧|好绑定吧|好继续吧|yes|ok|okay)");
    }
    private boolean confirmedProposal(String hubId,String agent,JsonNode args) throws Exception {
        var proposals=jdbc.queryForList("SELECT args_json,user_message_id FROM project_proposal WHERE session_id=? AND agent_id=? AND created_at>?",
                hubId,agent,System.currentTimeMillis()-30*60_000L);
        if (proposals.isEmpty()) { return false; }
        Long latest=jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE session_id=? AND role='user'",Long.class,hubId);
        if (latest==null || latest<=((Number)proposals.get(0).get("user_message_id")).longValue()) { return false; }
        JsonNode proposed=om.readTree((String)proposals.get(0).get("args_json"));
        for(String key:List.of("action","name","kind","note","project_id","source_session_id")) {
            String fallback=key.equals("kind") ? "other" : "";
            if (!proposed.path(key).asText(fallback).equals(args.path(key).asText(fallback))) { return false; }
        }
        return true;
    }

    static boolean explicitRequest(String user, String action) {
        String text=user==null ? "" : user.toLowerCase(Locale.ROOT);
        if (text.matches("(?s).*(不要|别|暂不|先不|不必|don't|do not).{0,16}(创建|新建|建成|整理|绑定|继续|create|bind|continue).*")) { return false; }
        if (text.matches("(?s).*(如何|怎么|是否|能否|建议|应该|要不要|讨论|介绍|解释|如果|how to|should we).{0,20}(创建|新建|绑定|继续|create|bind|continue).*")) { return false; }
        if (!text.matches("(?s).*(请|帮我|给我|把|将|我要|我想|好[，,、 ]|可以[，,、 ]|现在|直接|创建|新建|建个|建立|继续|接着|接上|续写|续接|绑定|关联|加入|取消|撤销|停止|create|make|bind|continue|resume|revoke|stop).*")) { return false; }
        return switch (action) {
            case "create" -> text.matches("(?s).*(创建|新建|建个|建成|建立|整理为|整理成|create|make a project).*" );
            case "bind" -> text.matches("(?s).*(绑定|关联|加入|继续|接着|bind|continue|resume).*" );
            case "continue" -> text.matches("(?s).*(继续|接着|接上|续写|续接|continue|resume).*" );
            case "revoke" -> text.matches("(?s).*(取消|撤销|停止|revoke|stop).*" );
            default -> false;
        };
    }

    private HubRow requireRemembered(String id) {
        HubRow s=session(id);
        if (sessions.memoryOff(id)) { throw bad("临时会话不参与项目整理或记忆续接，请先开启记忆"); }
        return s;
    }
    private HubRow session(String id) { return sessions.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在")); }
    private ProjectRow project(String id) {
        ProjectRow p=projects.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"项目不存在"));
        if (!"active".equals(p.status())) { throw bad("项目已归档"); }
        return p;
    }
    private static String blank(String s) { return s==null || s.isBlank() ? null : s.strip(); }
    private static ResponseStatusException bad(String s) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,s); }
}
