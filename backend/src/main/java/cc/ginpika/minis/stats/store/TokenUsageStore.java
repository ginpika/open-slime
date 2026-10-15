package cc.ginpika.minis.stats.store;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TokenUsageStore {
    public record PriceRow(long id, String provider, String model, long effectiveFrom,
                           String currency, BigDecimal input, BigDecimal output, BigDecimal cached) {}
    public record UsageRow(long id, long createdAt, String source, String provider, String model,
                           long promptTokens, long completionTokens, long totalTokens,
                           String quality, String status, long cachedTokens, long reasoningTokens) {}
    public record Aggregate(String day, String source, String provider, String model,
                            String quality, String status, long promptTokens, long completionTokens,
                            long totalTokens, long calls, long cachedTokens, long reasoningTokens,
                            PriceRow price) {}

    private final JdbcTemplate jdbc;
    public TokenUsageStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(String source, String provider, String model, long prompt, long completion,
                       long total, boolean estimated, String quality, String status,
                       long cached, long reasoning) {
        jdbc.update("INSERT INTO token_usage(source,provider,model,prompt_tokens,completion_tokens,"
                + "total_tokens,estimated,quality,status,cached_tokens,reasoning_tokens,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", source, provider, model, prompt, completion,
                total, estimated ? 1 : 0, quality, status, cached, reasoning, System.currentTimeMillis());
    }

    private static final String QUALITY = "COALESCE(u.quality,CASE WHEN u.estimated=1 THEN 'estimated' "
            + "WHEN u.total_tokens != u.prompt_tokens+u.completion_tokens THEN 'partial' ELSE 'reported' END)";
    private static final String STATUS = "COALESCE(u.status,'success')";
    private static final String PRICE_JOIN = " LEFT JOIN token_price p ON p.id=(SELECT p2.id FROM token_price p2"
            + " WHERE p2.provider=COALESCE(u.provider,'') AND p2.model=u.model AND p2.effective_from<=u.created_at"
            + " ORDER BY p2.effective_from DESC LIMIT 1) ";
    private static final String PRICE_COLUMNS = "p.id price_id,p.provider price_provider,p.model price_model,"
            + "p.effective_from,p.currency,p.input_per_million,p.output_per_million,p.cached_per_million";

    /** Java 生成每天的边界，避开 SQLite localtime 与 JVM/浏览器时区差异和 DST。 */
    public List<Aggregate> aggregates(LocalDate from, LocalDate to, ZoneId zone) {
        var args = new ArrayList<Object>();
        var dayCase = new StringBuilder("CASE ");
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            dayCase.append("WHEN u.created_at < ? THEN '").append(day).append("' ");
            args.add(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        }
        dayCase.append("END");
        args.add(from.atStartOfDay(zone).toInstant().toEpochMilli());
        args.add(to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
        return jdbc.query("SELECT " + dayCase + " day,u.source,COALESCE(u.provider,'') provider,u.model,"
                + QUALITY + " quality," + STATUS + " status,SUM(u.prompt_tokens) prompt,"
                + "SUM(u.completion_tokens) completion,SUM(u.total_tokens) total,COUNT(*) calls,"
                + "SUM(COALESCE(u.cached_tokens,0)) cached,SUM(COALESCE(u.reasoning_tokens,0)) reasoning,"
                + PRICE_COLUMNS + " FROM token_usage u " + PRICE_JOIN
                + "WHERE u.created_at>=? AND u.created_at<? "
                + "GROUP BY day,u.source,COALESCE(u.provider,''),u.model," + QUALITY + "," + STATUS + ",p.id,"
                + "CASE WHEN COALESCE(u.cached_tokens,0)>0 THEN 1 ELSE 0 END "
                + "ORDER BY day,u.source,u.model", (rs, n) -> new Aggregate(rs.getString("day"),
                rs.getString("source"), rs.getString("provider"), rs.getString("model"),
                rs.getString("quality"), rs.getString("status"), rs.getLong("prompt"),
                rs.getLong("completion"), rs.getLong("total"), rs.getLong("calls"),
                rs.getLong("cached"), rs.getLong("reasoning"), price(rs)), args.toArray());
    }

    public List<UsageRow> recent(long from, long until, String source, String provider, String model) {
        var args = new ArrayList<Object>(List.of(from, until));
        var where = new StringBuilder(" WHERE u.created_at>=? AND u.created_at<?");
        if (source != null) { where.append(" AND u.source=?"); args.add(source); }
        if (model != null) {
            where.append(" AND u.model=? AND COALESCE(u.provider,'')=?");
            args.add(model); args.add(provider == null ? "" : provider);
        }
        return jdbc.query("SELECT u.*," + QUALITY + " resolved_quality," + STATUS + " resolved_status"
                + " FROM token_usage u" + where + " ORDER BY u.created_at DESC,u.id DESC LIMIT 100",
                (rs, n) -> new UsageRow(rs.getLong("id"), rs.getLong("created_at"), rs.getString("source"),
                        rs.getString("provider") == null ? "" : rs.getString("provider"), rs.getString("model"),
                        rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("total_tokens"),
                        rs.getString("resolved_quality"), rs.getString("resolved_status"),
                        rs.getLong("cached_tokens"), rs.getLong("reasoning_tokens")), args.toArray());
    }

    public List<PriceRow> prices() {
        return jdbc.query("SELECT id price_id,provider price_provider,model price_model,effective_from,"
                + "currency,input_per_million,output_per_million,cached_per_million FROM token_price"
                + " ORDER BY effective_from DESC,id DESC", (rs, n) -> price(rs));
    }

    /** 更正同一生效时刻的价格；其他生效时间的版本保持独立。 */
    public void savePrice(String provider, String model, long effectiveFrom, String currency,
                          BigDecimal input, BigDecimal output, BigDecimal cached) {
        jdbc.update("INSERT INTO token_price(provider,model,effective_from,currency,input_per_million,"
                + "output_per_million,cached_per_million) VALUES(?,?,?,?,?,?,?) "
                + "ON CONFLICT(provider,model,effective_from) DO UPDATE SET currency=excluded.currency,"
                + "input_per_million=excluded.input_per_million,output_per_million=excluded.output_per_million,"
                + "cached_per_million=excluded.cached_per_million", provider, model, effectiveFrom,
                currency, input.toPlainString(), output.toPlainString(), cached == null ? null : cached.toPlainString());
    }

    public boolean deletePrice(long id) {
        return jdbc.update("DELETE FROM token_price WHERE id=?",id)>0;
    }

    private static PriceRow price(ResultSet rs) throws SQLException {
        long id = rs.getLong("price_id");
        if (rs.wasNull()) return null;
        String cached = rs.getString("cached_per_million");
        return new PriceRow(id, rs.getString("price_provider"), rs.getString("price_model"),
                rs.getLong("effective_from"), rs.getString("currency"),
                new BigDecimal(rs.getString("input_per_million")),
                new BigDecimal(rs.getString("output_per_million")), cached == null ? null : new BigDecimal(cached));
    }
}
