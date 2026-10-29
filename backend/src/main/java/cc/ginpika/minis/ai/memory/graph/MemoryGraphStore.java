package cc.ginpika.minis.ai.memory.graph;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.store.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Graph relations and source episodes refer to graph_claim: no second copy of a fact's body or status. */
@Repository
@org.springframework.context.annotation.DependsOn("schemaMigrations")
public class MemoryGraphStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public MemoryGraphStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** A name only identifies an entity inside a namespace; namespaces include identity and ownership. */
    public static String namespace(MemoryExec exec) {
        if (exec == null || exec.human()) return "knowledge";
        return "context:" + digest(JSON.valueToTree(List.of(Objects.toString(exec.userId(), ""),
                Objects.toString(exec.projectId(), ""), exec.agentOrBuiltin(),
                Objects.toString(exec.sessionId(), ""), exec.identityOrInferred().name())).toString());
    }

    public static String namespace(MemoryItemStore.ItemRow claim) {
        return switch (claim.owner()) {
            case "KNOWLEDGE" -> "knowledge";
            case "USER" -> "user:" + Objects.toString(claim.userId(), "");
            default -> "claim-domain:" + digest(JSON.valueToTree(Arrays.asList(claim.userId(), claim.owner(),
                    claim.projectId(), claim.agentId(), "L3".equals(claim.layer()) ? claim.sessionId() : null,
                    "L3".equals(claim.layer()) ? claim.taskId() : null, claim.identity())).toString());
        };
    }

    public MemoryItemStore claims() {
        return new MemoryItemStore(jdbc, new MemoryRevisionStore(jdbc), this);
    }

    /** Full source text is immutable per content version. Retraction changes visibility, never rewrites evidence. */
    public String episode(String source, String payload, MemoryExec exec, long observedAt) {
        String text = payload == null ? "" : payload;
        String hash = digest(text), id = digest(source + "\u0000" + hash);
        jdbc.update("INSERT OR IGNORE INTO graph_episode(id,source_ref,content_hash,payload,exec_json,observed_at,status) "
                        + "VALUES(?,?,?,?,?,?,'active')", id, source, hash, text, encode(exec), observedAt);
        if(!text.isEmpty()) {
            jdbc.update("INSERT OR IGNORE INTO graph_support(claim_id,episode_id,stance) SELECT s.claim_id,?,s.stance FROM graph_support s JOIN graph_episode e ON e.id=s.episode_id WHERE e.source_ref=? AND e.payload='' AND e.id<>?",id,source,id);
            jdbc.update("DELETE FROM graph_support WHERE episode_id IN (SELECT id FROM graph_episode WHERE source_ref=? AND payload='' AND id<>?)",source,id);
        }
        return id;
    }

    public void support(long claimId, String episodeId) {
        jdbc.update("INSERT OR IGNORE INTO graph_support(claim_id,episode_id,stance) VALUES(?,?,'supports')", claimId, episodeId);
    }

    public void attachEvidence(long claimId, String refs) {
        if (refs == null || refs.isBlank()) return;
        try {
            JsonNode values = JSON.readTree(refs);
            if (!values.isArray()) throw new IllegalArgumentException("证据引用必须是数组");
            for (JsonNode value : values) {
                String ref = value.isObject() ? value.path("ref").asText("") : value.asText("");
                if (ref.isBlank()) continue;
                if(ref.startsWith("graph_job:")) continue;
                if(ref.startsWith("graph_episode:")) {
                    String episode=ref.substring("graph_episode:".length());
                    if(jdbc.queryForObject("SELECT count(*) FROM graph_episode WHERE id=? AND status='active'",Integer.class,episode)==0) throw new IllegalStateException("来源经历已撤回或不存在");
                    support(claimId,episode); continue;
                }
                List<String> existing = jdbc.query("SELECT id FROM graph_episode WHERE source_ref=? AND status='active' ORDER BY observed_at DESC LIMIT 1",
                        (r,n) -> r.getString(1), ref);
                String text = value.isObject() ? value.path("text").asText("") : "";
                if(existing.isEmpty() && ref.startsWith("chunk:")) {
                    var original=jdbc.query("SELECT content FROM chunks WHERE id=?",(r,n)->r.getString(1),ref.substring(6));
                    if(!original.isEmpty()) text=original.getFirst();
                }
                String id = existing.isEmpty() ? episode(ref, text, MemoryScopeCtx.get(),
                        value.path("observedAt").asLong(System.currentTimeMillis())) : existing.getFirst();
                support(claimId, id);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("证据引用不是合法 JSON", e);
        }
    }

    /** Every memory statement is a node linked to its subject even when no semantic triple was extracted. */
    public void bind(long claimId) {
        var claim = claims().findById(claimId).orElseThrow();
        String ns = namespace(claim);
        String owner = switch (claim.owner()) {
            case "USER" -> "用户";
            case "PROJECT" -> "项目:" + claim.projectId();
            case "KNOWLEDGE" -> "知识库";
            default -> "Agent:" + claim.agentId();
        };
        String subject = entity(ns, owner, "CONTEXT", null, null);
        // The statement node has no duplicate body: readers obtain it from graph_claim.
        String object = entity(ns, "claim:" + claimId, "STATEMENT", null, null);
        relation(claimId, subject, claim.scope(), object, null);
        attachEvidence(claimId, claim.sourceRefs());
        index(claimId);
    }

    public void index(long claimId) {
        jdbc.update("DELETE FROM graph_claim_fts WHERE rowid=?", claimId);
        jdbc.update("INSERT INTO graph_claim_fts(rowid,title,body) SELECT id,title,body FROM graph_claim WHERE id=?", claimId);
    }

    public String entity(String namespace, String name, String type, String aliases, String summary) {
        return entity(namespace,name,name,type,aliases,summary);
    }
    public String entity(String namespace,String identityKey,String name,String type,String aliases,String summary) {
        var found = jdbc.query("SELECT id FROM graph_entity WHERE namespace=? AND identity_key=?", (r,n)->r.getString(1), namespace,identityKey);
        if (!found.isEmpty()) return found.getFirst();
        String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        jdbc.update("INSERT OR IGNORE INTO graph_entity(id,namespace,identity_key,name,type,types,aliases,summary,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id,namespace,identityKey,name,type,"[\""+type+"\"]",aliases,summary,now,now);
        return jdbc.queryForObject("SELECT id FROM graph_entity WHERE namespace=? AND identity_key=?",String.class,namespace,identityKey);
    }

    public void relation(long claim, String subject, String predicate, String object, Long chunk) {
        String id = digest(claim + "\u0000" + subject + "\u0000" + predicate + "\u0000" + object);
        jdbc.update("INSERT OR IGNORE INTO graph_relation(id,claim_id,subject_id,predicate,object_id,source_chunk) VALUES(?,?,?,?,?,?)",
                id,claim,subject,predicate,object,chunk);
    }

    public void entityMetadata(String id,String payload) {
        var existing=jdbc.query("SELECT metadata_claim_id FROM graph_entity WHERE id=? AND metadata_claim_id IS NOT NULL",(r,n)->r.getLong(1),id);
        MemoryExec exec=MemoryScopeCtx.get();
        if(!existing.isEmpty()) {
            var old=claims().findById(existing.getFirst()).orElseThrow();
            if("active".equals(old.status())) {
                if(payload.equals(old.body())) return;
                int changed=claims().updateContentCas(old.id(),old.title(),payload,old.version()==null?0:old.version(),exec==null||exec.human()?"manual":"auto");
                if(changed==0) throw new IllegalStateException("实体属性版本冲突，请重新读取");
                String episode=episode("entity_metadata:"+id,payload,exec,System.currentTimeMillis());
                String refs=exec!=null && exec.taskId()!=null?MemoryEvidence.ref("task",exec.taskId(),null):null;
                claims().attachEvidence(old.id(),MemoryEvidence.merge(refs,"[{\"ref\":\"graph_episode:"+episode+"\"}]"));
                return;
            }
        }
        MemoryItemStore.Ownership own;
        if(exec==null || exec.human()) own=new MemoryItemStore.Ownership(null,"KNOWLEDGE","KNOWLEDGE","L1",null,null,null,null);
        else {
            boolean temporary=exec.sessionId()!=null || exec.taskId()!=null;
            own=new MemoryItemStore.Ownership(exec.userId(),exec.projectId()!=null?"PROJECT":temporary?"TASK":"AGENT",
                    exec.projectId()!=null?"PROJECT":temporary?"TASK_EPISODIC":"AGENT",temporary?"L3":"L2",exec.projectId(),exec.sessionId(),exec.taskId(),exec.agentOrBuiltin());
        }
        String refs=exec!=null && exec.taskId()!=null?MemoryEvidence.ref("task",exec.taskId(),null):null;
        String episode=episode("entity_metadata:"+id,payload,exec,System.currentTimeMillis());
        refs=MemoryEvidence.merge(refs,"[{\"ref\":\"graph_episode:"+episode+"\"}]");
        long claim=claims().insert(own,new MemoryItemStore.Governance(exec==null?null:exec.agentOrBuiltin(),"graph_metadata","inferred","long",exec==null?MemoryIdentity.INFERRED:exec.identityOrInferred()),
                "knowledge",entityName(id),payload,0.7,exec==null||exec.human()?"manual":"auto",null,refs);
        jdbc.update("UPDATE graph_entity SET metadata_claim_id=? WHERE id=?",claim,id);
    }

    public String entityMetadataBody(String id) {
        return entityMetadataBody(id,readExec());
    }
    public String entityMetadataBody(String id,MemoryExec exec) {
        String sql="SELECT body FROM graph_claim WHERE id=(SELECT metadata_claim_id FROM graph_entity WHERE id=?) AND "+MemoryPolicy.visibleSql("graph_claim");
        List<Object> args=new ArrayList<>();args.add(id);args.addAll(MemoryPolicy.visibleArgs(exec));
        var found=jdbc.query(sql,(r,n)->r.getString(1),args.toArray());
        return found.isEmpty()?null:found.getFirst();
    }

    public void triples(long claim, JsonNode triples) {
        if (triples == null || !triples.isArray()) return;
        String ns = namespace(claims().findById(claim).orElseThrow());
        int count = 0;
        for (JsonNode triple : triples) {
            if (count++ >= 12) break;
            String subject=triple.path("subject").asText("").strip(), predicate=triple.path("predicate").asText("").strip(),
                    object=triple.path("object").asText("").strip();
            if (subject.isBlank() || predicate.isBlank() || object.isBlank() || subject.length()>300 || predicate.length()>120 || object.length()>300) continue;
            relation(claim,entity(ns,subject,"CONCEPT",null,null),predicate,entity(ns,object,"CONCEPT",null,null),null);
        }
    }

    /** The exact same visibility predicate controls graph expansion and memory retrieval. */
    public static String visibleClaims(MemoryExec exec) {
        return "SELECT id FROM graph_claim WHERE " + MemoryPolicy.visibleSql("graph_claim");
    }

    public static MemoryExec readExec() {
        MemoryExec exec=MemoryScopeCtx.get();
        return exec == null ? MemoryExec.none() : exec;
    }

    public List<Long> related(MemoryExec exec, Collection<Long> seeds, int limit) {
        if (seeds.isEmpty()) return List.of();
        String marks=String.join(",",Collections.nCopies(seeds.size(),"?"));
        // Scope-filter both the seed graph and target claims before traversing shared entities.
        return jdbc.query("WITH visible AS ("+visibleClaims(exec)+"), nodes AS (SELECT subject_id AS id FROM graph_relation WHERE claim_id IN ("+marks+") AND claim_id IN (SELECT id FROM visible) AND subject_id IN (SELECT id FROM graph_entity WHERE type NOT IN ('CONTEXT','STATEMENT')) "
                        +"UNION SELECT object_id FROM graph_relation WHERE claim_id IN (SELECT id FROM visible) AND claim_id IN ("+marks+") AND object_id IN (SELECT id FROM graph_entity WHERE type NOT IN ('CONTEXT','STATEMENT'))) "
                        +"SELECT DISTINCT claim_id FROM graph_relation WHERE claim_id IN ("+visibleClaims(exec)+") AND "
                        +"(subject_id IN (SELECT id FROM nodes) OR object_id IN (SELECT id FROM nodes)) LIMIT ?",
                (r,n)->r.getLong(1), relatedArgs(exec,seeds,limit));
    }

    private static Object[] relatedArgs(MemoryExec exec, Collection<Long> seeds, int limit) {
        List<Object> args=new ArrayList<>(MemoryPolicy.visibleArgs(exec)); args.addAll(seeds); args.addAll(seeds);
        args.addAll(MemoryPolicy.visibleArgs(exec)); args.add(limit); return args.toArray();
    }

    /** Import one source-backed document relation or one explicitly scoped Agent assertion. */
    @Transactional
    public int writeRelation(String stableId, String subject, String predicate, String object, Long chunk) {
        MemoryExec exec=MemoryScopeCtx.get();
        if (exec!=null && !exec.human() && chunk!=null) throw new IllegalArgumentException("Agent 不能伪造文档来源");
        if (exec!=null && !exec.human()) {
            Integer userAnchor=jdbc.queryForObject("SELECT count(*) FROM graph_entity WHERE id IN (?,?) AND name='用户'",Integer.class,subject,object);
            if (userAnchor!=null && userAnchor>0) throw new IllegalArgumentException("用户画像必须通过记忆候选与审批，禁止直接写用户锚点");
        }
        var old=jdbc.query("SELECT claim_id FROM graph_relation WHERE id=?",(r,n)->r.getLong(1),stableId);
        if (!old.isEmpty()) {
            var existing=claims().findById(old.getFirst()).orElseThrow();
            if (!"active".equals(existing.status()) || existing.supersededBy()!=null) throw new IllegalStateException("该关系已撤销，不能由重复写入重新激活");
            if (chunk!=null) chunkSupport(old.getFirst(),chunk);
            return 0;
        }
        String body=entityName(subject)+" —["+predicate+"]→ "+entityName(object);
        MemoryItemStore.Ownership own;
        if (exec==null || exec.human()) own=new MemoryItemStore.Ownership(null,"KNOWLEDGE","KNOWLEDGE","L1",null,null,null,null);
        else own=new MemoryItemStore.Ownership(exec.userId(),exec.projectId()==null?"TASK":"PROJECT",exec.projectId()==null?"TASK_EPISODIC":"PROJECT","L3",exec.projectId(),exec.sessionId(),exec.taskId(),exec.agentOrBuiltin());
        String ref=chunk!=null?MemoryEvidence.ref("chunk",chunk,null):exec!=null && exec.taskId()!=null?MemoryEvidence.ref("task",exec.taskId(),null):null;
        long claim=claims().insert(own,new MemoryItemStore.Governance(exec==null?null:exec.agentOrBuiltin(),chunk==null?"inferred":"document",chunk==null?"inferred":"explicit","long",exec==null?MemoryIdentity.INFERRED:exec.identityOrInferred()),
                "knowledge",predicate,body,chunk==null?0.6:1.0,exec==null||exec.human()?"manual":"auto",null,ref);
        jdbc.update("INSERT INTO graph_relation(id,claim_id,subject_id,predicate,object_id,source_chunk) VALUES(?,?,?,?,?,?)",stableId,claim,subject,predicate,object,chunk);
        if(chunk!=null) chunkSupport(claim,chunk);
        return 1;
    }

    private String entityName(String id) { return jdbc.queryForObject("SELECT name FROM graph_entity WHERE id=?",String.class,id); }
    private void chunkSupport(long claim,long chunk) {
        var text=jdbc.query("SELECT content FROM chunks WHERE id=?",(r,n)->r.getString(1),chunk);
        if(text.isEmpty()) throw new IllegalArgumentException("来源文档片段不存在");
        support(claim,episode("chunk:"+chunk,text.getFirst(),null,System.currentTimeMillis()));
    }

    /** Project documents keep a versioned catalog; the graph owns their full body and retrieval lifecycle. */
    public long projectDocument(String project,String key,int version,String kind,String title,String body,boolean retired,String author) {
        return MemoryScopeCtx.run(MemoryExec.system(author),()->{
            var previous=jdbc.query("SELECT graph_claim_id FROM project_document WHERE project_id=? AND doc_key=? AND graph_claim_id IS NOT NULL AND version<? ORDER BY version DESC LIMIT 1",(r,n)->r.getLong(1),project,key,version);
            String ref="project_document:"+project+":"+key+":"+version;
            String episode=episode(ref,body,MemoryExec.system(author),System.currentTimeMillis());
            long claim=claims().insert(new MemoryItemStore.Ownership(null,"PROJECT","PROJECT","L1",project,null,null,null),
                    new MemoryItemStore.Governance(author,"project_document","explicit","long",MemoryIdentity.REAL),
                    "decision".equals(kind)?"decision":"knowledge",title,body,1,"manual",null,"[{\"ref\":\"graph_episode:"+episode+"\"}]");
            if(!previous.isEmpty()) claims().supersede(previous.getFirst(),claim);
            if(retired) claims().setStatus(claim,"archived");
            jdbc.update("UPDATE project_document SET graph_claim_id=?,content='' WHERE project_id=? AND doc_key=? AND version=?",claim,project,key,version);
            return claim;
        });
    }

    /** Source removal preserves independently supported claims and revokes unsupported derivatives. */
    public void retractSources(Collection<String> refs) {
        for(String ref:refs) jdbc.update("UPDATE graph_episode SET status='retracted' WHERE source_ref=?",ref);
        jdbc.update("UPDATE graph_claim SET status='revoked',version=COALESCE(version,0)+1,updated_at=? "
                +"WHERE id IN (SELECT claim_id FROM graph_support) AND NOT EXISTS(SELECT 1 FROM graph_support s JOIN graph_episode e ON e.id=s.episode_id WHERE s.claim_id=graph_claim.id AND e.status='active')",System.currentTimeMillis());
    }

    public record Evidence(String id,String sourceRef,String status,String payload,boolean readable,long observedAt) {}
    @Transactional
    public void reviewLegacy(long id,long expectedVersion,boolean accept,String reason) {
        if(reason==null || reason.isBlank() || reason.length()>4000) throw new IllegalArgumentException("复核理由须为 1–4000 字");
        var claim=claims().findById(id).orElseThrow();
        if(!"KNOWLEDGE".equals(claim.owner()) || !"conflicted".equals(claim.status())) throw new IllegalArgumentException("此入口只复核迁移后的待确认知识，用户画像仍须经过画像审批");
        int changed=jdbc.update("UPDATE graph_claim SET status=?,source='manual',source_kind='reviewed_knowledge',evidence_level=?,version=COALESCE(version,0)+1,updated_at=? WHERE id=? AND COALESCE(version,0)=? AND status='conflicted'",
                accept?"active":"revoked",accept?"explicit":"inferred",System.currentTimeMillis(),id,expectedVersion);
        if(changed==0) throw new IllegalStateException("复核版本冲突，请重新读取");
        String episode=episode("human_review:"+id+":"+(expectedVersion+1),"人工复核："+reason+"\n声明："+claim.body(),MemoryExec.system(null),System.currentTimeMillis());
        support(id,episode);
        MemoryScopeCtx.runVoid(MemoryExec.system(null),()->claims().recordControl(id,accept?"confirm":"reject",reason));
    }
    public record EvidencePage(long claimId,String body,int offset,long totalEvidence,List<Evidence> evidence) {}
    public EvidencePage evidence(MemoryExec exec,long claimId,int offset,int limit) {
        var claim=(exec!=null && exec.human()?claims().findById(claimId):claims().historicalById(exec,claimId)).orElseThrow(()->new IllegalArgumentException("事实不存在或无权访问"));
        var rows=jdbc.query("SELECT e.* FROM graph_support s JOIN graph_episode e ON e.id=s.episode_id WHERE s.claim_id=? ORDER BY e.observed_at,e.id LIMIT ? OFFSET ?",(r,n)->{
            String source=r.getString("source_ref"),scopeJson=r.getString("exec_json");
            boolean readable=exec!=null && exec.human();
            if(!readable && (source.startsWith("chunk:") || source.startsWith("project_document:"))) readable=true;
            if(!readable && scopeJson!=null && !"null".equals(scopeJson)) {
                try {
                    var scope=JSON.readValue(scopeJson,MemoryExec.class);
                    readable=exec!=null && (scope.sessionId()!=null && scope.sessionId().equals(exec.sessionId()) || scope.taskId()!=null && scope.taskId().equals(exec.taskId()));
                } catch(Exception e) { throw new IllegalStateException("来源作用域损坏",e); }
            }
            return new Evidence(r.getString("id"),source,r.getString("status"),readable?r.getString("payload"):"",readable,r.getLong("observed_at"));
        },claimId,Math.max(1,Math.min(20,limit)),Math.max(0,offset));
        long total=jdbc.queryForObject("SELECT count(*) FROM graph_support WHERE claim_id=?",Long.class,claimId);
        return new EvidencePage(claimId,claim.body(),Math.max(0,offset),total,rows);
    }

    /** One-time, transactional backfill; missing provenance is retained as conflicted, never promoted to profile. */
    public void migrateLegacy() {
        jdbc.update("INSERT OR IGNORE INTO graph_entity(id,namespace,identity_key,type,types,name,aliases,summary,meta,created_at,updated_at) SELECT id,'knowledge',name,type,types,name,aliases,summary,meta,COALESCE(created_at,0),updated_at FROM entities");
        jdbc.update("INSERT OR IGNORE INTO graph_chunk_entity SELECT * FROM chunk_entity");
        jdbc.update("INSERT OR IGNORE INTO graph_entity_embedding SELECT * FROM entity_embedding");
        for(var old:jdbc.queryForList("SELECT * FROM entities")) {
            String id=old.get("id").toString();
            entityMetadata(id,encode(old));
            jdbc.update("UPDATE graph_claim SET status='conflicted' WHERE id=(SELECT metadata_claim_id FROM graph_entity WHERE id=?)",id);
        }
        var legacy=jdbc.queryForList("SELECT * FROM relations");
        for(var edge:legacy) {
            Long chunk=edge.get("source_chunk") instanceof Number n?n.longValue():null;
            String id=edge.get("id").toString();
            if(jdbc.queryForObject("SELECT count(*) FROM graph_relation WHERE id=?",Integer.class,id)>0) continue;
            writeRelation(id,edge.get("subject_id").toString(),edge.get("predicate").toString(),edge.get("object_id").toString(),chunk);
            if(chunk==null) jdbc.update("UPDATE graph_claim SET status='conflicted',evidence_level='inferred' WHERE id=(SELECT claim_id FROM graph_relation WHERE id=?)",id);
            jdbc.update("UPDATE graph_relation SET props=? WHERE id=?",edge.get("props"),id);
        }
        List<Long> items=jdbc.query("SELECT id FROM graph_claim",(r,n)->r.getLong(1));
        for(long item:items) bind(item);
        var edges=new MemoryEdgeStore(jdbc);
        for(var edge:jdbc.queryForList("SELECT * FROM memory_edge")) {
            Long source=edge.get("source_item") instanceof Number n?n.longValue():null;
            String id=edges.add((String)edge.get("user_id"),(String)edge.get("owner"),(String)edge.get("project_id"),(String)edge.get("agent_id"),edge.get("subject").toString(),edge.get("predicate").toString(),edge.get("object").toString(),((Number)edge.get("confidence")).doubleValue(),edge.get("valid_at") instanceof Number n?n.longValue():null,source);
            jdbc.update("UPDATE graph_claim SET invalid_at=?,expired_at=?,learned_at=?,created_at=?,status=CASE WHEN ? IS NULL THEN 'active' ELSE 'superseded' END WHERE id=(SELECT claim_id FROM graph_relation WHERE id=?)",
                    edge.get("invalid_at"),edge.get("expired_at"),edge.get("learned_at"),edge.get("created_at"),edge.get("invalid_at"),id);
        }
        jdbc.update("DELETE FROM memory_edge");
        for(var doc:jdbc.queryForList("SELECT * FROM project_document WHERE graph_claim_id IS NULL ORDER BY project_id,doc_key,version")) {
            projectDocument(doc.get("project_id").toString(),doc.get("doc_key").toString(),((Number)doc.get("version")).intValue(),doc.get("kind").toString(),doc.get("title").toString(),doc.get("content").toString(),((Number)doc.get("retired")).intValue()!=0,doc.get("author").toString());
        }
        // All rows now have their canonical graph representation; clear legacy foreign-key satellites.
        jdbc.update("DELETE FROM relations"); jdbc.update("DELETE FROM chunk_entity");
        jdbc.update("DELETE FROM entity_embedding"); jdbc.update("DELETE FROM entities");
    }

    public static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch(Exception e) { throw new IllegalArgumentException("图谱对象不能序列化",e); }
    }
}
