package cc.ginpika.minis.coding.store;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class CodingExecutionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CodingStore root;
    public CodingExecutionStore(JdbcTemplate jdbc,TransactionTemplate tx,CodingStore root){this.jdbc=jdbc;this.tx=tx;this.root=root;}
    public Child create(Run run,Role role,String title,String prompt,String modelId){
        return create(run,role,title,prompt,modelId,null,null);
    }
    public Child create(Run run,Role role,String title,String prompt,String modelId,String requestKey,String arguments){
        // Release the lookup connection before starting a SQLite write transaction:
        // upgrading a read transaction while another worker writes can fail with SQLITE_BUSY.
        Child existing=request(run.id(),requestKey,arguments);if(existing!=null){return existing;}
        try{return tx.execute(status->{
            long now=System.currentTimeMillis();String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO task(parent_id,kind,status,title,agent_id,model_id,workspace_id,payload,origin,created_at,updated_at) VALUES(?,'code','pending',?,?,?,?,?,'coding',?,?)",
                    run.taskId(),title,BuiltinAgents.CORE,modelId,run.repositoryId(),run.id(),now,now);
            long task=jdbc.queryForObject("SELECT last_insert_rowid()",Long.class);
            jdbc.update("INSERT INTO coding_execution(id,run_id,task_id,role,title,prompt,model_id,instructions,config_version,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'QUEUED',?,?)",
                    id,run.id(),task,role.id(),title,prompt,modelId,role.instructions(),role.version(),now,now);
            if(requestKey!=null){jdbc.update("INSERT INTO coding_delegation_request VALUES(?,?,?,?)",requestKey,run.id(),id,arguments);}
            root.event(run.id(),"agent.created",Map.of("executionId",id,"agentType",role.id(),"title",title,"prompt",prompt,"attempt",run.attempt()));
            return child(run.id(),id);
        });}catch(org.springframework.dao.DuplicateKeyException error){Child previous=request(run.id(),requestKey,arguments);if(previous!=null){return previous;}throw error;}
    }
    private Child request(String run,String key,String arguments){
        if(key==null){return null;}
        var rows=jdbc.query("SELECT execution_id,arguments FROM coding_delegation_request WHERE request_key=? AND run_id=?",(r,n)->Map.of("id",r.getString(1),"args",r.getString(2)),key,run);
        if(rows.isEmpty()){return null;}
        if(!rows.getFirst().get("args").equals(arguments)){throw new IllegalArgumentException("子任务请求 ID 已用于不同的委派参数");}
        return child(run,rows.getFirst().get("id"));
    }
    public Child child(String run,String id){return jdbc.query("SELECT * FROM coding_execution WHERE run_id=? AND id=?",CodingExecutionStore::row,run,id).stream().findFirst().orElse(null);}
    public List<Child> list(String run){return jdbc.query("SELECT * FROM coding_execution WHERE run_id=? ORDER BY created_at,id",CodingExecutionStore::row,run);}
    public String instructions(String id){return jdbc.queryForObject("SELECT instructions FROM coding_execution WHERE id=?",String.class,id);}
    public String messages(String id){return jdbc.queryForObject("SELECT messages FROM coding_execution WHERE id=?",String.class,id);}
    public void checkpoint(String id,JsonNode messages,int round){jdbc.update("UPDATE coding_execution SET messages=?,round=?,checkpoint_attempt=attempt,updated_at=? WHERE id=?",messages.toString(),round,System.currentTimeMillis(),id);}
    public void hashes(String id,String base,String end){jdbc.update("UPDATE coding_execution SET base_hash=?,final_hash=? WHERE id=?",base,end,id);}
    public void answer(String id,String text){jdbc.update("UPDATE coding_execution SET answer=? WHERE id=?",text,id);}
    public void reported(String id){jdbc.update("UPDATE coding_execution SET reported_at=? WHERE id=?",System.currentTimeMillis(),id);}
    public List<Child> unreported(String run){return jdbc.query("SELECT * FROM coding_execution WHERE run_id=? AND reported_at IS NULL AND status NOT IN ('QUEUED','RUNNING')",CodingExecutionStore::row,run);}
    public void phase(String id,String phase,String error){tx.executeWithoutResult(status->{
        jdbc.update("UPDATE coding_execution SET status=?,error=?,updated_at=? WHERE id=?",phase,error,System.currentTimeMillis(),id);
        String generic=switch(phase){case "QUEUED"->"pending";case "RUNNING"->"running";case "DONE","STALE"->"done";case "CANCELLED"->"cancelled";case "INTERRUPTED"->"interrupted";case "PARTIAL"->"suspended";default->"failed";};
        jdbc.update("UPDATE task SET status=?,error=?,updated_at=? WHERE id=(SELECT task_id FROM coding_execution WHERE id=?)",generic,error,System.currentTimeMillis(),id);
        String run=jdbc.queryForObject("SELECT run_id FROM coding_execution WHERE id=?",String.class,id);
        root.event(run,"agent.phase_changed",Map.of("executionId",id,"status",phase,"error",error==null?"":error));
    });}
    public void nextAttempt(String id){jdbc.update("UPDATE coding_execution SET attempt=attempt+1,answer=NULL,error=NULL,reported_at=NULL WHERE id=?",id);}
    public String resultForCall(String id,String call){return jdbc.query("SELECT result FROM coding_child_operation WHERE execution_id=? AND call_id=? AND attempt=(SELECT checkpoint_attempt FROM coding_execution WHERE id=?) AND round=(SELECT round FROM coding_execution WHERE id=?) ORDER BY id DESC LIMIT 1",
            (r,n)->r.getString(1),id,call,id,id).stream().filter(Objects::nonNull).findFirst().orElse("{\"status\":\"NOT_RETURNED\",\"message\":\"上次只读操作未返回，请重新读取\"}");}
    public void recover(){tx.executeWithoutResult(status->{
        jdbc.update("UPDATE coding_child_operation SET status='NOT_RETURNED' WHERE status='RUNNING'");
        for(String id:jdbc.queryForList("SELECT id FROM coding_execution WHERE status IN ('QUEUED','RUNNING')",String.class)){phase(id,"INTERRUPTED","后端重启，子 Agent 可在继续主会话后恢复");}
    });}
    private static Child row(ResultSet r,int n) throws SQLException{return new Child(r.getString("id"),r.getString("run_id"),r.getString("role"),r.getString("title"),r.getString("prompt"),r.getString("model_id"),r.getString("status"),r.getInt("attempt"),r.getInt("round"),r.getString("answer"),r.getString("error"),r.getString("base_hash"),r.getString("final_hash"),r.getInt("config_version"),r.getLong("created_at"),r.getLong("updated_at"));}
}
