package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.ai.knowledge.retrieval.RerankScorer;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 Rerank 客户端（POST {base}/rerank）。
 * base-url/api-key 与 LLM 同源，模型名取 RERANK_MODEL。
 *
 * <p>未配置时 score() 返回 empty（精排层空跑，检索仍返回 RRF 序）；
 * 已配置但调用失败抛错，由 {@link cc.ginpika.minis.ai.knowledge.retrieval.Reranker} 兜底回退。
 */
@Component
public class RerankClient implements RerankScorer {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 分数字段候选名：各供应商/网关命名不一，按序取首个数值字段 */
    private static final List<String> SCORE_FIELDS =
            List.of("relevance_score", "relevanceScore", "score");

    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final TokenUsageService tokenUsage;
    private final OkHttpClient client;

    public RerankClient(LlmProps props, SettingsService settings, ObjectMapper objectMapper,
                        TokenUsageService tokenUsage) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.tokenUsage = tokenUsage;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 当前 Rerank 端点是否就绪（RERANK_MODEL + LLM base-url/api-key） */
    public boolean configured() {
        return settings.resolveRerank().configured();
    }

    @Override
    public Optional<double[]> score(String query, List<String> documents) {
        ModelTarget target = settings.resolveRerank();
        if (!target.configured() || documents.isEmpty()) {
            log.debug("rerank 未配置（缺 RERANK_MODEL 或 base-url/api-key），跳过精排");
            return Optional.empty();
        }
        return score(target, query, documents);
    }

    /** 按指定端点打分（设置页测试按钮用草稿端点直连，不改动已保存配置） */
    public Optional<double[]> score(ModelTarget target, String query, List<String> documents) {
        return score(target, query, documents, TokenUsageService.SOURCE_RERANK);
    }

    public Optional<double[]> score(ModelTarget target, String query, List<String> documents, String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", target.model());
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_n", documents.size());
        boolean attempted = false, succeeded = false;
        TokenUsageService.Usage usage = null;
        try {
            Request request = new Request.Builder()
                    .url(join(target.baseUrl(), "rerank"))
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .header("Authorization", "Bearer " + target.apiKey())
                    .build();
            attempted = true;
            try (Response response = client.newCall(request).execute()) {
                String raw = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    throw new IOException("rerank HTTP " + response.code() + " " + abbreviate(raw));
                }
                JsonNode root = objectMapper.readTree(raw);
                usage = UsageParser.parse(root.path("usage"), true);
                var result = parse(root.path("results"), documents.size());
                succeeded = result.isPresent();
                return result;
            }
        } catch (IOException e) {
            throw new IllegalStateException("rerank 调用失败: " + e.getMessage(), e);
        } finally {
            if (attempted) {
                if (usage == null) {
                    long prompt = 0;
                    if (succeeded) {
                        prompt = TokenUsageService.estimateTokens(query);
                        for (String doc : documents) prompt += TokenUsageService.estimateTokens(doc);
                    }
                    usage = TokenUsageService.Usage.estimated(prompt, 0);
                }
                tokenUsage.record(source, target, succeeded ? usage : usage.partial(), succeeded ? "success" : "failed");
            }
        }
    }

    /** results[{index, relevance_score}] → 与 documents 对齐的分数组；字段缺失或 index 越界视为响应畸形 */
    private static Optional<double[]> parse(JsonNode results, int size) {
        if (!results.isArray() || results.isEmpty()) {
            log.warn("rerank 响应无 results 数组，回退 RRF 原序");
            return Optional.empty();
        }
        double[] scores = new double[size];
        Arrays.fill(scores, Double.NEGATIVE_INFINITY);
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            Double value = firstScore(item);
            if (index < 0 || index >= size || value == null) {
                log.warn("rerank 响应畸形（index={} 越界或缺分数字段），回退 RRF 原序", index);
                return Optional.empty();
            }
            scores[index] = value;
        }
        return Optional.of(scores);
    }

    /** 错误响应体压成单行并截断，便于日志定位（如模型名无效 / 接口不存在） */
    private static String abbreviate(String raw) {
        String t = raw.strip().replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    private static Double firstScore(JsonNode item) {
        for (String field : SCORE_FIELDS) {
            JsonNode v = item.get(field);
            if (v != null && v.isNumber()) {
                return v.asDouble();
            }
        }
        return null;
    }

    private static String join(String base, String path) {
        return (base.endsWith("/") ? base : base + "/") + path;
    }
}
