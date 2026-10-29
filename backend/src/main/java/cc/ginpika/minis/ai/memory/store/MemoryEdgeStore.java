package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.*;
import cc.ginpika.minis.ai.memory.graph.MemoryGraphStore;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Compatibility API for temporal edges; all facts and their validity now live in graph_claim. */
@Repository
public class MemoryEdgeStore {
    public record EdgeRow(String id,String userId,String owner,String projectId,String agentId,String subject,
                          String predicate,String object,double confidence,Long validAt,Long invalidAt,long learnedAt,
                          Long expiredAt,Long sourceItem,long createdAt) {}
    private final JdbcTemplate jdbc;
    private final MemoryGraphStore graph;
    public MemoryEdgeStore(JdbcTemplate jdbc) { this.jdbc=jdbc;this.graph=new MemoryGraphStore(jdbc); }

    /** Multiple objects coexist. A new object alone is not evidence that an old fact became false. */
    @Transactional
    public String add(String userId,String owner,String project,String agent,String subject,String predicate,String object,
                      double confidence,Long validAt,Long sourceItem) {
        var source=sourceItem==null?null:graph.claims().findById(sourceItem).orElseThrow();
        var own=source==null?new MemoryItemStore.Ownership(userId,owner,
                "USER".equals(owner)?"USER_PROFILE":"PROJECT".equals(owner)?"PROJECT":"AGENT",
                "USER".equals(owner)||"PROJECT".equals(owner)?"L1":"L2",project,null,null,agent)
                :new MemoryItemStore.Ownership(source.userId(),source.owner(),source.domain(),source.layer(),source.projectId(),source.sessionId(),source.taskId(),source.agentId());
        String refs=source==null?null:MemoryEvidence.merge(source.sourceRefs(),"[{\"ref\":\"graph_claim:"+sourceItem+"\"}]");
        long claim=graph.claims().insert(own,new MemoryItemStore.Governance(agent,"inferred","inferred","long",MemoryIdentity.INFERRED),
                "fact",predicate,subject+" —["+predicate+"]→ "+object,confidence,"manual",null,refs);
        if(validAt!=null) jdbc.update("UPDATE graph_claim SET valid_at=? WHERE id=?",validAt,claim);
        String ns=MemoryGraphStore.namespace(graph.claims().findById(claim).orElseThrow());
        String s=graph.entity(ns,subject,"CONCEPT",null,null),o=graph.entity(ns,object,"CONCEPT",null,null);
        graph.relation(claim,s,predicate,o,null);
        String id=MemoryGraphStore.digest(claim+"\u0000"+s+"\u0000"+predicate+"\u0000"+o);
        jdbc.update("UPDATE graph_relation SET source_claim=? WHERE id=?",sourceItem,id);
        return id;
    }

    public List<EdgeRow> activeBySubject(String owner,String project,String agent,String subject) {
        List<Object> args=new ArrayList<>(List.of(owner));args.add(project);args.add(agent);args.add(subject);
        return rows("c.owner=? AND c.project_id IS ? AND c.agent_id IS ? AND s.name=? AND c.invalid_at IS NULL AND c.status='active'",args,1000);
    }
    public List<EdgeRow> activeAll(int limit) { return rows("c.invalid_at IS NULL AND c.status='active'",new ArrayList<>(),limit); }
    public List<EdgeRow> asOf(long at,int limit) { return rows("COALESCE(c.valid_at,c.learned_at)<=? AND (c.invalid_at IS NULL OR c.invalid_at>?)",new ArrayList<>(List.of(at,at)),limit); }
    private List<EdgeRow> rows(String filter,List<Object> args,int limit) {
        args.add(Math.max(1,limit));
        return jdbc.query("SELECT r.id,c.user_id,c.owner,c.project_id,c.agent_id,s.name AS subject,r.predicate,o.name AS object,c.confidence,c.valid_at,c.invalid_at,c.learned_at,c.expired_at,r.source_claim,c.created_at "
                        +"FROM graph_relation r JOIN graph_claim c ON c.id=r.claim_id JOIN graph_entity s ON s.id=r.subject_id JOIN graph_entity o ON o.id=r.object_id "
                        +"WHERE o.type<>'STATEMENT' AND "+filter+" ORDER BY c.learned_at DESC LIMIT ?",(r,n)->new EdgeRow(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getDouble(9),nullable(r,10),nullable(r,11),r.getLong(12),nullable(r,13),nullable(r,14),r.getLong(15)),args.toArray());
    }
    public int invalidateBySourceItem(long item) {
        long now=System.currentTimeMillis();
        return jdbc.update("UPDATE graph_claim SET invalid_at=COALESCE(invalid_at,?),expired_at=?,status='superseded' WHERE id IN (SELECT claim_id FROM graph_relation WHERE source_claim=?) AND invalid_at IS NULL",now,now,item);
    }
    public int clearAll() { return jdbc.update("DELETE FROM graph_relation WHERE source_claim IS NOT NULL"); }
    private static Long nullable(java.sql.ResultSet r,int column) throws java.sql.SQLException {long value=r.getLong(column);return r.wasNull()?null:value;}
}
