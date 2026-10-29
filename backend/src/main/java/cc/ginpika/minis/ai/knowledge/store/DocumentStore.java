package cc.ginpika.minis.ai.knowledge.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import cc.ginpika.minis.ai.knowledge.web.SnapshotEvidenceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import cc.ginpika.minis.ai.knowledge.ingest.Chunker;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;

/**
 * 文档仓储（documents 表）：统一文档模型——文档分析工作流的父级 + 已发布文章的索引
 * （url 非空的行即文章，markdown 原文由本类文件侧存 data/article/{url}.md）。
 *
 * <p>与 chunk 的父子关系双向维护：chunk→doc 由 chunks.doc_id 外键承载（写块时填入），
 * doc→chunk 由本类的聚合查询（list 统计 / ChunkStore.rowsByDoc 列块）承载，
 * 两端读写在同一事务内完成，不落冗余镜像。
 *
 * <p>分块策略见 {@link Chunker}（markdown 标题优先 → 段落贪心 500~800 → LLM 语义切）。
 */
@Repository
public class DocumentStore {

    /** chunk 分析状态值域 */
    public static final String STATUS_UNANALYZED = "unanalyzed";
    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";

    /** 文档行 + 子块聚合统计（chunkCount=总块数，analyzedCount=已分析块数；contextualizing=全文概述，知识提取上下文用；docType=LLM 判定的文档类型） */
    public record DocSummary(long id, String title, long createdAt, int chunkCount,
            int analyzedCount, String contextualizing, String docType) {
    }

    /** create 的产物 */
    public record CreatedDoc(long id, String title, int chunks) {
    }

    /** 文章行（url 非空的文档） */
    public record ArticleMeta(long id, String url, String title, String summary, long createdAt) {
    }

    /** 搜索栏文档命中行（source: doc=内部文档 / blog=已发布文章） */
    public record DocHit(long id, String title, String url, String summary, String source) {
    }

    public record DirectoryEntry(long docId, String title, String source, String url, String localUrl,
                                 String snapshotId, long createdAt, boolean enabled, String indexStatus, String summary) {}
    public record DirectoryPage(List<DirectoryEntry> items, String nextCursor, boolean hasMore) {}

