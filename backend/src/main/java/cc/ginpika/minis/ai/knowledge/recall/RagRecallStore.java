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
 * 不是可编辑的配置。消费方是各 Agent 的「RAG 召回」子页与全局观测台（回看 RAG 效果）。
 *
 * <p>每条留痕按 {@code agent_id} 归属到发起它的那个 Agent（读侧可按 Agent 过滤）。
 */
@Repository
public class RagRecallStore {

    /** 一条召回留痕：result 为回给模型的 tool 消息原文，resultChars 为截断前的原始字符数 */
    public record RecallRow(long id, String channel, String tool, String source, String agentId,
                            String query, String result, long resultChars, long createdAt) {
    }

    private static final String COLS =
            "id, channel, tool, source, agent_id, query, result, result_chars, created_at";

    private final JdbcTemplate jdbc;

    public RagRecallStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条留痕（agentId 可为空：归属不到的召回按 null 落库） */
    public void append(String channel, String tool, String source, String agentId, String query,
                       String result, long resultChars) {
        jdbc.update(
                "INSERT INTO rag_recall(channel, tool, source, agent_id, query, result, result_chars, "
                        + "created_at) VALUES(?,?,?,?,?,?,?,?)",
                channel, tool, source, agentId, query, result, resultChars,
                System.currentTimeMillis());
    }

    /**
     * 最近若干条（时间倒序，created_at 同毫秒按 id 分先后）。
     *
     * @param channel 只看某一路（vector / graph / memory）；null 表示不过滤
     * @param agentId 只看某个 Agent；null 表示全部（全局观测台）
     */
    public List<RecallRow> recent(int limit, String channel, String agentId) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM rag_recall WHERE 1=1");
        boolean byChannel = channel != null && !channel.isBlank();
        boolean byAgent = agentId != null && !agentId.isBlank();
        if (byChannel) {
            sql.append(" AND channel = ?");
        }
        if (byAgent) {
            sql.append(" AND agent_id = ?");
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        if (byChannel && byAgent) {
            return jdbc.query(sql.toString(), RagRecallStore::map, channel, agentId, limit);
        }
        if (byChannel) {
            return jdbc.query(sql.toString(), RagRecallStore::map, channel, limit);
        }
        if (byAgent) {
            return jdbc.query(sql.toString(), RagRecallStore::map, agentId, limit);
        }
        return jdbc.query(sql.toString(), RagRecallStore::map, limit);
    }

    /** 清空留痕，返回删除条数；agentId 非空时只清该 Agent 的留痕 */
    public int clear(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return jdbc.update("DELETE FROM rag_recall");
        }
        return jdbc.update("DELETE FROM rag_recall WHERE agent_id = ?", agentId);
    }

    private static RecallRow map(ResultSet rs, int rowNum) throws SQLException {
        return new RecallRow(rs.getLong("id"), rs.getString("channel"), rs.getString("tool"),
                rs.getString("source"), rs.getString("agent_id"), rs.getString("query"),
                rs.getString("result"), rs.getLong("result_chars"), rs.getLong("created_at"));
    }
}
