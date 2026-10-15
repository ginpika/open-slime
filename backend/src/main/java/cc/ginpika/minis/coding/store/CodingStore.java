package cc.ginpika.minis.coding.store;

import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.coding.context.CodingExecutionScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.stereotype.Repository
public class CodingStore implements cc.ginpika.minis.ai.runtime.AgentActivitySource {
    private static final String RUN_SELECT="SELECT r.*,(SELECT session_id FROM coding_session_run WHERE run_id=r.id) session_id FROM coding_run r";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    public CodingStore(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json) {
        this.jdbc = jdbc; this.tx = tx; this.json = json;
    }
    public Repository register(String owner, String name, String path, String projectId, String validation) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO coding_repository VALUES(?,?,?,?,?,?,?)", id, owner, name, path, projectId, validation, System.currentTimeMillis());
        return repository(id, owner);
    }
    public List<Repository> repositories(String owner) {
        return jdbc.query("SELECT * FROM coding_repository WHERE owner_id=? ORDER BY created_at DESC", CodingStore::repo, owner);
    }
    public Repository repository(String id, String owner) {
        return jdbc.query("SELECT * FROM coding_repository WHERE id=? AND owner_id=?", CodingStore::repo, id, owner)
                .stream().findFirst().orElse(null);
    }
    public Run create(String owner, StartRun req, int rounds) {
        return create(owner,req,rounds,null);
    }
    public Run request(String owner,String key){return jdbc.query(RUN_SELECT+" WHERE owner_id=? AND request_key=?",CodingStore::runRow,owner,key).stream().findFirst().orElse(null);}
    public Run create(String owner, StartRun req, int rounds,String requestKey) {
        return create(owner,req,rounds,requestKey,req.modelId(),null);
    }
    public Run create(String owner, StartRun req, int rounds,String requestKey,String requestedModel,String instructions) {
        return tx.execute(status -> {
            String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO task(kind,status,title,agent_id,model_id,workspace_id,payload,origin,created_at,updated_at) VALUES('code','pending',?,?,?,?,?, 'coding',?,?)",
                    req.prompt().substring(0, Math.min(80, req.prompt().length())), BuiltinAgents.CODING, req.modelId(), req.repositoryId(), id, now, now);
            long taskId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            jdbc.update("INSERT INTO coding_run(id,task_id,owner_id,repository_id,prompt,model_id,mode,runner,max_rounds,status,request_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?,?)",
                    id, taskId, owner, req.repositoryId(), req.prompt(), req.modelId(), req.mode().name(), req.runner().name(), rounds, requestKey, now, now);
            jdbc.update("INSERT INTO coding_run_agent(run_id,agent_id,requested_model_id,instructions) VALUES(?,?,?,?)",
                    id,BuiltinAgents.CODING,requestedModel,instructions);
            attachSession(run(id,owner),req.sessionId());
            event(id, "run.created", java.util.Map.of("taskId", taskId, "mode", req.mode().name(), "agentId",BuiltinAgents.CODING));
            return run(id, owner);
        });
    }
    public record Profile(String agentId,String requestedModelId,String instructions) {}
    public Profile profile(String id) {
        return jdbc.query("SELECT * FROM coding_run_agent WHERE run_id=?",(r,n)->
                new Profile(r.getString("agent_id"),r.getString("requested_model_id"),r.getString("instructions")),id)
                .stream().findFirst().orElse(null);
    }
    public String requestedModel(String id) {
        Profile profile=profile(id);return profile==null?runInternal(id).modelId():profile.requestedModelId();
    }
    public boolean ownsTask(String owner,long taskId) {
        return owner!=null && !owner.isBlank() && jdbc.queryForObject(
                "SELECT COUNT(*) FROM coding_run WHERE owner_id=? AND (task_id=? OR id IN (SELECT run_id FROM coding_execution WHERE task_id=?))",Integer.class,owner,taskId,taskId)>0;
    }
    public void linkConversation(String owner,String id,String sessionId,String requesterAgent) {
        if(sessionId==null || sessionId.isBlank()){return;}
        tx.executeWithoutResult(status->{
            int count=jdbc.update("UPDATE task SET session_id=? WHERE id=(SELECT task_id FROM coding_run WHERE id=? AND owner_id=?) AND session_id IS NULL",sessionId,id,owner);
            if(count>0){event(id,"agent.delegated",java.util.Map.of("sessionId",sessionId,"requesterAgentId",requesterAgent==null?"builtin":requesterAgent,"agentId",BuiltinAgents.CODING));}
        });
    }
    public List<Run> runs(String owner) {
        return jdbc.query(RUN_SELECT+" WHERE owner_id=? ORDER BY updated_at DESC LIMIT 100", CodingStore::runRow, owner);
    }
    @Override public List<Activity> activities(String owner,String sessionId) {
        if(owner==null || owner.isBlank()){return List.of();}
        String sql="SELECT t.agent_id, "
                +"SUM(CASE WHEN r.status='QUEUED' THEN 1 ELSE 0 END) pending, "
                +"SUM(CASE WHEN r.status IN ('PREPARING','RUNNING','WAITING_AGENTS','VERIFYING','CANCELLING','ACCEPTING') OR EXISTS(SELECT 1 FROM coding_execution e WHERE e.run_id=r.id AND e.status IN ('QUEUED','RUNNING')) THEN 1 ELSE 0 END) doing, "
                +"SUM(CASE WHEN r.status='COMPLETED' OR (r.status='REVIEWABLE' AND r.mode='PLAN') THEN 1 ELSE 0 END) done "
                +"FROM coding_run r JOIN task t ON t.id=r.task_id WHERE r.owner_id=?";
        boolean scoped=sessionId!=null && !sessionId.isBlank();
        if(scoped){sql+=" AND t.session_id=?";}
        sql+=" GROUP BY t.agent_id";
        org.springframework.jdbc.core.RowMapper<Activity> mapper=(r,n)->new Activity(r.getString("agent_id"),r.getInt("pending"),r.getInt("doing"),r.getInt("done"));
        return scoped?jdbc.query(sql,mapper,owner,sessionId):jdbc.query(sql,mapper,owner);
    }
    public Run run(String id, String owner) {
        return jdbc.query(RUN_SELECT+" WHERE id=? AND owner_id=?", CodingStore::runRow, id, owner).stream().findFirst().orElse(null);
    }
    public Run runInternal(String id){return jdbc.query(RUN_SELECT+" WHERE id=?",CodingStore::runRow,id).stream().findFirst().orElseThrow();}
    public Runner runRunner(String id){return Runner.valueOf(jdbc.queryForObject("SELECT runner FROM coding_run WHERE id=?",String.class,id));}
    public int rounds(String id) { return jdbc.queryForObject("SELECT max_rounds FROM coding_run WHERE id=?", Integer.class, id); }
    public String messages(String id) { return jdbc.queryForObject("SELECT messages FROM coding_run WHERE id=?", String.class, id); }
    private void attachSession(Run run,String requested) {
        long now=System.currentTimeMillis();String sessionId=requested==null || requested.isBlank()?run.id():requested;
        if(requested==null || requested.isBlank()){
            jdbc.update("INSERT OR IGNORE INTO coding_session(id,owner_id,repository_id,title,last_run_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                    sessionId,run.ownerId(),run.repositoryId(),run.prompt().substring(0,Math.min(80,run.prompt().length())),run.id(),now,now);
        }else{
            Session session=session(run.ownerId(),requested);
            if(session==null || !session.repositoryId().equals(run.repositoryId())){throw new IllegalArgumentException("编程会话不存在或不属于当前项目");}
            Run previous=session.lastRunId()==null?null:run(session.lastRunId(),run.ownerId());
            if(previous!=null && !("COMPLETED".equals(previous.status()) || (previous.mode()==Mode.PLAN && "REVIEWABLE".equals(previous.status())))){
                throw new IllegalArgumentException("当前会话还有未完成或未应用的修改，请先继续、停止或审阅当前任务");
            }
            int updated=jdbc.update("UPDATE coding_session SET last_run_id=?,updated_at=? WHERE id=? AND owner_id=? AND last_run_id IS ?",run.id(),now,sessionId,run.ownerId(),session.lastRunId());
            if(updated!=1){throw new IllegalStateException("会话已被其他请求推进，请刷新后重试");}
        }
        jdbc.update("INSERT OR IGNORE INTO coding_session_run(run_id,session_id) VALUES(?,?)",run.id(),sessionId);
    }
    public Session session(String owner,String id){return jdbc.query("SELECT * FROM coding_session WHERE id=? AND owner_id=?",CodingStore::sessionRow,id,owner).stream().findFirst().orElse(null);}
    public Session sessionOf(Run run){
        String id=jdbc.query("SELECT session_id FROM coding_session_run WHERE run_id=?",(r,n)->r.getString(1),run.id()).stream().findFirst().orElse(null);
        if(id!=null){return session(run.ownerId(),id);}
        return tx.execute(status->{attachSession(run,null);return session(run.ownerId(),run.id());});
    }
    public List<Session> sessions(String owner){return jdbc.query("SELECT * FROM coding_session WHERE owner_id=? ORDER BY updated_at DESC LIMIT 100",CodingStore::sessionRow,owner);}
    public String inheritedMessages(String id){return jdbc.query("SELECT s.messages FROM coding_session s JOIN coding_session_run l ON s.id=l.session_id WHERE l.run_id=?",(r,n)->r.getString(1),id).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);}
    public List<Run> sessionRuns(String owner,String id){return jdbc.query("SELECT r.*,l.session_id FROM coding_run r JOIN coding_session_run l ON r.id=l.run_id WHERE r.owner_id=? AND l.session_id=? ORDER BY l.rowid",CodingStore::runRow,owner,id);}
    public void mode(String id,Mode mode){jdbc.update("UPDATE coding_run SET mode=? WHERE id=?",mode.name(),id);}
    public void checkpoint(String id, JsonNode messages, int round) {
        jdbc.update("UPDATE coding_run SET messages=?,round=?,checkpoint_attempt=attempt,updated_at=? WHERE id=?", messages.toString(), round, System.currentTimeMillis(), id);
        jdbc.update("UPDATE coding_session SET messages=?,updated_at=? WHERE last_run_id=?",messages.toString(),System.currentTimeMillis(),id);
    }
    public record Input(long id,String content) {}
    public void input(String id,String text){
        tx.executeWithoutResult(status->{jdbc.update("INSERT INTO coding_input(run_id,content,created_at) VALUES(?,?,?)",id,text,System.currentTimeMillis());event(id,"input.received",java.util.Map.of("text",text));});
    }
    public List<Input> pendingInputs(String id){return jdbc.query("SELECT id,content FROM coding_input WHERE run_id=? AND applied_at IS NULL ORDER BY id",(r,n)->new Input(r.getLong("id"),r.getString("content")),id);}
    public void applyInputs(String id,JsonNode messages,int round,List<Input> inputs){
        tx.executeWithoutResult(status->{
            checkpoint(id,messages,round);
            for(Input input:inputs){jdbc.update("UPDATE coding_input SET applied_at=? WHERE id=? AND run_id=?",System.currentTimeMillis(),input.id(),id);event(id,"input.applied",java.util.Map.of("inputId",input.id(),"text",input.content(),"round",round));}
        });
    }
    public void phase(String id, String phase, String error) {
        tx.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            jdbc.update("UPDATE coding_run SET status=?,error=?,updated_at=? WHERE id=?", phase, error, now, id);
            String generic = switch (phase) {
                case "QUEUED" -> "pending";
                case "PREPARING", "RUNNING", "WAITING_AGENTS", "VERIFYING", "CANCELLING", "ACCEPTING" -> "running";
                case "FAILED" -> "failed";
                case "CANCELLED" -> "cancelled";
                case "INTERRUPTED" -> "interrupted";
                case "COMPLETED" -> "done";
                case "REVIEWABLE" -> "PLAN".equals(jdbc.queryForObject("SELECT mode FROM coding_run WHERE id=?",String.class,id))?"done":"suspended";
                default -> "suspended";
            };
            jdbc.update("UPDATE task SET status=?,error=?,updated_at=? WHERE id=(SELECT task_id FROM coding_run WHERE id=?)", generic, error, now, id);
            event(id, "run.phase_changed", java.util.Map.of("status", phase, "error", error == null ? "" : error));
        });
    }
    public void answer(String id, String answer) { jdbc.update("UPDATE coding_run SET answer=? WHERE id=?", answer, id); }
    public void artifact(String id, String hash) { jdbc.update("UPDATE coding_run SET artifact_hash=? WHERE id=?", hash, id); }
    public void validation(String id, String status) { validation(id,status,null); }
    public void validation(String id,String status,String hash){jdbc.update("UPDATE coding_run SET validation_status=?,validation_hash=? WHERE id=?",status,hash,id);}
    public String validationHash(String id){return jdbc.queryForObject("SELECT validation_hash FROM coding_run WHERE id=?",String.class,id);}
    public void nextAttempt(String id) {
        jdbc.update("UPDATE coding_run SET attempt=attempt+1,artifact_hash=NULL,validation_status='NOT_RUN',validation_hash=NULL,error=NULL,answer=NULL WHERE id=?", id);
    }
    public long event(String id, String type, Object payload) {
        var scope=CodingExecutionScope.get();
        if(scope!=null){var node=json.valueToTree(payload);if(node.isObject()){((com.fasterxml.jackson.databind.node.ObjectNode)node)
                .put("executionId",scope.id()).put("agentType",scope.role()).put("attempt",scope.rootAttempt()).put("executionAttempt",scope.attempt());payload=node;}}
        String encoded=encode(payload);
        return tx.execute(status -> {
            jdbc.update("INSERT INTO coding_event(run_id,type,payload,created_at) VALUES(?,?,?,?)", id, type, encoded, System.currentTimeMillis());
            return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        });
    }
    public List<Event> events(String id, long after, int limit) {
        return jdbc.query("SELECT * FROM coding_event WHERE run_id=? AND id>? ORDER BY id LIMIT ?",
                (r,n) -> new Event(r.getLong("id"), r.getString("run_id"), r.getString("type"), r.getString("payload"), r.getLong("created_at")), id, after, Math.min(500, Math.max(1, limit)));
    }
    public long beginOperation(Run run, String callId, String tool, String args) {
        return tx.execute(status -> {
            var scope=CodingExecutionScope.get();
            if(scope==null){jdbc.update("INSERT INTO coding_operation(run_id,attempt,round,call_id,tool,args,status,started_at) VALUES(?,?,(SELECT round FROM coding_run WHERE id=?),?,?,?,'RUNNING',?)",
                    run.id(), run.attempt(),run.id(), callId, tool, args, System.currentTimeMillis());}
            else{jdbc.update("INSERT INTO coding_child_operation(execution_id,attempt,round,call_id,tool,args,status,started_at) VALUES(?,?,(SELECT round FROM coding_execution WHERE id=?),?,?,?,'RUNNING',?)",
                    scope.id(),scope.attempt(),scope.id(),callId,tool,args,System.currentTimeMillis());}
            return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        });
    }
    public void endOperation(long id, String status, String result) {
        var scope=CodingExecutionScope.get();
        if(scope==null){jdbc.update("UPDATE coding_operation SET status=?,result=?,finished_at=? WHERE id=?", status, result, System.currentTimeMillis(), id);}
        else{jdbc.update("UPDATE coding_child_operation SET status=?,result=?,finished_at=? WHERE id=? AND execution_id=?",status,result,System.currentTimeMillis(),id,scope.id());}
    }
    public List<Operation> operations(String id) {
        return jdbc.query("SELECT * FROM coding_operation WHERE run_id=? ORDER BY id DESC LIMIT 200",
                (r,n) -> new Operation(r.getLong("id"), id, r.getInt("attempt"), r.getString("tool"), r.getString("args"),
                        r.getString("status"), r.getString("result"), r.getLong("started_at"), nullableLong(r,"finished_at")), id);
    }
    public boolean unknownOperations(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coding_operation WHERE run_id=? AND status='UNKNOWN'", Integer.class, id) > 0;
    }
    public String resultForCall(String id,String callId) {
        return jdbc.query("SELECT status,result FROM coding_operation WHERE run_id=? AND call_id=? AND attempt=(SELECT checkpoint_attempt FROM coding_run WHERE id=?) AND round=(SELECT round FROM coding_run WHERE id=?) ORDER BY id DESC LIMIT 1",
                (r,n)->switch(r.getString("status")){
                    case "UNKNOWN" -> "{\"status\":\"UNKNOWN\",\"message\":\"上次副作用结果未知，必须先检查当前文件/进程状态，不能盲目重复执行\"}";
                    case "NOT_RETURNED" -> "{\"status\":\"NOT_RETURNED\",\"message\":\"上次只读结果未保存，请重新读取当前状态\"}";
                    default -> r.getString("result")==null?"{\"status\":\"NOT_EXECUTED\"}":r.getString("result");
                },id,callId,id,id).stream().findFirst().orElse("{\"status\":\"NOT_EXECUTED\",\"message\":\"上次在此工具执行前中断\"}");
    }
    public void recover() {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE task SET agent_id=? WHERE origin='coding' AND kind='code' AND agent_id IS NULL",BuiltinAgents.CODING);
            jdbc.update("UPDATE coding_operation SET status='NOT_RETURNED' WHERE status='RUNNING' AND tool IN ('read_file','list_files','search_text','git_status','git_diff','delegate_agent','read_agent','resume_agent','stop_agent')");
            jdbc.update("UPDATE coding_operation SET status='UNKNOWN' WHERE status='RUNNING'");
            var ids = jdbc.queryForList("SELECT id FROM coding_run WHERE status IN ('QUEUED','PREPARING','RUNNING','WAITING_AGENTS','VERIFYING','CANCELLING','ACCEPTING')", String.class);
            for (String id : ids) { phase(id, "INTERRUPTED", "后端重启，执行已中断；恢复前请检查未知操作与进程状态"); }
        });
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("事件编码失败", e); } }
    private static Long nullableLong(ResultSet row, String column) throws SQLException { long value=row.getLong(column); return row.wasNull()?null:value; }
    private static Repository repo(ResultSet r, int n) throws SQLException {
        return new Repository(r.getString("id"),r.getString("owner_id"),r.getString("name"),r.getString("path"),r.getString("project_id"),r.getString("validation_command"),r.getLong("created_at"));
    }
    private static Session sessionRow(ResultSet r,int n) throws SQLException{return new Session(r.getString("id"),r.getString("repository_id"),r.getString("title"),r.getString("last_run_id"),r.getLong("created_at"),r.getLong("updated_at"));}
    private static Run runRow(ResultSet r, int n) throws SQLException {
        return new Run(r.getString("id"),r.getLong("task_id"),r.getString("owner_id"),r.getString("repository_id"),r.getString("prompt"),r.getString("model_id"),Mode.valueOf(r.getString("mode")),Runner.valueOf(r.getString("runner")),r.getString("status"),r.getInt("attempt"),r.getInt("round"),r.getString("answer"),r.getString("error"),r.getString("validation_status"),r.getString("artifact_hash"),r.getLong("created_at"),r.getLong("updated_at"),r.getString("session_id"));
    }
}
