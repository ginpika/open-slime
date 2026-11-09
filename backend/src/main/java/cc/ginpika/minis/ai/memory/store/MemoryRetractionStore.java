package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.memory.MemoryEvidence;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 来源撤回与派生记忆失效；不写内容指纹墓碑，重新发送仍可学习。 */
@Repository
public class MemoryRetractionStore {
    private final JdbcTemplate jdbc;
    private final MemoryItemStore items;
    private final MemoryCandidateStore candidates;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.ai.memory.graph.MemoryGraphStore graph;

    public MemoryRetractionStore(JdbcTemplate jdbc, MemoryItemStore items, MemoryCandidateStore candidates) {
        this.jdbc = jdbc;
        this.items = items;
        this.candidates = candidates;
    }

    public boolean current(String refs) {
        if(!cc.ginpika.minis.ai.memory.MemoryResetBoundary.read(jdbc).allows(refs)) return false;
        for (String ref : MemoryEvidence.references(refs)) {
            if(ref.startsWith("graph_job:")) {
                String[] parts=ref.split(":",3);
                if(parts.length!=3 || jdbc.queryForObject("SELECT count(*) FROM graph_ingest_job WHERE id=? AND lease_token=? AND state='running' AND lease_until>?",Integer.class,parts[1],parts[2],System.currentTimeMillis())==0) return false;
                continue;
            }
            // A late distillation cannot revive a source that a reset has physically removed.
            for(var source:java.util.Map.of("graph_episode:","graph_episode","memory_work:","memory_work",
                    "memory_event:","memory_event","graph_claim:","graph_claim","chunk:","chunks").entrySet()) {
                if(ref.startsWith(source.getKey())) {
                    String sql="SELECT count(*) FROM "+source.getValue()+" WHERE id=?"
                            +(source.getValue().equals("graph_episode")?" AND status='active'":"");
                    if(jdbc.queryForObject(sql,Integer.class,ref.substring(source.getKey().length()))==0) return false;
                    break;
                }
            }
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_retracted_source WHERE ref = ?",
                    Integer.class, ref);
            if (n != null && n > 0) { return false; }
        }
        return true;
    }

    /** 校验与写入同一事务：回退与异步提炼竞争时不能在校验后偷偷写回旧材料。 */
    @Transactional
    public boolean writeIfCurrent(String refs, BooleanSupplier write) {
        return current(refs) && write.getAsBoolean();
    }

    public long generation() { return cc.ginpika.minis.ai.memory.MemoryResetBoundary.read(jdbc).generation(); }

    @Transactional
    public boolean writeIfCurrent(long expectedGeneration,String refs,BooleanSupplier write) {
        return generation()==expectedGeneration && current(refs) && write.getAsBoolean();
    }

    @Transactional
    public void retractChat(String sessionId, List<Long> messageIds, List<Long> taskIds,
                            List<String> turnIds, long fromTime) {
        Set<String> refs = new HashSet<>();
        Set<Long> tasks = new HashSet<>(taskIds);
        Set<Long> modified = new HashSet<>();
        // update_memory 可改写更早创建的条目，创建时间和原始来源不足以判断当前正文的来路。
        for (String turn : turnIds) {
            modified.addAll(jdbc.query("SELECT DISTINCT item_id FROM memory_revision WHERE turn_id=? "
                            + "AND actor_kind='AGENT' AND op IN ('create','update') AND item_id IS NOT NULL",
                    (rs, n) -> rs.getLong(1), turn));
        }
        messageIds.forEach(id -> refs.add("chat_message:" + id));
        taskIds.forEach(id -> refs.add("task:" + id));
        // 先取得事件 ID，旧版条目可能引用事件而非直接引用消息。
        var events = jdbc.query("SELECT id, ref_type, ref_id FROM memory_event",
                (rs, n) -> new Source(rs.getLong(1), rs.getString(2) + ":" + rs.getLong(3)));
        for (Source event : events) {
            if (refs.contains(event.ref())) { refs.add("memory_event:" + event.id()); }
        }
        var works = jdbc.query("SELECT id, task_id, ref_type, ref_id, created_at FROM memory_work WHERE session_id = ?",
                (rs, n) -> new Work(rs.getLong(1), rs.getLong(2),
                        rs.getString(3) + ":" + rs.getLong(4), rs.getLong(5)), sessionId);
        for (Work work : works) {
            if (tasks.contains(work.task()) || refs.contains(work.ref())
                    || (work.task() == 0 && work.createdAt() >= fromTime)) {
                refs.add("memory_work:" + work.id());
                jdbc.update("UPDATE memory_work SET status='archived', updated_at=? WHERE id=?",
                        System.currentTimeMillis(), work.id());
            }
        }
        // 混合多轮产出的摘要不能靠删掉一个引用还原正文，整条失效，避免保留被丢弃的结论。
        var allItems = items.listAll();
        Set<Long> revoked = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (var item : allItems) {
                if (revoked.contains(item.id())) { continue; }
                boolean legacy = sessionId.equals(item.sessionId()) && item.createdAt() >= fromTime
                        && MemoryEvidence.references(item.sourceRefs()).isEmpty() && !"manual".equals(item.source());
                if (legacy || modified.contains(item.id()) || tasks.contains(item.taskId()) || intersects(item.sourceRefs(), refs)) {
                    items.setStatus(item.id(), "revoked");
                    jdbc.update("UPDATE memory_share SET status='revoked', revoked_at=? WHERE item_id=?",
                            System.currentTimeMillis(), item.id());
                    jdbc.update("UPDATE memory_edge SET invalid_at=?, expired_at=? WHERE source_item=? AND invalid_at IS NULL",
                            System.currentTimeMillis(), System.currentTimeMillis(), item.id());
                    revoked.add(item.id());
                    refs.add("graph_claim:" + item.id());
                    changed = true;
                }
            }
        } while (changed);
        for (var candidate : candidates.listByStatus(null)) {
            if (intersects(candidate.evidenceRefs(), refs)) {
                jdbc.update("UPDATE memory_candidate SET status='rejected', updated_at=? WHERE id=?",
                        System.currentTimeMillis(), candidate.id());
            }
        }
        for (String ref : refs) {
            jdbc.update("INSERT OR IGNORE INTO memory_retracted_source(ref, retracted_at) VALUES(?,?)",
                    ref, System.currentTimeMillis());
        }
        // Delegated extraction can be linked only by a task reference. Fence queued and leased jobs here.
        var jobs = jdbc.query("SELECT id, source_refs FROM graph_ingest_job WHERE state IN ('pending','running')",
                (rs, n) -> new IngestSource(rs.getString(1), rs.getString(2)));
        for (IngestSource job : jobs) {
            if (intersects(job.refs(), refs)) {
                jdbc.update("UPDATE graph_ingest_job SET state='retracted', lease_token=NULL, lease_until=NULL WHERE id=?",
                        job.id());
            }
        }
        if(graph!=null) graph.retractSources(refs);
        for (Source event : events) {
            if (refs.contains("memory_event:" + event.id())) {
                jdbc.update("DELETE FROM memory_event_embedding WHERE event_id=?", event.id());
                jdbc.update("DELETE FROM memory_event WHERE id=?", event.id());
            }
        }
    }

    private static boolean intersects(String evidence, Set<String> refs) {
        return MemoryEvidence.references(evidence).stream().anyMatch(refs::contains);
    }

    private record Source(long id, String ref) { }
    private record Work(long id, long task, String ref, long createdAt) { }
    private record IngestSource(String id, String refs) { }
}
