package cc.ginpika.minis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S0 spike：验证 xerial sqlite-jdbc 在该环境可用且 FTS5 trigram tokenizer 可建表、中文子串可命中等。
 * 覆盖《二次评估.md》§5 待 spike 与《M0-骨架设计.md》§9-4 的关键环境风险。
 */
class SqliteSpikeTest {

    private static final String JDBC = "jdbc:sqlite::memory:";

    private List<Long> search(Connection c, String table, String match) throws Exception {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps =
                c.prepareStatement("SELECT rowid FROM " + table + " WHERE " + table + " MATCH ?")) {
            ps.setString(1, match);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    @Test
    void trigramChineseSubstringAndAsciiMatch() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC);
                Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE fts_cn USING fts5(content, tokenize='trigram')");

            String zh = "我最近在研究 SQLite 的 vec1 扩展和 FTS5 trigram tokenizer。";
            String en = "图数据库和向量检索的组合方案，配合递归 CTE 遍历邻接关系。";
            try (PreparedStatement ps =
                    c.prepareStatement("INSERT INTO fts_cn(rowid, content) VALUES(?,?)")) {
                ps.setLong(1, 1);
                ps.setString(2, zh);
                ps.addBatch();
                ps.setLong(1, 2);
                ps.setString(2, en);
                ps.addBatch();
                ps.executeBatch();
            }

            // 中文 ≥3 字子串命中（"向量检索" 只出现在 row2）
            assertEquals(List.of(2L), search(c, "fts_cn", "\"向量检索\""));
            // 相邻英文 token（vec1 为 4 字母，trigram 可建）只出现在 row1
            assertEquals(List.of(1L), search(c, "fts_cn", "\"vec1\""));
        }
    }

    @Test
    void unicode61EnglishTitleMatch() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC);
                Statement st = c.createStatement()) {
            st.execute("CREATE VIRTUAL TABLE fts_meta USING fts5(title, content, content='', tokenize='unicode61')");
            try (PreparedStatement ps =
                    c.prepareStatement("INSERT INTO fts_meta(rowid, title, content) VALUES(?,?,?)")) {
                ps.setLong(1, 1);
                ps.setString(2, "SQLite vec1 extension notes");
                ps.setString(3, "sqlite vec1 and fts5 trigram");
                ps.executeUpdate();
            }
            assertTrue(search(c, "fts_meta", "vec1").contains(1L));
            // 分词非完整 token 不应命中（验证 unicode61 为词级）
            assertTrue(search(c, "fts_meta", "sqlit").isEmpty());
        }
    }
}
