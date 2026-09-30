package cc.ginpika.minis.ai.knowledge.store;

import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.retrieval.VecMath;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** chunks + 双 FTS + chunk_embedding 的读写（M0 幂等语义：按 uuid 整块替换） */
@Repository
public class ChunkStore {

    private final JdbcTemplate jdbc;

    public ChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** docId 为 null 表示散装灌入（不归属受分析管理的文档）；status: unanalyzed|enabled|disabled；
     *  meta 为增强留档 JSON；header/questions 为最终提交预案留档（生成时落库，二次进入回显） */
    public record Row(long id, String uuid, String source, String sourceRef,
                      String title, String content, String tags, String meta,
                      String header, String questions, Long docId,
                      String status, long createdAt) {
    }

    public record EmbedRow(long chunkId, int dim, byte[] bytes) {
    }

    private static final String CHUNK_COLS =
            "id, uuid, source, source_ref, title, content, tags, meta, header, questions, "
                    + "doc_id, status, created_at";

    private Row map(ResultSet rs) throws SQLException {
        long docId = rs.getLong("doc_id");
        boolean noDoc = rs.wasNull(); // 须紧跟 getLong 读取，否则读到的是后续列的空状态
        return new Row(
                rs.getLong("id"),
                rs.getString("uuid"),
                rs.getString("source"),
                rs.getString("source_ref"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("tags"),
                rs.getString("meta"),
                rs.getString("header"),
                rs.getString("questions"),
                noDoc ? null : docId,
                rs.getString("status"),
                rs.getLong("created_at"));
    }

    /** 按 uuid 整体替换（散装灌入：不归属文档，状态未分析），返回新 rowid */
    @Transactional
    public long replace(String uuid, String source, String sourceRef,
                        String title, String content, List<String> tags) {
        return replace(uuid, source, sourceRef, title, content, tags, null, "unanalyzed");
    }

    /** 按 uuid 整体替换（可带父文档与状态），返回新 rowid */
    @Transactional
    public long replace(String uuid, String source, String sourceRef,
                        String title, String content, List<String> tags, Long docId, String status) {
        Long existed = jdbc.query(
                "SELECT id FROM chunks WHERE uuid = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                uuid);
        if (existed != null) {
            delete(existed);
        }
        long now = System.currentTimeMillis();
        String tagsJson = jsonArray(tags);
        long id = jdbc.queryForObject(
                "INSERT INTO chunks(uuid, source, source_ref, title, content, tags, doc_id, status, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                uuid, source, sourceRef, title, content, tagsJson, docId, status, now);
        jdbc.update("INSERT INTO chunks_fts_meta(rowid, title, tags, content) VALUES(?,?,?,?)",
                id, title, tagsJson, content);
        jdbc.update("INSERT INTO chunks_fts_cn(rowid, content) VALUES(?,?)", id, content);
        return id;
    }

    @Transactional
    public void delete(long id) {
        jdbc.update("DELETE FROM chunks_fts_meta WHERE rowid = ?", id);
        jdbc.update("DELETE FROM chunks_fts_cn WHERE rowid = ?", id);
        jdbc.update("DELETE FROM chunk_embedding WHERE chunk_id = ?", id);
        jdbc.update("DELETE FROM chunk_entity WHERE chunk_id = ?", id);
        jdbc.update("DELETE FROM chunk_extraction WHERE chunk_id = ?", id);
        jdbc.update("DELETE FROM chunks WHERE id = ?", id);
    }

    /** 清空全部 chunk 及其索引与向量（重置知识库用）：先清双 FTS 与各子表，再删 chunks 主表；
     *  返回删除的 chunk 条数。chunk_entity 亦在此清（外键指向 chunks，须先于主表）。 */
    @Transactional
    public int clearAll() {
        jdbc.update("DELETE FROM chunks_fts_meta");
        jdbc.update("DELETE FROM chunks_fts_cn");
        jdbc.update("DELETE FROM chunk_embedding");
        jdbc.update("DELETE FROM chunk_entity");
        jdbc.update("DELETE FROM chunk_extraction");
        return jdbc.update("DELETE FROM chunks");
    }

    public void saveEmbedding(long chunkId, String model, float[] vec) {
        jdbc.update(
                "INSERT INTO chunk_embedding(chunk_id, model, dim, vec, updated_at) VALUES(?,?,?,?,?) "
                        + "ON CONFLICT(chunk_id) DO UPDATE SET model=excluded.model, dim=excluded.dim, "
                        + "vec=excluded.vec, updated_at=excluded.updated_at",
                chunkId, model, vec.length, VecMath.encode(vec), System.currentTimeMillis());
    }

    public List<EmbedRow> embeddingsByModel(String model) {
        return jdbc.query(
                "SELECT chunk_id, dim, vec FROM chunk_embedding WHERE model = ?",
                (rs, i) -> new EmbedRow(rs.getLong("chunk_id"), rs.getInt("dim"), rs.getBytes("vec")),
                model);
    }

    /** 同模型余弦 top-k（M0 暴力扫描） */
    public List<Long> topByCosine(String model, float[] qvec, int limit) {
        List<EmbedRow> rows = embeddingsByModel(model);
        double[][] scored = new double[rows.size()][2];
        int n = 0;
        for (EmbedRow r : rows) {
            float sim = VecMath.cosine(qvec, VecMath.decode(r.bytes(), r.dim()));
            if (sim > 0) {
                scored[n][0] = r.chunkId();
                scored[n][1] = sim;
                n++;
            }
        }
        java.util.Arrays.sort(scored, 0, n,
                (a, b) -> Double.compare(b[1], a[1]));
        List<Long> out = new ArrayList<>(Math.min(limit, n));
        for (int i = 0; i < n && out.size() < limit; i++) {
            out.add((long) scored[i][0]);
        }
        return out;
    }

    /** chunks_fts_meta（unicode61）：token 全部命中即收录（OR 组合），BM25 排序 */
    public List<Long> metaMatch(List<String> tokens, int limit) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        String match = tokens.stream()
                .map(ChunkStore::ftsPhrase)
                .collect(Collectors.joining(" OR "));
        return rank("chunks_fts_meta", match, limit);
    }

    /** chunks_fts_cn（trigram）：多短语 AND 组合 */
    public List<Long> cnMatch(List<String> phrases, int limit) {
        if (phrases.isEmpty()) {
            return List.of();
        }
        String match = phrases.stream()
                .map(ChunkStore::ftsPhrase)
                .collect(Collectors.joining(" AND "));
        return rank("chunks_fts_cn", match, limit);
    }

    private List<Long> rank(String table, String match, int limit) {
        return jdbc.query(
                "SELECT rowid FROM " + table + " WHERE " + table + " MATCH ? "
                        + "ORDER BY bm25(" + table + ") LIMIT ?",
                (rs, i) -> rs.getLong(1),
                match, limit);
    }

    private static String ftsPhrase(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** 从候选 id 中剔除已禁用（status=disabled）的块，保持原顺序（检索侧跳过人工禁用的块） */
    public List<Long> excludeDisabled(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Set<Long> disabled = new HashSet<>(jdbc.query(
                "SELECT id FROM chunks WHERE status = ? AND id IN (" + in + ")",
                (rs, i) -> rs.getLong(1),
                DocumentStore.STATUS_DISABLED));
        if (disabled.isEmpty()) {
            return new ArrayList<>(ids);
        }
        List<Long> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (!disabled.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 按给定顺序返回块（缺失的跳过） */
    public List<Row> rowsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Row> byId = new LinkedHashMap<>();
        jdbc.query("SELECT " + CHUNK_COLS + " FROM chunks WHERE id IN (" + in + ")",
                (rs, i) -> map(rs)).forEach(r -> byId.put(r.id(), r));
        List<Row> ordered = new ArrayList<>();
        for (Long id : ids) {
            Row row = byId.get(id);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    /** 文档下的全部 chunk（id 升序；父子关系 doc→chunk 查询侧） */
    public List<Row> rowsByDoc(long docId) {
        return jdbc.query(
                "SELECT " + CHUNK_COLS + " FROM chunks WHERE doc_id = ? ORDER BY id",
                (rs, i) -> map(rs),
                docId);
    }

    /** 更新 chunk 分析状态；返回受影响行数（0 = 不存在） */
    public int updateStatus(long id, String status) {
        return jdbc.update("UPDATE chunks SET status = ? WHERE id = ?", status, id);
    }

    /** 覆盖式更新最终提交预案留档（Chunk Header / 假设性问题，null = 清空）；返回受影响行数（0 = 不存在） */
    public int updatePlan(long id, String header, String questions) {
        return jdbc.update("UPDATE chunks SET header = ?, questions = ? WHERE id = ?",
                header, questions, id);
    }

    private static String jsonArray(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return "[" + tags.stream()
                .map(t -> "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "]";
    }
}
