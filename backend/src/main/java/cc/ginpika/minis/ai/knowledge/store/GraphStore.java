package cc.ginpika.minis.ai.knowledge.store;

import cc.ginpika.minis.ai.knowledge.retrieval.VecMath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 图：entities / relations / chunk_entity 读写 + BFS 邻居 */
@Repository
public class GraphStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public GraphStore(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    /** KV 附加属性：v 统一存字符串，t 决定前端渲染方式（text|number|url|date|bool） */
    public record Prop(String k, String v, String t) {
    }

    /** type 为主类型（首写定不覆盖）；types 为全量类型清单（含主类型，去重合并，兼容多类型）；
     *  createdAt/updatedAt 为行级时间戳（毫秒 epoch），updated_at 触及性写入也会刷新 */
    public record Entity(String id, String type, List<String> types, String name, String aliases,
            String summary, List<Prop> props, Long createdAt, Long updatedAt) {
    }

    /** sourceChunk：产出该关系的 chunk（relations.source_chunk，可空——溯源渲染用） */
    public record Edge(String id, String subjectId, String predicate, String objectId,
            List<Prop> props, Long sourceChunk) {
    }

    /** 稳定 id：名字的 SHA-256 前 16 位十六进制 */
    public static String entityIdFor(String name) {
        return digest(name);
    }

    /** 关系稳定 id：subject \0 predicate \0 object 的摘要（导入去重计数复用） */
    public static String relationIdFor(String subjectId, String predicate, String objectId) {
        return digest(subjectId + "\u0000" + predicate + "\u0000" + objectId);
    }

    private static String digest(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 实体 upsert：主 type 首写定（冲突不覆盖）；types 并集去重（含主类型）；
     *  aliases/summary 维持既有覆盖语义。时间戳按存在性判定——新建行 created_at/updated_at
     *  同落库；既有行仅刷新 updated_at（created_at 不在冲突更新列，恒不被覆盖）。 */
    public void upsertEntity(String type, String name, List<String> aliases, String summary) {
        upsertEntity(type, List.of(), name, aliases, summary);
    }

    /** 同上，extraTypes 为额外类型（一次提交多类型实体，如 PERSON+CHARACTER） */
    public void upsertEntity(String type, List<String> extraTypes, String name, List<String> aliases,
            String summary) {
        String id = entityIdFor(name);
        String aliasJson = jsonArray(cleanAliases(name, aliases));
        Entity exist = byName(name);
        List<String> incoming = normalizeTypes(type, extraTypes);
        List<String> all = exist == null ? incoming : mergeTypes(exist, incoming);
        String primary = exist == null ? incoming.get(0) : exist.type();
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO entities(id, type, types, name, aliases, summary, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(name) DO UPDATE SET types=excluded.types, aliases=excluded.aliases, "
                        + "summary=excluded.summary, updated_at=excluded.updated_at",
                id, primary, jsonArray(all), name, aliasJson, summary, now, now);
    }

    /** 既有实体并入额外类型（主 type/aliases/summary 不动）；实体不存在则忽略。
     *  供同一实体在不同文档被识别为不同类型时累积。 */
    public void mergeEntityTypes(String name, List<String> extraTypes) {
        if (extraTypes == null || extraTypes.isEmpty()) {
            return;
        }
        Entity exist = byName(name);
        if (exist == null) {
            return;
        }
        jdbc.update("UPDATE entities SET types = ?, updated_at = ? WHERE id = ?",
                jsonArray(mergeTypes(exist, extraTypes)), System.currentTimeMillis(), exist.id());
    }

    /** 既有实体并入别名（与已有别名取并集去重；实体不存在则忽略）。
     *  供同一实体在不同文档被识别出别名时累积，避免 upsert 的覆盖语义丢别名。 */
    public void mergeEntityAliases(String name, List<String> aliases) {
        List<String> add = cleanAliases(name, aliases);
        if (add.isEmpty()) {
            return;
        }
        Entity exist = byName(name);
        if (exist == null) {
            return;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(parseStringList(exist.aliases()));
        if (!set.addAll(add)) {
            return;
        }
        jdbc.update("UPDATE entities SET aliases = ?, updated_at = ? WHERE id = ?",
                jsonArray(List.copyOf(set)), System.currentTimeMillis(), exist.id());
    }

    public Entity byName(String name) {
        return jdbc.query("SELECT " + ENTITY_COLS + " FROM entities WHERE name = ?",
                rs -> rs.next() ? mapEntity(rs) : null,
                name);
    }

    /** 关键词模糊查找（name/aliases/meta LIKE）。meta 一并匹配，是为了让历史数据里
     *  写成 props 的别名（别名/通称/称号等）与属性值也能被检索到。
     *  按「名字更短 = 更接近检索词」排序：避免命中按插入序截断，把真正想要的实体挤掉。 */
    public List<Entity> lookup(List<String> terms, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        StringBuilder where = new StringBuilder();
        List<String> params = new ArrayList<>();
        for (String term : terms) {
            String like = "%" + term + "%";
            if (where.length() > 0) {
                where.append(" OR ");
            }
            where.append("name LIKE ? OR aliases LIKE ? OR meta LIKE ?");
            params.add(like);
            params.add(like);
            params.add(like);
        }
        params.add(String.valueOf(limit));
        return jdbc.query(
                "SELECT " + ENTITY_COLS + " FROM entities WHERE " + where
                        + " ORDER BY LENGTH(name), name LIMIT ?",
                (rs, i) -> mapEntity(rs),
                params.toArray());
    }

    /** 按关系度数排序的中心实体（图谱页默认入口建议） */
    public List<Entity> hubs(int limit) {
        return jdbc.query(
                "SELECT e.id, e.type, e.types, e.name, e.aliases, e.summary, e.meta, "
                        + "e.created_at, e.updated_at, "
                        + "(SELECT COUNT(*) FROM relations r WHERE r.subject_id = e.id OR r.object_id = e.id) AS deg "
                        + "FROM entities e ORDER BY deg DESC, e.name LIMIT ?",
                (rs, i) -> mapEntity(rs), limit);
    }

    /** 按类型标记筛选实体（types 全量类型清单含该值即命中，如锚点 ANCHOR）；按名称排序 */
    public List<Entity> entitiesByType(String type, int limit) {
        if (type == null || type.isBlank()) {
            return List.of();
        }
        String like = "%\"" + type.replace("\\", "\\\\").replace("\"", "\\\"") + "\"%";
        return jdbc.query(
                "SELECT " + ENTITY_COLS + " FROM entities WHERE types LIKE ? ORDER BY name LIMIT ?",
                (rs, i) -> mapEntity(rs), like, Math.max(1, limit));
    }

    /** 集合内/触及集合的边（含 source_chunk，供溯源） */
    public List<Edge> edgesTouching(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(GraphStore::quote).collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT id, subject_id, predicate, object_id, props, source_chunk FROM relations "
                        + "WHERE subject_id IN (" + in + ") OR object_id IN (" + in + ")",
                (rs, i) -> {
                    long chunkId = rs.getLong("source_chunk");
                    boolean noChunk = rs.wasNull();
                    return new Edge(
                            rs.getString("id"), rs.getString("subject_id"), rs.getString("predicate"),
                            rs.getString("object_id"), parseProps(rs.getString("props")),
                            noChunk ? null : chunkId);
                });
    }

    /** 与给定实体相邻的另一端实体 id */
    public Set<String> neighborsOf(Collection<String> ids) {
        Set<String> out = new HashSet<>();
        for (Edge e : edgesTouching(ids)) {
            if (ids.contains(e.subjectId())) {
                out.add(e.objectId());
            }
            if (ids.contains(e.objectId())) {
                out.add(e.subjectId());
            }
        }
        return out;
    }

    public List<Entity> entitiesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(GraphStore::quote).collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT " + ENTITY_COLS + " FROM entities WHERE id IN (" + in + ")",
                (rs, i) -> mapEntity(rs));
    }

    public List<Long> chunkIdsForEntities(Collection<String> entityIds, int limit) {
        if (entityIds.isEmpty()) {
            return List.of();
        }
        String in = entityIds.stream().map(GraphStore::quote).collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT chunk_id FROM chunk_entity WHERE entity_id IN (" + in + ") LIMIT ?",
                (rs, i) -> rs.getLong(1),
                limit);
    }

    public void linkEntityChunk(long chunkId, String entityId) {
        jdbc.update("INSERT OR IGNORE INTO chunk_entity(chunk_id, entity_id) VALUES(?,?)",
                chunkId, entityId);
    }

    /** 删除由某 chunk 承载的关系（relations.source_chunk）；chunk 删除/重切前调用，返回删除行数 */
    public int deleteRelationsByChunk(long chunkId) {
        return jdbc.update("DELETE FROM relations WHERE source_chunk = ?", chunkId);
    }

    /** 删除一条关系（按稳定 id）；返回删除行数（0=不存在） */
    public int deleteRelation(String relationId) {
        return jdbc.update("DELETE FROM relations WHERE id = ?", relationId);
    }

    /**
     * 删除一个实体及其全部关联：先删该实体承担的关系（主/宾两侧，外键要求先清），
     * 再删 chunk_entity 关联与实体向量（entity_embedding），最后删实体行；
     * 返回连带删除的关系条数。实体不存在时各步影响 0 行，返回 0。
     */
    @Transactional
    public int deleteEntity(String entityId) {
        int relations = jdbc.update("DELETE FROM relations WHERE subject_id = ? OR object_id = ?",
                entityId, entityId);
        jdbc.update("DELETE FROM chunk_entity WHERE entity_id = ?", entityId);
        jdbc.update("DELETE FROM entity_embedding WHERE id = ?", entityId);
        jdbc.update("DELETE FROM entities WHERE id = ?", entityId);
        return relations;
    }

    /** 清空全图的结果：删除的实体与关系条数 */
    public record Cleared(int entities, int relations) {
    }

    /**
     * 清空全部实体与关系及其关联与向量（重置知识库用）：先删关系（外键指向实体，须先清），
     * 再删 chunk_entity 关联与实体向量（entity_embedding），最后删实体行；返回删除条数。
     */
    @Transactional
    public Cleared clearAll() {
        int relations = jdbc.update("DELETE FROM relations");
        jdbc.update("DELETE FROM chunk_entity");
        jdbc.update("DELETE FROM entity_embedding");
        return new Cleared(jdbc.update("DELETE FROM entities"), relations);
    }

    /** chunk_entity 关联行（JOIN entities 取名称/类型，供文档分析页按 chunk 聚合实体） */
    public record ChunkEntityLink(long chunkId, String entityId, String name, String type) {
    }

    /** 源自某 chunk 的关系（JOIN 主/宾实体名；source_chunk 侧） */
    public record ChunkRelationLink(long chunkId, String relationId, String predicate,
            String subjectName, String objectName) {
    }

    /** 给定 chunk 集合关联的全部实体（按 chunk、实体名排序） */
    public List<ChunkEntityLink> entitiesByChunks(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        String in = chunkIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT ce.chunk_id, e.id, e.name, e.type FROM chunk_entity ce "
                        + "JOIN entities e ON e.id = ce.entity_id "
                        + "WHERE ce.chunk_id IN (" + in + ") ORDER BY ce.chunk_id, e.name",
                (rs, i) -> new ChunkEntityLink(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    /** 给定 chunk 集合产出的全部关系（source_chunk 侧，按 chunk、关系 id 排序） */
    public List<ChunkRelationLink> relationsByChunks(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        String in = chunkIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return jdbc.query(
                "SELECT r.source_chunk, r.id, r.predicate, s.name, o.name FROM relations r "
                        + "JOIN entities s ON s.id = r.subject_id "
                        + "JOIN entities o ON o.id = r.object_id "
                        + "WHERE r.source_chunk IN (" + in + ") ORDER BY r.source_chunk, r.id",
                (rs, i) -> new ChunkRelationLink(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)));
    }

    /** 写入关系（source_chunk 关联产出该关系的 chunk）；返回 1=新插入，0=已存在被忽略 */
    public int upsertRelation(String subjectId, String predicate, String objectId, Long sourceChunk) {
        String id = relationIdFor(subjectId, predicate, objectId);
        return jdbc.update(
                "INSERT OR IGNORE INTO relations(id, subject_id, predicate, object_id, source_chunk) "
                        + "VALUES(?,?,?,?,?)",
                id, subjectId, predicate, objectId, sourceChunk);
    }

    /** chunk 知识提取记录行（triples 为提交快照 JSON，由调用方序列化/解析） */
    public record ChunkExtraction(long chunkId, String triples, long createdAt) {
    }

    /** 覆盖式保存 chunk 的知识提取记录（同一 chunk 重复提交只保留最近一次） */
    public void saveChunkExtraction(long chunkId, String triplesJson) {
        jdbc.update(
                "INSERT INTO chunk_extraction(chunk_id, triples, created_at) VALUES(?,?,?) "
                        + "ON CONFLICT(chunk_id) DO UPDATE SET triples=excluded.triples, "
                        + "created_at=excluded.created_at",
                chunkId, triplesJson, System.currentTimeMillis());
    }

    /** 读取 chunk 的知识提取记录（无则 null） */
    public ChunkExtraction chunkExtraction(long chunkId) {
        return jdbc.query(
                "SELECT chunk_id, triples, created_at FROM chunk_extraction WHERE chunk_id = ?",
                rs -> rs.next() ? new ChunkExtraction(rs.getLong(1), rs.getString(2),
                        rs.getLong(3)) : null,
                chunkId);
    }

    /** 实体向量 upsert（文档导入时对节点文本向量化；embedding 未配置时调用方跳过） */
    public void saveEntityEmbedding(String entityId, String model, float[] vec) {
        jdbc.update(
                "INSERT INTO entity_embedding(id, model, dim, vec, updated_at) VALUES(?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET model=excluded.model, dim=excluded.dim, "
                        + "vec=excluded.vec, updated_at=excluded.updated_at",
                entityId, model, vec.length, VecMath.encode(vec), System.currentTimeMillis());
    }

    /** 实体向量相似度命中（同模型全量余弦 top-k，M0 暴力；sim<=0 不返回） */
    public record EntitySimilarity(String entityId, float sim) {
    }

    /** 按查询向量召回实体（读 entity_embedding；model 未配置/无向量返回空）。字面 LIKE 命不中时
     *  的语义补充（如「我们公司」→ 星尘科技）。 */
    public List<EntitySimilarity> entitiesByCosine(String model, float[] qvec, int limit) {
        if (model == null || model.isBlank() || qvec == null || qvec.length == 0 || limit <= 0) {
            return List.of();
        }
        List<EntitySimilarity> scored = new ArrayList<>();
        jdbc.query(
                "SELECT id, dim, vec FROM entity_embedding WHERE model = ?",
                rs -> {
                    while (rs.next()) {
                        float sim = VecMath.cosine(qvec, VecMath.decode(rs.getBytes("vec"), rs.getInt("dim")));
                        if (sim > 0) {
                            scored.add(new EntitySimilarity(rs.getString("id"), sim));
                        }
                    }
                    return null;
                },
                model);
        scored.sort((a, b) -> Float.compare(b.sim(), a.sim()));
        return scored.size() <= limit ? scored : scored.subList(0, limit);
    }

    /** 实体公共列（各查询拼列名时引用，避免漏带时间戳列） */
    private static final String ENTITY_COLS =
            "id, type, types, name, aliases, summary, meta, created_at, updated_at";

    private Entity mapEntity(java.sql.ResultSet rs) throws java.sql.SQLException {
        /* wasNull 只反映紧邻的上一次读取，须在读取其它列前先取好标记 */
        long createdAt = rs.getLong("created_at");
        boolean noCreated = rs.wasNull();
        long updatedAt = rs.getLong("updated_at");
        boolean noUpdated = rs.wasNull();
        return new Entity(
                rs.getString("id"),
                rs.getString("type"),
                parseStringList(rs.getString("types")),
                rs.getString("name"),
                rs.getString("aliases"),
                rs.getString("summary"),
                parseProps(rs.getString("meta")),
                noCreated ? null : createdAt,
                noUpdated ? null : updatedAt);
    }

    /** 覆盖式更新实体 KV 属性（meta 列），并刷新 updated_at；返回受影响行数（0 = 实体不存在） */
    public int updateEntityProps(String entityId, List<Prop> props) {
        return jdbc.update("UPDATE entities SET meta = ?, updated_at = ? WHERE id = ?",
                writeProps(props), System.currentTimeMillis(), entityId);
    }

    /** 覆盖式更新关系 KV 属性（props 列）；返回受影响行数（0 = 关系不存在） */
    public int updateRelationProps(String relationId, List<Prop> props) {
        return jdbc.update("UPDATE relations SET props = ? WHERE id = ?", writeProps(props),
                relationId);
    }

    /** 仅刷新实体行的 updated_at（created_at 恒不动）：供「不改实体行、但实体上知识有变动」的
     *  写入留痕——如 upsert_edge 新挂关系、edit_relation_props 改关系属性时，两端的实体被触及。 */
    public void touchEntities(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String in = ids.stream().map(GraphStore::quote).collect(Collectors.joining(","));
        jdbc.update("UPDATE entities SET updated_at = ? WHERE id IN (" + in + ")",
                System.currentTimeMillis());
    }

    /** 别名归一：去空/去首尾空白/去重/剔除与实体名相同者（保序） */
    private static List<String> cleanAliases(String name, List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String a : aliases) {
            if (a == null || a.isBlank()) {
                continue;
            }
            String v = a.strip();
            if (!v.equals(name)) {
                set.add(v);
            }
        }
        return List.copyOf(set);
    }

    /** 类型归一：去空/去重/保序；全空回退 CONCEPT */
    private static List<String> normalizeTypes(String primary, List<String> extras) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (primary != null && !primary.isBlank()) {
            set.add(primary.strip());
        }
        if (extras != null) {
            for (String t : extras) {
                if (t != null && !t.isBlank()) {
                    set.add(t.strip());
                }
            }
        }
        if (set.isEmpty()) {
            set.add("CONCEPT");
        }
        return List.copyOf(set);
    }

    /** 既有实体类型并上新类型：主 type 恒居首（历史行 types 为空时由 type 派生） */
    private static List<String> mergeTypes(Entity exist, List<String> incoming) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(exist.type() == null || exist.type().isBlank() ? "CONCEPT" : exist.type());
        if (exist.types() != null) {
            set.addAll(exist.types());
        }
        for (String t : incoming) {
            if (t != null && !t.isBlank()) {
                set.add(t.strip());
            }
        }
        return List.copyOf(set);
    }

    /** types 列 JSON → 类型列表（空/坏数据按空处理） */
    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = om.readTree(json);
            if (!arr.isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    out.add(n.asText());
                }
            }
            return out;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** meta/props 列 JSON → KV 列表（空/坏数据按空处理） */
    private List<Prop> parseProps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Prop[] arr = om.readValue(json, Prop[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeProps(List<Prop> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(props);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return "[" + values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",")) + "]";
    }
}
