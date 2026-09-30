package cc.ginpika.minis.ai.knowledge.recall;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * rag_recall（RAG 召回留痕）的读写：三路召回工具调用的一次记录。
 *
 * <p>写入一律走 {@link RagRecallRecorder}（在工具分发处埋点、失败隔离）；
 * 本类只管落行与读取。留痕只追加不改写——它是「模型当初检索了什么、拿到了什么原始数据」的审计，
 * 不是可编辑的配置。消费方是潜意识页的「RAG 召回」子页（回看 RAG 效果）。
 */
@Repository
public class RagRecallStore {

    /** 一条召回留痕：result 为回给模型的 tool 消息原文，resultChars 为截断前的原始字符数 */
    public record RecallRow(long id, String channel, String tool, String source, String query,
                            String result, long resultChars, long createdAt) {
    }

    private static final String COLS =
            "id, channel, tool, source, query, result, result_chars, created_at";

    private final JdbcTemplate jdbc;

    public RagRecallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条留痕 */
    public void append(String channel, String tool, String source, String query, String result,
                       long resultChars) {
        jdbc.update(
                "INSERT INTO rag_recall(channel, tool, source, query, result, result_chars, "
                        + "created_at) VALUES(?,?,?,?,?,?,?)",
                channel, tool, source, query, result, resultChars, System.currentTimeMillis());
    }

    /** 最近若干条（时间倒序，created_at 同毫秒按 id 分先后）；channel 给 null 表示不过滤 */
    public List<RecallRow> recent(int limit, String channel) {
        if (channel == null || channel.isBlank()) {
            return jdbc.query(
                    "SELECT " + COLS + " FROM rag_recall ORDER BY created_at DESC, id DESC LIMIT ?",
                    RagRecallStore::map, limit);
        }
        return jdbc.query(
                "SELECT " + COLS + " FROM rag_recall WHERE channel = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                RagRecallStore::map, channel, limit);
    }

    /** 清空留痕，返回删除条数 */
    public int clear() {
        return jdbc.update("DELETE FROM rag_recall");
    }

    private static RecallRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RecallRow(rs.getLong("id"), rs.getString("channel"), rs.getString("tool"),
                rs.getString("source"), rs.getString("query"), rs.getString("result"),
                rs.getLong("result_chars"), rs.getLong("created_at"));
    }
}
