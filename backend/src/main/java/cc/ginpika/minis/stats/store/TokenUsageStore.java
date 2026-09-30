package cc.ginpika.minis.stats.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * LLM token 用量留痕（token_usage）：一次调用一行，供统计页做总量 / 分来源 / 分模型 / 按日聚合。
 * 写入量小（每次 LLM 调用一条），因此只建最基础的两条索引。
 */
@Repository
public class TokenUsageStore {

    /** 一个维度（来源 / 模型 / 日期）的汇总行；key 为该维度的取值 */
    public record BucketRow(String key, long promptTokens, long completionTokens, long totalTokens,
                            long calls) {
    }

    /** 全部记录的合计 */
    public record TotalsRow(long promptTokens, long completionTokens, long totalTokens, long calls) {
    }

    private final JdbcTemplate jdbc;

    public TokenUsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String source, String model, long promptTokens, long completionTokens,
                       long totalTokens, boolean estimated) {
        jdbc.update(
                "INSERT INTO token_usage(source, model, prompt_tokens, completion_tokens, "
                        + "total_tokens, estimated, created_at) VALUES(?,?,?,?,?,?,?)",
                source, model, promptTokens, completionTokens, totalTokens,
                estimated ? 1 : 0, System.currentTimeMillis());
    }

    public TotalsRow totals() {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens),0) p, COALESCE(SUM(completion_tokens),0) c, "
                        + "COALESCE(SUM(total_tokens),0) t, COUNT(*) n FROM token_usage",
                TokenUsageStore::mapTotals);
    }

    /** 分来源汇总（token 由多到少） */
    public List<BucketRow> bySource() {
        return jdbc.query(
                "SELECT source AS key, SUM(prompt_tokens) p, SUM(completion_tokens) c, "
                        + "SUM(total_tokens) t, COUNT(*) n FROM token_usage "
                        + "GROUP BY source ORDER BY t DESC",
                TokenUsageStore::mapBucket);
    }

    /** 分模型汇总（token 由多到少） */
    public List<BucketRow> byModel() {
        return jdbc.query(
                "SELECT model AS key, SUM(prompt_tokens) p, SUM(completion_tokens) c, "
                        + "SUM(total_tokens) t, COUNT(*) n FROM token_usage "
                        + "GROUP BY model ORDER BY t DESC",
                TokenUsageStore::mapBucket);
    }

    /**
     * 按日汇总（本地时区，key=yyyy-MM-dd）：只取 since 起的记录；
     * 无记录的日期不出现在结果里（缺失日期由前端补零，保证趋势图 X 轴连续）。
     */
    public List<BucketRow> daily(long since) {
        return jdbc.query(
                "SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS key, "
                        + "SUM(prompt_tokens) p, SUM(completion_tokens) c, SUM(total_tokens) t, "
                        + "COUNT(*) n FROM token_usage WHERE created_at >= ? "
                        + "GROUP BY key ORDER BY key",
                TokenUsageStore::mapBucket, since);
    }

    private static TotalsRow mapTotals(ResultSet rs, int rowNum) throws SQLException {
        return new TotalsRow(rs.getLong("p"), rs.getLong("c"), rs.getLong("t"), rs.getLong("n"));
    }

    private static BucketRow mapBucket(ResultSet rs, int rowNum) throws SQLException {
        return new BucketRow(rs.getString("key"), rs.getLong("p"), rs.getLong("c"),
                rs.getLong("t"), rs.getLong("n"));
    }
}
