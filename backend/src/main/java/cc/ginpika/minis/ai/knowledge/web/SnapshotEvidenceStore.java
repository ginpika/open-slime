package cc.ginpika.minis.ai.knowledge.web;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** A rebuildable paragraph index, independent of mutable retrieval chunks and embedded media. */
@Repository
public class SnapshotEvidenceStore {
    private final JdbcTemplate jdbc;

    public SnapshotEvidenceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void write(String snapshotId, List<WebDtos.Paragraph> paragraphs) {
        var values = new ArrayList<Object[]>();
        for (int i = 0; i < paragraphs.size(); i++) {
            var paragraph = paragraphs.get(i);
            values.add(new Object[]{snapshotId, i, paragraph.id(), paragraph.text()});
        }
        jdbc.batchUpdate("INSERT OR IGNORE INTO web_snapshot_paragraph(snapshot_id,ordinal,paragraph_id,content) VALUES(?,?,?,?)", values);
    }

    @Transactional
    public List<WebDtos.Paragraph> paragraphs(String snapshotId) {
        var result = indexed(snapshotId);
        if (!result.isEmpty()) return result;
        // Old snapshots acquire the derived index lazily; source HTML and its hash remain untouched.
        var html = jdbc.queryForList("SELECT source_html FROM web_snapshot WHERE id=?", String.class, snapshotId);
        if (html.isEmpty()) throw new IllegalArgumentException("快照不存在");
        var paragraphs = new ArrayList<WebDtos.Paragraph>();
        for (var element : Jsoup.parse(html.getFirst()).select("[id]")) {
            if (element.id().matches("p[0-9]+"))
                paragraphs.add(new WebDtos.Paragraph(element.id(), element.wholeText()));
        }
        write(snapshotId, paragraphs);
        return indexed(snapshotId);
    }

    private List<WebDtos.Paragraph> indexed(String id) {
        return jdbc.query("SELECT paragraph_id,content FROM web_snapshot_paragraph WHERE snapshot_id=? ORDER BY ordinal",
                (rs, i) -> new WebDtos.Paragraph(rs.getString(1), rs.getString(2)), id);
    }

    @Transactional
    public String text(String snapshotId) {
        return paragraphs(snapshotId).stream().map(p -> "[" + p.id() + "] " + p.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }
}
