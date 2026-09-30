package cc.ginpika.minis.stats.dto;

import java.util.List;

/** 统计页（LLM token 消耗）出参。 */
public final class StatsDtos {

    /** 一个维度（来源 / 模型 / 日期）的汇总；key 为来源标识 / 模型名 / 日期（yyyy-MM-dd） */
    public record Bucket(String key, long promptTokens, long completionTokens, long totalTokens,
                         long calls) {
    }

    /** 全部记录的合计 */
    public record Totals(long promptTokens, long completionTokens, long totalTokens, long calls) {
    }

    /** 统计页数据：总量 + 分来源 + 分模型 + 最近 days 天的按日趋势 */
    public record StatsView(Totals total, List<Bucket> bySource, List<Bucket> byModel,
                            List<Bucket> daily, int days) {
    }

    private StatsDtos() {
    }
}
