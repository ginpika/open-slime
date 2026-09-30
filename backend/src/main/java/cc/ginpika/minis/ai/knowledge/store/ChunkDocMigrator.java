package cc.ginpika.minis.ai.knowledge.store;

import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文档模型迁移（幂等，启动时执行一次）：
 * ① chunks 补 doc_id/status/header/questions 列 + idx_chunks_doc（文档分析工作流 + 最终提交预案留档）；
 * ② documents 补 url/summary/contextualizing/doc_type 列 + idx_documents_url 唯一索引（文章并入文档模型）；
 * ③ 旧 articles 索引表合并入 documents（元数据复制 + markdown 分块回填），完成后移除该表；
 * ④ entities 补 types 列（多类型 JSON array，主 type 首写定不覆盖）；
 * ⑤ entities 补 updated_at 列（实体行最近变动时间，旧行回填 created_at）。
 * 两个索引均只由此处创建——schema.sql 对旧库的 CREATE TABLE IF NOT EXISTS 不生效，
 * 先行 CREATE INDEX 会因缺列失败（见 schema.sql 注释）。
 * 时机：注入 DataSource 的 bean 构造时 schema.sql 已由 sql init 执行完（同 HubSessionMigrator）。
 */
@Component
public class ChunkDocMigrator {

    private static final Logger log = LoggerFactory.getLogger(ChunkDocMigrator.class);

    private final DataSource dataSource;
    private final DocumentStore documents;

    public ChunkDocMigrator(DataSource dataSource, DocumentStore documents) {
        this.dataSource = dataSource;
        this.documents = documents;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            // ① chunks：doc_id / status（默认 NULL / unanalyzed；历史块不归属任何文档）
            if (!hasColumn(c, "chunks", "doc_id")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE chunks ADD COLUMN doc_id INTEGER REFERENCES documents(id)");
                }
                log.info("chunks 表已补列 doc_id");
            }
            if (!hasColumn(c, "chunks", "status")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE chunks ADD COLUMN status TEXT NOT NULL DEFAULT 'unanalyzed'");
                }
                log.info("chunks 表已补列 status");
            }
            // ① chunks：header / questions（最终提交预案留档：生成时落库，二次进入回显）
            if (!hasColumn(c, "chunks", "header")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE chunks ADD COLUMN header TEXT");
                }
                log.info("chunks 表已补列 header");
            }
            if (!hasColumn(c, "chunks", "questions")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE chunks ADD COLUMN questions TEXT");
                }
                log.info("chunks 表已补列 questions");
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(doc_id)");
            }
            // ② documents：url / summary（文章并入文档模型）
            if (!hasColumn(c, "documents", "url")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE documents ADD COLUMN url TEXT");
                }
                log.info("documents 表已补列 url");
            }
            if (!hasColumn(c, "documents", "summary")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE documents ADD COLUMN summary TEXT");
                }
                log.info("documents 表已补列 summary");
            }
            if (!hasColumn(c, "documents", "contextualizing")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE documents ADD COLUMN contextualizing TEXT");
                }
                log.info("documents 表已补列 contextualizing");
            }
            if (!hasColumn(c, "documents", "doc_type")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE documents ADD COLUMN doc_type TEXT");
                }
                log.info("documents 表已补列 doc_type");
            }
            try (Statement st = c.createStatement()) {
                st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_documents_url ON documents(url)");
            }
            // ④ entities：types（多类型 JSON array；主 type 仍在 type 列且首写定，旧行由 type 派生）
            if (!hasColumn(c, "entities", "types")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE entities ADD COLUMN types TEXT");
                }
                log.info("entities 表已补列 types");
            }
            // ⑤ entities：updated_at（实体行最近一次变动时间；旧行回填 created_at，避免 NULL 语义）
            if (!hasColumn(c, "entities", "updated_at")) {
                try (Statement st = c.createStatement()) {
                    st.execute("ALTER TABLE entities ADD COLUMN updated_at INTEGER");
                }
                try (Statement st = c.createStatement()) {
                    int backfilled = st.executeUpdate(
                            "UPDATE entities SET updated_at = created_at WHERE updated_at IS NULL");
                    log.info("entities 表已补列 updated_at（回填 {} 行）", backfilled);
                }
            }
            // ③ 旧 articles 表合并
            mergeArticles(c);
        } catch (SQLException e) {
            throw new IllegalStateException("文档模型迁移失败: " + e.getMessage(), e);
        }
    }

    /** articles 并入 documents：元数据复制（按 url 去重）→ md 分块回填（仅无 chunk 的文档）→ 移除旧表 */
    private void mergeArticles(Connection c) throws SQLException {
        if (!tableExists(c, "articles")) {
            return; // 新库或已合并
        }
        int copied;
        try (Statement st = c.createStatement()) {
            copied = st.executeUpdate("""
                    INSERT INTO documents(title, source, url, summary, created_at)
                    SELECT title, 'blog', url, summary, created_at FROM articles a
                    WHERE NOT EXISTS(SELECT 1 FROM documents d WHERE d.url = a.url)""");
        }
        if (copied > 0) {
            log.info("articles → documents：复制 {} 行元数据", copied);
        }
        // 分块回填：只处理尚无任何 chunk 的文章文档（重跑幂等；文件缺失的跳过并告警）
        record Pending(long id, String url, String title) {
        }
        List<Pending> pending = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT d.id, d.url, d.title FROM documents d
                     WHERE d.url IS NOT NULL
                       AND NOT EXISTS(SELECT 1 FROM chunks k WHERE k.doc_id = d.id)""")) {
            while (rs.next()) {
                pending.add(new Pending(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        }
        for (Pending p : pending) {
            String md = documents.markdown(p.url());
            if (md == null) {
                log.warn("文章文件缺失，跳过分块回填: {}", p.url());
                continue;
            }
            try {
                documents.rechunk(p.id(), p.title(), md, "blog", p.url());
            } catch (RuntimeException e) {
                log.warn("文章分块回填失败（{}）：{}", p.url(), e.getMessage());
            }
        }
        if (!pending.isEmpty()) {
            log.info("articles 合并：回填 {} 篇文章的分块", pending.size());
        }
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE articles");
            log.info("articles 表已并入 documents，旧表移除");
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
