package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.MemoryEvidence;
import cc.ginpika.minis.ai.memory.graph.MemoryGraphStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable extraction outbox. Leases fence late completions; failed jobs remain retryable across restarts. */
@Repository
public class MemoryIngestStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int MATERIAL_CHARS=12000;
    private static final long LEASE_MS=600_000;
    private final JdbcTemplate jdbc;
    private final MemoryGraphStore graph;
    public MemoryIngestStore(JdbcTemplate jdbc,MemoryGraphStore graph) { this.jdbc=jdbc;this.graph=graph; }
    public record Job(String id,String group,String token,MemoryExec exec,String text,String refs,boolean explicit,int attempts) {}

    @Transactional
    public String enqueue(MemoryExec exec,String text,String refs,boolean explicit,long idleMs) {
        if(!cc.ginpika.minis.ai.memory.MemoryResetBoundary.read(jdbc).allows(refs)) return "";
        for (String ref : MemoryEvidence.references(refs)) {
            if (Boolean.TRUE.equals(jdbc.query("""
                    SELECT 1 FROM memory_retracted_source WHERE ref = ?
                    """, rs -> { return rs.next(); }, ref))) {
                return "";
            }
        }
        String group=MemoryGraphStore.digest(MemoryGraphStore.encode(Arrays.asList(exec.userId(),exec.agentOrBuiltin(),exec.projectId(),exec.sessionId(),exec.identityOrInferred())));
        String source="ingest:"+MemoryGraphStore.digest(group+MemoryEvidence.references(refs).stream().sorted().toList()+text);
        String episode=graph.episode(source,text,exec,System.currentTimeMillis());
        String evidence=MemoryEvidence.merge(refs,"[{\"ref\":\"graph_episode:"+episode+"\"}]");
        long now=System.currentTimeMillis();
        for(int offset=0;offset<text.length();) {
            int end=Math.min(text.length(),offset+MATERIAL_CHARS);
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
            String id=MemoryGraphStore.digest(episode+":"+offset);
            String window=graph.episode("window:"+id,text.substring(offset,end),exec,now);
            jdbc.update("INSERT OR IGNORE INTO graph_ingest_job(id,group_key,exec_json,episode_id,source_refs,explicit,state,next_at,created_at) VALUES(?,?,?,?,?,?,'pending',?,?)",
                    id,group,MemoryGraphStore.encode(exec),window,evidence,explicit?1:0,explicit?now:now+Math.max(1000,idleMs),now);
            if(end==text.length()) break;
            offset=Math.max(offset+1,end-200);
        }
        return group;
    }

    public List<String> dueGroups(int batch) {
        long now=System.currentTimeMillis();
        return jdbc.query("SELECT group_key FROM graph_ingest_job WHERE (state='pending' OR (state='running' AND lease_until<=?)) "
                        +"AND (attempts=0 OR next_at<=?) GROUP BY group_key HAVING count(*)>=? OR min(next_at)<=? ORDER BY min(created_at) LIMIT 16",
                (r,n)->r.getString(1),now,now,Math.max(1,batch),now);
    }

    @Transactional
    public List<Job> claim(String group,int limit) {
        long now=System.currentTimeMillis(); String token=UUID.randomUUID().toString();
        var ids=jdbc.query("SELECT id FROM graph_ingest_job WHERE group_key=? AND ((state='pending' AND (attempts=0 OR next_at<=?)) OR (state='running' AND lease_until<=?)) ORDER BY created_at,id LIMIT ?",
                (r,n)->r.getString(1),group,now,now,Math.max(1,Math.min(2,limit)));
        for(String id:ids) jdbc.update("UPDATE graph_ingest_job SET state='running',lease_token=?,lease_until=? WHERE id=? AND (state='pending' OR (state='running' AND lease_until<=?))",
                token,now+LEASE_MS,id,now);
        return jdbc.query("SELECT j.id,j.group_key,j.exec_json,j.source_refs,j.explicit,j.attempts,e.payload FROM graph_ingest_job j JOIN graph_episode e ON e.id=j.episode_id WHERE j.lease_token=? AND j.state='running' ORDER BY j.created_at,j.id",
                (r,n)->new Job(r.getString(1),r.getString(2),token,decode(r.getString(3)),r.getString(7),r.getString(4),r.getInt(5)!=0,r.getInt(6)),token);
    }

    public void complete(List<Job> jobs,boolean ok,String error) {
        for(Job job:jobs) {
            long delay=Math.min(3_600_000L,30_000L*(1L<<Math.min(7,job.attempts())));
            jdbc.update("UPDATE graph_ingest_job SET state=?,attempts=attempts+1,next_at=?,lease_until=NULL,lease_token=NULL,last_error=? WHERE id=? AND lease_token=? AND state='running'",
                    ok?"done":"pending",System.currentTimeMillis()+delay,error,job.id(),job.token());
        }
    }

    @Transactional
    public void rewind(String session,Set<String> refs) {
        var jobs=jdbc.query("SELECT id,exec_json,source_refs FROM graph_ingest_job WHERE state IN ('pending','running')",
                (r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)});
        for(var job:jobs) if(session.equals(decode(job[1]).sessionId()) && MemoryEvidence.references(job[2]).stream().anyMatch(refs::contains)) {
            jdbc.update("UPDATE graph_ingest_job SET state='retracted',lease_token=NULL,lease_until=NULL WHERE id=?",job[0]);
        }
    }
    private static MemoryExec decode(String json) {
        try { return JSON.readValue(json,MemoryExec.class); }
        catch(Exception e) { throw new IllegalStateException("提炼作业的执行作用域损坏",e); }
    }
}
