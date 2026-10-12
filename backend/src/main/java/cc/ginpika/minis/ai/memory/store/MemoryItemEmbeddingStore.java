package cc.ginpika.minis.ai.memory.store;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * memory_item_embedding（记忆条目向量）的读写：记忆体系 v2 的「条目向量轴」。
 *
 * <p>与 {@link MemoryEventEmbeddingStore} 同构（列结构一致、编码沿用 {@link MemoryVecMath}）：
 * 写入只有覆盖式 save（换模型重算整行覆盖），读侧按当前模型取指定条目集的向量。
 * 补齐入口见 {@link cc.ginpika.minis.ai.memory.MemoryItemEmbeddingWriter}。
 */
@Repository
public class MemoryItemEmbeddingStore {

    private final JdbcTemplate jdbc;

    public MemoryItemEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 覆盖式写入一条条目向量（换模型重算同样走这里，model/vec 一并覆盖） */
    public void save(long itemId, String model, float[] vec) {
        jdbc.update(
                "INSERT INTO memory_item_embedding(item_id, model, dim, vec, updated_at) "
                        + "VALUES(?,?,?,?,?) "
                        + "ON CONFLICT(item_id) DO UPDATE SET model=excluded.model, "
                        + "dim=excluded.dim, vec=excluded.vec, updated_at=excluded.updated_at",
                itemId, model, vec.length, MemoryVecMath.encode(vec), System.currentTimeMillis());
    }

    public boolean saveVersion(long itemId, String model, float[] vec, long version) {
        return jdbc.update("INSERT INTO memory_item_embedding(item_id, model, dim, vec, updated_at, item_version) "
                + "SELECT id, ?, ?, ?, ?, ? FROM memory_item WHERE id = ? AND COALESCE(version,0) = ? "
                + "ON CONFLICT(item_id) DO UPDATE SET model=excluded.model, dim=excluded.dim, "
                + "vec=excluded.vec, updated_at=excluded.updated_at, item_version=excluded.item_version",
                model, vec.length, MemoryVecMath.encode(vec), System.currentTimeMillis(), version,
                itemId, version) > 0;
    }

    /** 指定条目集里当前模型的向量（已解码；缺向量 / 模型不符的条目不在结果里） */
    public Map<Long, float[]> byItemIds(String model, Collection<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        String in = itemIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, float[]> out = new LinkedHashMap<>();
        jdbc.query("SELECT item_id, dim, vec FROM memory_item_embedding "
                        + "WHERE model = ? AND item_id IN (" + in + ") "
                        + "AND COALESCE(item_version,0) = (SELECT COALESCE(version,0) FROM memory_item i WHERE i.id = item_id)",
                rs -> {
                    out.put(rs.getLong("item_id"),
                            MemoryVecMath.decode(rs.getBytes("vec"), rs.getInt("dim")));
                },
                model);
        return out;
    }

    /**
     * 缺当前模型向量的条目（无行，或行是旧模型留下的），新建在前，最多 limit 条：
     * 补齐入口的扫描源。模型切换后旧行不算「已有向量」，会被重算覆盖。
     */
    public List<MemoryItemStore.ItemRow> itemsMissing(String model, int limit) {
        return jdbc.query(
                "SELECT e.* FROM memory_item e "
                        + "LEFT JOIN memory_item_embedding v ON v.item_id = e.id AND v.model = ? "
                        + "AND COALESCE(v.item_version,0) = COALESCE(e.version,0) "
                        + "WHERE v.item_id IS NULL AND e.status = 'active' ORDER BY e.id LIMIT ?",
                (rs, rowNum) -> MemoryItemStore.map(rs), model, limit);
    }
}
