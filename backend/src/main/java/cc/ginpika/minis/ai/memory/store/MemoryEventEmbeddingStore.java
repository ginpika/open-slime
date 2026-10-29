package cc.ginpika.minis.ai.memory.store;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * memory_event_embedding（记忆事件向量）的读写：三轴记忆里「向量轴」的存储。
 *
 * <p>写入只有覆盖式 save（同 event_id 重算则整行更新——重建投影，事件本身仍是只追加）；
 * 读侧按当前模型取「时间窗收敛后的候选集」的向量（见 recall 包），不做全表暴力扫
 * （ChunkStore.topByCosine 的教训）。补齐入口见 {@link cc.ginpika.minis.ai.memory.MemoryEmbeddingWriter}。
 */
@Repository
public class MemoryEventEmbeddingStore {

    private final JdbcTemplate jdbc;

    public MemoryEventEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 覆盖式写入一条事件向量（换模型重算时同样走这里，model/vec 一并覆盖） */
    public void save(long eventId, String model, float[] vec) {
        jdbc.update(
                "INSERT INTO memory_event_embedding(event_id, model, dim, vec, updated_at) "
                        + "SELECT id,?,?,?,? FROM memory_event WHERE id=? "
                        + "ON CONFLICT(event_id) DO UPDATE SET model=excluded.model, "
                        + "dim=excluded.dim, vec=excluded.vec, updated_at=excluded.updated_at",
                model, vec.length, MemoryVecMath.encode(vec), System.currentTimeMillis(),eventId);
    }

    /** 指定事件集里当前模型的向量（已解码；缺向量/模型不符的事件不在结果里） */
    public Map<Long, float[]> byEventIds(String model, Collection<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Map.of();
        }
        String in = eventIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, float[]> out = new LinkedHashMap<>();
        jdbc.query("SELECT event_id, dim, vec FROM memory_event_embedding "
                        + "WHERE model = ? AND event_id IN (" + in + ")",
                rs -> {
                    out.put(rs.getLong("event_id"),
                            MemoryVecMath.decode(rs.getBytes("vec"), rs.getInt("dim")));
                },
                model);
        return out;
    }

    /**
     * 缺当前模型向量的事件（无行，或行是旧模型留下的），时间倒序，最多 limit 条：
     * 补齐入口的扫描源。模型切换后旧行不算「已有向量」，会被重算覆盖。
     */
    public List<MemoryEventStore.EventRow> eventsMissing(String model, int limit) {
        return jdbc.query(
                "SELECT e.id, e.event_key, e.kind, e.actor, e.title, e.payload, "
                        + "e.ref_type, e.ref_id, e.created_at "
                        + "FROM memory_event e "
                        + "LEFT JOIN memory_event_embedding v ON v.event_id = e.id AND v.model = ? "
                        + "WHERE v.event_id IS NULL "
                        + "ORDER BY e.created_at DESC, e.id DESC LIMIT ?",
                (rs, rowNum) -> MemoryEventStore.map(rs), model, limit);
    }
}
