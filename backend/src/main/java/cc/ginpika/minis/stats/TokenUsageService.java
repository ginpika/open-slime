package cc.ginpika.minis.stats;

import cc.ginpika.minis.stats.dto.StatsDtos;
import cc.ginpika.minis.stats.store.TokenUsageStore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM token 用量统计：写入留痕（各客户端调用）+ 聚合视图（统计页）。
 *
 * <p>一次 LLM 调用记一行，来源标签由调用方给出（见 SOURCE_* 常量）。真实用量来自供应商回传的
 * usage；未回传时用 {@link #estimateTokens(String)} 本地估算兜底（estimated=true 便于区分）。
 * 落库失败只告警不抛出：统计是旁路，不能影响正常对话/检索。
 */
@Service
public class TokenUsageService {

    /* ---------- 来源标签（写入 token_usage.source，前端映射中文名） ---------- */

    /** 聊天对话 */
    public static final String SOURCE_CHAT = "chat";
    /** 文档分析（首轮提取） */
    public static final String SOURCE_ANALYZE = "analyze";
    /** 文档分析（提交确认轮） */
    public static final String SOURCE_COMMIT = "commit";
    /** 文档摘要 */
    public static final String SOURCE_DOC_SUMMARY = "doc-summary";
    /** 分块问题生成 */
    public static final String SOURCE_CHUNK_QUESTIONS = "chunk-questions";
    /** 分块摘要 */
    public static final String SOURCE_CHUNK_SUMMARY = "chunk-summary";
    /** 长文切分 */
    public static final String SOURCE_CHUNKER = "chunker";
    /** 潜意识子代理 */
    public static final String SOURCE_HEARTBEAT = "heartbeat";
    /** 向量化 */
    public static final String SOURCE_EMBEDDING = "embedding";
    /** 精排 */
    public static final String SOURCE_RERANK = "rerank";
    /** 设置页连通性测试 */
    public static final String SOURCE_MODEL_TEST = "model-test";

    /** 趋势图默认天数 */
    private static final int DEFAULT_DAYS = 30;
    /** 趋势图天数上限 */
    private static final int MAX_DAYS = 365;

    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    /**
     * 一次调用的用量；estimated=数字为本地估算（供应商未回传 usage）。
     * 供应商只回传部分字段时，缺的按 0 处理、total 兜算。
     */
    public record Usage(long promptTokens, long completionTokens, long totalTokens,
                        boolean estimated) {

        /** 供应商回传的真实用量 */
        public static Usage real(long promptTokens, long completionTokens, long totalTokens) {
            long total = totalTokens > 0 ? totalTokens : promptTokens + completionTokens;
            return new Usage(promptTokens, completionTokens, total, false);
        }

        /** 本地估算（输入/输出分别为估算 token 数） */
        public static Usage estimated(long promptTokens, long completionTokens) {
            return new Usage(promptTokens, completionTokens, promptTokens + completionTokens, true);
        }
    }

    private final TokenUsageStore store;

    public TokenUsageService(TokenUsageStore store) {
        this.store = store;
    }

    /** 记录一次调用（失败只告警） */
    public void record(String source, String model, Usage usage) {
        if (usage == null) {
            return;
        }
        try {
            store.insert(source, model == null ? "" : model, usage.promptTokens(),
                    usage.completionTokens(), usage.totalTokens(), usage.estimated());
        } catch (Exception e) {
            log.warn("token 用量落库失败：source={} model={} error={}", source, model, e.getMessage());
        }
    }

    /** 统计页数据：总量 + 分来源 + 分模型 + 最近 days 天按日趋势 */
    public StatsDtos.StatsView view(int days) {
        int d = clampDays(days);
        /* since 取 days 天前的零点（本地时区），与 SQLite 'localtime' 分组口径一致 */
        long since = LocalDate.now().minusDays(d - 1L)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new StatsDtos.StatsView(toTotals(store.totals()), toBuckets(store.bySource()),
                toBuckets(store.byModel()), toBuckets(store.daily(since)), d);
    }

    /**
     * 估算文本 token 数：CJK（U+3400–U+9FFF）1 字 ≈ 1 token，其余约 4 字符 1 token。
     * 与前端 estimateTokens 同构，仅用于供应商未回传 usage 时的兜底。
     */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + (long) Math.ceil((text.length() - cjk) / 4.0);
    }

    private static int clampDays(int days) {
        if (days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static StatsDtos.Totals toTotals(TokenUsageStore.TotalsRow row) {
        return new StatsDtos.Totals(row.promptTokens(), row.completionTokens(), row.totalTokens(),
                row.calls());
    }

    private static List<StatsDtos.Bucket> toBuckets(List<TokenUsageStore.BucketRow> rows) {
        return rows.stream()
                .map(r -> new StatsDtos.Bucket(r.key(), r.promptTokens(), r.completionTokens(),
                        r.totalTokens(), r.calls()))
                .toList();
    }
}
