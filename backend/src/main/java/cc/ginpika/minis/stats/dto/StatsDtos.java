package cc.ginpika.minis.stats.dto;

import java.math.BigDecimal;
import java.util.List;

/** 用量统计与费用试算；金额按币种分别汇总，永不自动换汇。 */
public final class StatsDtos {
    public record Money(String currency, BigDecimal amount) {}
    public record Totals(long promptTokens, long completionTokens, long totalTokens, long calls,
                         long reportedCalls, long estimatedCalls, long partialCalls,
                         long unsuccessfulCalls, long cachedTokens, long reasoningTokens,
                         long unpricedCalls, List<Money> costs) {}
    public record Bucket(String key, String provider, String model, long promptTokens,
                         long completionTokens, long totalTokens, long calls, long unpricedCalls,
                         List<Money> costs) {}
    public record Model(String provider, String model) {}
    public record Price(long id, String provider, String model, String effectiveFrom,
                        String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                        BigDecimal cachedPerMillion) {}
    public record PriceRequest(String provider, String model, String effectiveFrom,
                               String currency, BigDecimal inputPerMillion,
                               BigDecimal outputPerMillion, BigDecimal cachedPerMillion) {}
    public record Call(long id, long createdAt, String source, String provider, String model,
                       long promptTokens, long completionTokens, long totalTokens,
                       String quality, String status, long cachedTokens, long reasoningTokens,
                       String currency, BigDecimal cost) {}
    public record StatsView(Totals total, List<Bucket> bySource, List<Bucket> byModel,
                            List<Bucket> daily, int days, String from, String to,
                            String timeZone, String today, long generatedAt,
                            List<String> sources, List<Model> models, List<Price> prices,
                            List<Call> recentCalls, long recordingFailures) {}
    private StatsDtos() {}
}
