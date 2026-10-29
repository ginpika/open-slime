package cc.ginpika.minis.ai.knowledge.web;

import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQLite is the source of truth, so evidence and search publication commit atomically. */
@Repository
public class WebSnapshotStore {
    private final JdbcTemplate jdbc;
    private final ChunkStore chunks;
    private final DocumentStore docs;
    private final ObjectMapper om;
    private final SnapshotEvidenceStore evidence;
    private static final String METADATA = "id,doc_id,url,title,author,published_at,captured_at,saved_at,content_hash,summary,rationale,claims,model,warnings,index_status,enabled";
    public WebSnapshotStore(JdbcTemplate jdbc, ChunkStore chunks, DocumentStore docs, ObjectMapper om, SnapshotEvidenceStore evidence) {
        this.jdbc = jdbc; this.chunks = chunks; this.docs = docs; this.om = om;
        this.evidence = evidence;
    }

    @Transactional
    public synchronized WebDtos.Snapshot save(WebDtos.Capture capture, WebDtos.SaveRequest request) {
        List<String> existing = jdbc.queryForList("SELECT id FROM web_snapshot WHERE url=? AND content_hash=?", String.class,
                capture.url(), capture.contentHash());
        if (!existing.isEmpty()) return get(existing.getFirst());
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Long docId = jdbc.queryForObject("INSERT INTO documents(title,source,summary,created_at,contextualizing) VALUES(?,'snapshot',?,?,?) RETURNING id",
                Long.class, request.title(), request.summary(), now, request.summary());
        jdbc.update("""
                INSERT INTO web_snapshot(id,doc_id,url,title,author,published_at,captured_at,saved_at,
                content_hash,source_html,summary,rationale,claims,model,warnings) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, docId, capture.url(), request.title(), capture.author(), capture.publishedAt(), capture.capturedAt(), now,
                capture.contentHash(), capture.sourceHtml(), request.summary(), request.rationale(),
                json(request.claims()), request.model(), json(capture.warnings()));
        evidence.write(id, capture.paragraphs());
        // Only source evidence is indexed as factual content; generated claims remain a distinct view.
        int index = 0;
        StringBuilder block = new StringBuilder();
        for (WebDtos.Paragraph paragraph : capture.paragraphs()) {
            for (int offset = 0; offset < paragraph.text().length(); offset += 900) {
                String piece = "[" + paragraph.id() + "] " + paragraph.text().substring(offset, Math.min(offset + 900, paragraph.text().length()));
                if (!block.isEmpty() && block.length() + piece.length() > 1000) {
                    writeChunk(id, docId, request.title(), capture.url(), index++, block.toString());
                    block.setLength(0);
                }
                if (!block.isEmpty()) block.append("\n\n");
                block.append(piece);
            }
        }
        if (!block.isEmpty()) writeChunk(id, docId, request.title(), capture.url(), index, block.toString());
        return get(id);
    }

    private void writeChunk(String id, long docId, String title, String url, int index, String text) {
        chunks.replace("snapshot:" + id + ":" + index, "snapshot", "snapshot:" + id, title,
                "# " + title + "\n来源：" + url + "\n本地快照：#/snapshots/" + id + "\n\n" + text,
                List.of("网页快照"), docId, DocumentStore.STATUS_UNANALYZED);
    }

    public List<WebDtos.Snapshot> list(int page, int size) {
        return jdbc.query("SELECT " + METADATA + ",'' AS source_html FROM web_snapshot ORDER BY saved_at DESC,id DESC LIMIT ? OFFSET ?",
                this::snapshot, size, (long) (page - 1) * size);
    }

    public WebDtos.Snapshot get(String id) {
        return find(id, true);
    }

    public WebDtos.Snapshot metadata(String id) { return find(id, false); }
    public List<WebDtos.Paragraph> paragraphs(String id) { return evidence.paragraphs(id); }

    private WebDtos.Snapshot find(String id, boolean includeHtml) {
        var rows = jdbc.query("SELECT " + METADATA + (includeHtml ? ",source_html" : ",'' AS source_html")
                + " FROM web_snapshot WHERE id=?", this::snapshot, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("快照不存在");
        return rows.getFirst();
    }

    private WebDtos.Snapshot snapshot(java.sql.ResultSet r, int i) throws java.sql.SQLException {
        return new WebDtos.Snapshot(
                r.getString("id"),r.getObject("doc_id") == null ? null : r.getLong("doc_id"),r.getString("url"),r.getString("title"),
                r.getString("author"),r.getString("published_at"),r.getLong("captured_at"),r.getLong("saved_at"),r.getString("content_hash"),
                r.getString("summary"),r.getString("rationale"),read(r.getString("claims"),WebDtos.Claim.class),r.getString("model"),
                read(r.getString("warnings"),String.class),r.getString("index_status"),r.getBoolean("enabled"),r.getString("source_html"),"");
    }

    @Transactional
    public WebDtos.Renamed rename(String id, String requestedTitle) {
        String title=requestedTitle==null?"":requestedTitle.strip();
        if(title.isBlank() || title.length()>500 || title.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("快照名称须为 1～500 字符，不能包含换行或控制字符");
        var rows=jdbc.queryForList("SELECT doc_id FROM web_snapshot WHERE id=?",id);
        if(rows.isEmpty())throw new IllegalArgumentException("快照不存在");
        jdbc.update("UPDATE web_snapshot SET title=? WHERE id=?",title,id);
        Object docId=rows.getFirst().get("doc_id");
        if(docId!=null) {
            jdbc.update("UPDATE documents SET title=? WHERE id=?",title,docId);
            jdbc.update("UPDATE chunks SET title=? WHERE doc_id=?",title,docId);
            // Change search metadata in place; keep evidence, row IDs, vectors and graph references intact.
            jdbc.update("DELETE FROM chunks_fts_meta WHERE rowid IN (SELECT id FROM chunks WHERE doc_id=?)",docId);
            jdbc.update("INSERT INTO chunks_fts_meta(rowid,title,tags,content) SELECT id,title,tags,content FROM chunks WHERE doc_id=?",docId);
            jdbc.update("DELETE FROM chunks_fts_cn WHERE rowid IN (SELECT id FROM chunks WHERE doc_id=?)",docId);
            jdbc.update("INSERT INTO chunks_fts_cn(rowid,content) SELECT id,title || char(10) || content FROM chunks WHERE doc_id=?",docId);
        }
        return new WebDtos.Renamed(id,title);
    }

    @Transactional
    public WebDtos.Snapshot toggle(String id, boolean enabled) {
        WebDtos.Snapshot snapshot = get(id);
        jdbc.update("UPDATE web_snapshot SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        if (snapshot.docId() != null) {
            for (var chunk : chunks.rowsByDoc(snapshot.docId())) {
                chunks.updateStatus(chunk.id(), enabled ? DocumentStore.STATUS_UNANALYZED : DocumentStore.STATUS_DISABLED);
                if (!enabled) {
                    jdbc.update("DELETE FROM chunk_embedding WHERE chunk_id=?",chunk.id());
                    new cc.ginpika.minis.ai.memory.graph.MemoryGraphStore(jdbc).retractSources(List.of("chunk:"+chunk.id()));
                }
            }
        }
        indexStatus(id,enabled ? "pending" : "disabled");
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        WebDtos.Snapshot snapshot = get(id);
        jdbc.update("DELETE FROM web_snapshot_paragraph WHERE snapshot_id=?", id);
        if (snapshot.docId() != null) {
            for (var chunk : chunks.rowsByDoc(snapshot.docId())) docs.deleteChunk(chunk.id());
            jdbc.update("DELETE FROM web_snapshot WHERE id=?", id);
            jdbc.update("DELETE FROM documents WHERE id=?", snapshot.docId());
        } else jdbc.update("DELETE FROM web_snapshot WHERE id=?", id);
    }

    public void indexStatus(String id, String status) { jdbc.update("UPDATE web_snapshot SET index_status=? WHERE id=?", status, id); }
    public long memoryGeneration() { return cc.ginpika.minis.ai.memory.MemoryResetBoundary.read(jdbc).generation(); }
    public void indexStatus(String id,String status,long generation) {
        jdbc.update("UPDATE web_snapshot SET index_status=? WHERE id=? AND COALESCE((SELECT last_event_id FROM memory_cursor WHERE session_id='reset:generation'),0)=?",status,id,generation);
    }
    public List<String> unfinished() {
        return jdbc.queryForList("SELECT id FROM web_snapshot WHERE enabled=1 AND index_status IN ('pending','indexing')",String.class);
    }
    /** One atomic INSERT SELECT prevents deleted/disabled snapshots from acquiring orphan vectors. */
    public void saveEmbeddingIfEnabled(String id, long chunkId, String model, float[] vector) {
        jdbc.update("""
                INSERT INTO chunk_embedding(chunk_id,model,dim,vec,updated_at)
                SELECT c.id,?,?,?,? FROM chunks c JOIN web_snapshot s ON s.doc_id=c.doc_id
                WHERE s.id=? AND s.enabled=1 AND c.id=? AND c.status!='disabled'
                ON CONFLICT(chunk_id) DO UPDATE SET model=excluded.model,dim=excluded.dim,vec=excluded.vec,updated_at=excluded.updated_at
                """, model,vector.length,cc.ginpika.minis.ai.knowledge.retrieval.VecMath.encode(vector),System.currentTimeMillis(),id,chunkId);
    }
    private String json(Object value) {
        try { return om.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private <T> List<T> read(String value, Class<T> type) {
        try { return om.readValue(value, om.getTypeFactory().constructCollectionType(List.class, type)); }
        catch (Exception e) { throw new IllegalStateException("快照数据损坏", e); }
    }
}