    /** One stable keyset order for both browsing and title search. No document bodies are fetched. */
    public DirectoryPage directory(String keywords, String source, String status, Long from, Long to,
                                   String cursor, int requestedLimit) {
        if (source != null && !List.of("doc", "blog", "snapshot").contains(source))
            throw new IllegalArgumentException("source 须为 doc、blog 或 snapshot");
        if (!List.of("all", "enabled", "disabled").contains(status))
            throw new IllegalArgumentException("status 须为 all、enabled 或 disabled");
        if (from != null && to != null && from > to) throw new IllegalArgumentException("created_from 不能晚于 created_to");
        int limit = Math.max(1, Math.min(30, requestedLimit));
        var predicates = new ArrayList<String>();
        var params = new ArrayList<Object>();
        predicates.add("1=1");
        if (keywords != null && !keywords.isBlank()) {
            for (String term : keywords.strip().split("\\s+")) {
                predicates.add("d.title LIKE ? ESCAPE '\\'"); params.add("%" + escapeLike(term) + "%");
            }
        }
        if (source != null) { predicates.add("d.source=?"); params.add(source); }
        // 'enabled' describes retrieval availability, not authorization or document analysis completion.
        String enabled = "COALESCE(s.enabled, NOT EXISTS(SELECT 1 FROM chunks c WHERE c.doc_id=d.id) OR EXISTS(SELECT 1 FROM chunks c WHERE c.doc_id=d.id AND c.status!='disabled'))";
        if (!"all".equals(status)) { predicates.add(enabled + "=?"); params.add("enabled".equals(status) ? 1 : 0); }
        if (from != null) { predicates.add("d.created_at>=?"); params.add(from); }
        if (to != null) { predicates.add("d.created_at<=?"); params.add(to); }
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1);
                if (values.length != 2) throw new IllegalArgumentException();
                long time = Long.parseLong(values[0]), id = Long.parseLong(values[1]);
                if (time < 0 || id <= 0) throw new IllegalArgumentException();
                predicates.add("(d.created_at<? OR (d.created_at=? AND d.id<?))");
                params.add(time); params.add(time); params.add(id);
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("cursor 无效，请重新列举并使用返回的 next_cursor"); }
        }
        params.add(limit + 1);
        var entries = jdbc.query("SELECT d.id,d.title,d.source,COALESCE(s.url,d.url) AS url,s.id AS snapshot_id,"
                        + "d.created_at," + enabled + " AS enabled,s.index_status,substr(COALESCE(NULLIF(d.summary,''),d.contextualizing,''),1,200) AS summary "
                        + "FROM documents d LEFT JOIN web_snapshot s ON s.doc_id=d.id WHERE " + String.join(" AND ", predicates)
                        + " ORDER BY d.created_at DESC,d.id DESC LIMIT ?",
                (rs, i) -> {
                    String snapshot = rs.getString("snapshot_id"), url = rs.getString("url");
                    String local = snapshot != null ? "#/snapshots/" + snapshot
                            : "blog".equals(rs.getString("source")) && url != null ? "#/article/" + url : "#/analyze?doc=" + rs.getLong("id");
                    return new DirectoryEntry(rs.getLong("id"), rs.getString("title"), rs.getString("source"), snapshot != null ? url : null,
                            local, snapshot, rs.getLong("created_at"), rs.getBoolean("enabled"), rs.getString("index_status"), rs.getString("summary"));
                }, params.toArray());
        boolean more = entries.size() > limit;
        var items = List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
        String next = null;
        if (more) {
            var last = items.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((last.createdAt() + ":" + last.docId()).getBytes(StandardCharsets.UTF_8));
        }
        return new DirectoryPage(items, next, more);
    }

    /** 按 id 批量取文档行；不存在的跳过 */
    public List<DocHit> docsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String in = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return jdbc.query(
                "SELECT id, title, url, summary, source FROM documents WHERE id IN (" + in + ")",
                (rs, i) -> new DocHit(rs.getLong("id"), rs.getString("title"), rs.getString("url"),
                        rs.getString("summary"), rs.getString("source")));
    }

    /** LIKE 通配符转义（配合 ESCAPE '\'）：让关键词里的 % _ \ 按字面匹配 */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private final JdbcTemplate jdbc;
    private final ChunkStore chunks;
    private final GraphStore graph;
    private final Chunker chunker;
    private final Path articleDir;
    private final SnapshotEvidenceStore snapshotEvidence;

    public DocumentStore(JdbcTemplate jdbc, ChunkStore chunks, GraphStore graph,
                         Chunker chunker,
                         @Value("${minis.article-dir:../data/article}") String articleDir,
                         SnapshotEvidenceStore snapshotEvidence) {
        this.jdbc = jdbc;
        this.chunks = chunks;
        this.graph = graph;
        this.chunker = chunker;
        this.articleDir = Path.of(articleDir).toAbsolutePath().normalize();
        this.snapshotEvidence = snapshotEvidence;
    }

    /** 写文章 markdown 原文（发布/更新的文件侧） */
    public void writeMarkdown(String url, String markdown) {
        try {
            Files.createDirectories(articleDir);
            Files.writeString(articleDir.resolve(url + ".md"), markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写文章文件失败: " + url, e);
        }
    }

    /** 读文章 markdown 原文；文件缺失返回 null（详情页 404） */
    public String markdown(String url) {
        Path file = articleDir.resolve(url + ".md").normalize();
        if (!file.startsWith(articleDir) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读文章文件失败: " + url, e);
        }
    }

    /** 建内部文档并分块入库（无 url，不发布为文章） */
    @Transactional
    public CreatedDoc create(String title, String content) {
        long docId = jdbc.queryForObject(
                "INSERT INTO documents(title, source, created_at) VALUES(?, 'doc', ?) RETURNING id",
                Long.class, title, System.currentTimeMillis());
        int pieces = rechunk(docId, title, content, "doc", "doc:" + docId);
        return new CreatedDoc(docId, title, pieces);
    }

    /** 发布/更新文章：写 markdown 文件 + upsert documents 行（url 冲突时更新，保留首次发布时间）+ 重新分块 */
    @Transactional
    public void saveArticle(String url, String title, String summary, String markdown) {
        writeMarkdown(url, markdown);
        jdbc.update(
                "INSERT INTO documents(title, source, url, summary, created_at) VALUES(?, 'blog', ?, ?, ?) "
                        + "ON CONFLICT(url) DO UPDATE SET title=excluded.title, summary=excluded.summary",
                title, url, summary, System.currentTimeMillis());
        Long docId = jdbc.queryForObject("SELECT id FROM documents WHERE url = ?", Long.class, url);
        rechunk(docId, title, markdown, "blog", url);
    }

    /**
     * 触发文档（重新）分块：文章文档（url 非空）读 md 原文重切；
     * 内部文档由现有 chunk 内容按序拼接恢复原文。均走 rechunk（清旧块及其承载的关系/向量/实体链接）。
     */
    @Transactional
    public CreatedDoc rechunkDocument(long docId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, source, url FROM documents WHERE id = ?", docId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        var row = rows.get(0);
        String title = (String) row.get("title");
        String source = row.get("source") == null ? "doc" : row.get("source").toString();
        String url = (String) row.get("url");
        String content = fullText(docId);
        if (content == null) {
            throw new IllegalStateException("文章 markdown 文件缺失: " + url);
        }
        String sourceRef = url != null ? url : "doc:" + docId;
        if ("snapshot".equals(source)) {
            List<String> ids = jdbc.queryForList("SELECT id FROM web_snapshot WHERE doc_id=?",String.class,docId);
            if (!ids.isEmpty()) sourceRef = "snapshot:" + ids.getFirst();
        }
        return new CreatedDoc(docId, title, rechunk(docId, title, content, source, sourceRef));
    }

    /**
     * 文档全文：文章文档（url 非空）读 md 原文（文件缺失返回 null）；
     * 内部文档由现有 chunk 内容按序拼接（无 chunk 返回空串）；文档不存在返回 null。
     */
    public String fullText(long docId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT url,source FROM documents WHERE id = ?", docId);
        if (rows.isEmpty()) {
            return null;
        }
        String url = (String) rows.get(0).get("url");
        if ("snapshot".equals(rows.getFirst().get("source"))) {
            var snapshots = jdbc.queryForList("SELECT id,url,title FROM web_snapshot WHERE doc_id=?",docId);
            if (!snapshots.isEmpty()) {
                var snapshot = snapshots.getFirst();
                StringBuilder original = new StringBuilder("# " + snapshot.get("title") + "\n来源：" + snapshot.get("url")
                        + "\n本地快照：#/snapshots/" + snapshot.get("id") + "\n\n");
                original.append(snapshotEvidence.text((String) snapshot.get("id")));
                return original.toString();
            }
        }
        if (url != null) {
            return markdown(url);
        }
        StringBuilder sb = new StringBuilder();
        for (ChunkStore.Row r : chunks.rowsByDoc(docId)) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(r.content());
        }
        return sb.toString();
    }

    /** 读文档全文概述（contextualizing，文档分析页「摘要」按钮）；不存在或未生成返回 null */
    public String contextualizing(long docId) {
        List<String> rows = jdbc.queryForList(
                "SELECT contextualizing FROM documents WHERE id = ?", String.class, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 文档标题（增强步骤组装上下文补完串用）；不存在返回 null */
    public String title(long docId) {
        List<String> rows = jdbc.queryForList(
                "SELECT title FROM documents WHERE id = ?", String.class, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 保存文档全文概述（LLM 总结全文后调用） */
    public void updateContextualizing(long docId, String contextualizing) {
        jdbc.update("UPDATE documents SET contextualizing = ? WHERE id = ?", contextualizing, docId);
    }

    /** 读文档类型（doc_type，LLM 生成摘要时判定）；不存在或未生成返回 null */
    public String docType(long docId) {
        List<String> rows = jdbc.queryForList(
                "SELECT doc_type FROM documents WHERE id = ?", String.class, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 保存文档类型（LLM 判定后调用） */
    public void updateDocType(long docId, String docType) {
        jdbc.update("UPDATE documents SET doc_type = ? WHERE id = ?", docType, docId);
    }

    /** 删除单个 chunk（连带 FTS/向量/实体链接/由其承载的关系）；chunk 不存在抛 IllegalArgumentException */
    @Transactional
    public void deleteChunk(long chunkId) {
        if (chunks.rowsByIds(List.of(chunkId)).isEmpty()) {
            throw new IllegalArgumentException("chunk 不存在: " + chunkId);
        }
        graph.deleteRelationsByChunk(chunkId);
        chunks.delete(chunkId);
    }

    /** 重切分：清掉该文档旧 chunk（连带 FTS/向量/实体关联/由其承载的关系）后按内容重新分块入库；返回块数 */
    @Transactional
    public int rechunk(long docId, String title, String content, String source, String sourceRef) {
        for (ChunkStore.Row old : chunks.rowsByDoc(docId)) {
            // 已启用的旧块先从检索数据源下线：置禁用并删除其向量（chunk_embedding），再清其余关联，避免旧块污染数据源
            if (STATUS_ENABLED.equals(old.status())) {
                chunks.updateStatus(old.id(), STATUS_DISABLED);
            }
            graph.deleteRelationsByChunk(old.id());
            chunks.delete(old.id());
        }
        List<String> pieces = chunker.split(content);
        for (int i = 0; i < pieces.size(); i++) {
            chunks.replace("doc:" + docId + "#" + i, source, sourceRef,
                    pieces.size() == 1 ? title : (title + " " + (i + 1)),
                    pieces.get(i), List.of(), docId, STATUS_UNANALYZED);
        }
        return pieces.size();
    }

    /** 文档列表（含子块统计、全文概述与文档类型；全部块均已分析即视为「已分析」） */
    public List<DocSummary> list() {
        return jdbc.query(
                """
                SELECT d.id, d.title, d.created_at, d.contextualizing, d.doc_type,
                       COUNT(c.id) AS chunk_count,
                       COALESCE(SUM(CASE WHEN c.status != 'unanalyzed' THEN 1 ELSE 0 END), 0) AS analyzed_count
                FROM documents d LEFT JOIN chunks c ON c.doc_id = d.id
                GROUP BY d.id
                ORDER BY d.created_at DESC, d.id DESC
                """,
                (rs, i) -> new DocSummary(
                        rs.getLong("id"), rs.getString("title"), rs.getLong("created_at"),
                        rs.getInt("chunk_count"), rs.getInt("analyzed_count"),
                        rs.getString("contextualizing"), rs.getString("doc_type")));
    }

    /** 已发布文章数（url 非空） */
    public long articleCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE url IS NOT NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** 已发布文章的一页（发布时间倒序，同刻按 url 倒序，与旧 articles 表排序一致） */
    public List<ArticleMeta> articlePage(int page, int size) {
        return jdbc.query(
                "SELECT id, url, title, summary, created_at FROM documents "
                        + "WHERE url IS NOT NULL ORDER BY created_at DESC, url DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ArticleMeta(rs.getLong("id"), rs.getString("url"), rs.getString("title"),
                        rs.getString("summary"), rs.getLong("created_at")),
                size, (long) (page - 1) * size);
    }

    /** 按 url 取文章元数据；不存在返回 null */
    public ArticleMeta articleMeta(String url) {
        return jdbc.query(
                "SELECT id, url, title, summary, created_at FROM documents WHERE url = ?",
                rs -> rs.next() ? new ArticleMeta(rs.getLong("id"), rs.getString("url"),
                        rs.getString("title"), rs.getString("summary"), rs.getLong("created_at")) : null,
                url);
    }
}
