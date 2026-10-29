package cc.ginpika.minis.agent.roundtable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Optional task contracts and durable review/diagnosis evidence, scoped by the existing task board. */
@Repository
public class CollaborationQuality {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CollaborationQuality(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public record Quality(JsonNode contract,JsonNode findings,JsonNode diagnosis) {}
    public Quality find(String session,String todo){
        Integer count=jdbc.queryForObject("SELECT count(*) FROM roundtable_todo WHERE id=? AND session_id=?",Integer.class,todo,session);
        if(count==null || count!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"当前会话中没有这项工作");
        return find(todo);
    }
    public Quality find(String todo){
        return jdbc.query("SELECT contract,findings,diagnosis FROM collaboration_task_quality WHERE todo_id=?",
                (r,n)->new Quality(read(r.getString(1)),read(r.getString(2)),read(r.getString(3))),todo).stream()
                .findFirst().orElse(new Quality(json.nullNode(),json.createArrayNode(),json.nullNode()));
    }
    private JsonNode read(String value){try{return value==null?json.nullNode():json.readTree(value);}catch(Exception error){throw new IllegalStateException("任务质量记录不可读",error);}}
    public static void validateContract(JsonNode contract){
        if(contract==null || contract.isNull() || contract.isMissingNode())return;
        if(!contract.isObject())throw new IllegalArgumentException("contract 必须是对象");
        for(String field:Set.of("outcome","stopCondition"))
            if(!contract.path(field).isTextual() || contract.path(field).asText().isBlank())throw new IllegalArgumentException("contract."+field+" 不能为空");
        var criteria=contract.path("acceptanceCriteria");
        if(!criteria.isArray() || criteria.isEmpty())throw new IllegalArgumentException("contract.acceptanceCriteria 至少列出一项验收标准");
        Set<String> ids=new HashSet<>();
        for(JsonNode c:criteria)if(!c.path("id").isTextual() || !c.path("text").isTextual() || c.path("id").asText().isBlank() || c.path("text").asText().isBlank() || !ids.add(c.path("id").asText()))
            throw new IllegalArgumentException("验收标准需要唯一 id 和具体 text");
    }
    public static void validateFindings(JsonNode findings,boolean accept){
        if(findings==null || findings.isMissingNode() || findings.isNull())return;
        if(!findings.isArray())throw new IllegalArgumentException("findings 必须是数组");
        Set<String> ids=new HashSet<>();
        for(JsonNode f:findings){
            f.fieldNames().forEachRemaining(key->{if(!Set.of("id","severity","location","expected","basis").contains(key))throw new IllegalArgumentException("检查意见不接受系统记录字段："+key);});
            for(String key:Set.of("id","location","expected","basis"))if(!f.path(key).isTextual() || f.path(key).asText().isBlank())
                throw new IllegalArgumentException("每条检查意见需要 id、location、expected 和 basis（验收依据或具体正确性风险）");
            if(!ids.add(f.path("id").asText()) || !Set.of("BLOCKER","MINOR").contains(f.path("severity").asText()))
                throw new IllegalArgumentException("检查意见 id 必须唯一，severity 使用 BLOCKER/MINOR");
            if(accept && "BLOCKER".equals(f.path("severity").asText()))throw new IllegalArgumentException("仍有阻塞问题时不能接受成果");
        }
        if(!accept && !findings.isEmpty() && !java.util.stream.StreamSupport.stream(findings.spliterator(),false)
                .anyMatch(f->"BLOCKER".equals(f.path("severity").asText())))throw new IllegalArgumentException("仅有一般建议时应通过并记录建议；拒绝需要具体阻塞问题");
    }
    private void save(String todo,String field,JsonNode value){
        if(!Set.of("contract","findings","diagnosis").contains(field))throw new IllegalArgumentException();
        jdbc.update("INSERT INTO collaboration_task_quality(todo_id,"+field+",updated_at) VALUES(?,?,?) ON CONFLICT(todo_id) DO UPDATE SET "+field+"=excluded."+field+",updated_at=excluded.updated_at",
                todo,value==null?null:value.toString(),System.currentTimeMillis());
    }
    public void contract(String todo,JsonNode contract){validateContract(contract);save(todo,"contract",contract);}
    public boolean repeatsBlockers(String todo,JsonNode findings,String artifact){
        if(artifact==null || artifact.isBlank() || findings==null || !findings.isArray())return false;
        var previous=find(todo).findings();
        var old=new java.util.TreeSet<String>();var next=new java.util.TreeSet<String>();
        if(previous.isArray())for(JsonNode f:previous)if("OPEN".equals(f.path("status").asText()) && "BLOCKER".equals(f.path("severity").asText()) && artifact.equals(f.path("artifact").asText()))old.add(signature(f));
        for(JsonNode f:findings)if("BLOCKER".equals(f.path("severity").asText()))next.add(signature(f));
        return !next.isEmpty() && old.equals(next);
    }
    private String signature(JsonNode finding){
        var signature=json.createArrayNode();for(String key:new String[]{"id","location","expected","basis"})signature.add(finding.path(key));return signature.toString();
    }
    public void review(String todo,JsonNode findings,boolean accept,int version,String evidence,String artifact){
        validateFindings(findings,accept);
        var merged=new java.util.LinkedHashMap<String,com.fasterxml.jackson.databind.node.ObjectNode>();
        var previous=find(todo).findings();
        if(previous.isArray())for(JsonNode f:previous){
            var copy=(com.fasterxml.jackson.databind.node.ObjectNode)f.deepCopy();
            if(accept && "OPEN".equals(copy.path("status").asText()))copy.put("status","RESOLVED").put("resolutionEvidence",evidence).put("resolvedVersion",version);
            merged.put(copy.path("id").asText(),copy);
        }
        if(findings!=null && findings.isArray())for(JsonNode f:findings){
            var copy=(com.fasterxml.jackson.databind.node.ObjectNode)f.deepCopy();
            var old=merged.get(copy.path("id").asText());
            if(old!=null){var history=old.path("history").isArray()?old.withArray("history").deepCopy():json.createArrayNode();var snapshot=old.deepCopy();snapshot.remove("history");history.add(snapshot);copy.set("history",history);}
            copy.put("status",accept || "MINOR".equals(f.path("severity").asText())?"ADVISORY":"OPEN").put("reviewVersion",version).put("artifact",artifact);
            merged.put(copy.path("id").asText(),copy);
        }
        var saved=json.createArrayNode();merged.values().forEach(saved::add);
        save(todo,"findings",saved);
        if(accept)closeDiagnosis(todo,"RESOLVED",evidence);
    }
    public void diagnose(String todo,String code,String detail,String run,String hash){
        save(todo,"diagnosis",json.createObjectNode().put("code",code).put("detail",detail).put("runId",run).put("artifactHash",hash).put("state","OPEN").put("createdAt",System.currentTimeMillis()));
    }
    public void reassigned(String todo,String decision){closeDiagnosis(todo,"REASSIGNED",decision);}
    private void closeDiagnosis(String todo,String state,String decision){
        var diagnosis=find(todo).diagnosis();
        if(diagnosis.isObject()){
            var updated=(com.fasterxml.jackson.databind.node.ObjectNode)diagnosis.deepCopy();
            updated.put("state",state).put("decision",decision).put("updatedAt",System.currentTimeMillis());save(todo,"diagnosis",updated);
        }
    }
    public String context(String todo){var q=find(todo);return "\n<task_contract>"+q.contract()+"</task_contract>\n<review_findings>"+q.findings()+"</review_findings>\n<diagnosis>"+q.diagnosis()+"</diagnosis>\n";}
}
