package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * OpenAI 兼容 Embedding 客户端（POST {base}/embeddings）。
 * base-url 需指向带版本前缀的端点，如 https://api.openai.com/v1。
 *
 * <p>未配置 base/key/model 时 embed() 返回 empty（keyword/图检索仍可用，向量层空跑）；
 * 已配置但调用失败直接抛错（灌入方应感知，不静默降级）。
 */
@Component
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final LlmProps props;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final TokenUsageService tokenUsage;
    private final OkHttpClient client;

    public EmbeddingClient(LlmProps props, SettingsService settings, ObjectMapper objectMapper,
                           TokenUsageService tokenUsage) {
        this.props = props;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.tokenUsage = tokenUsage;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(props.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 当前 Embedding 端点是否就绪（设置页配置或 .env 任一） */
    public boolean configured() {
        return settings.embeddingConfigured();
    }

    /** 当前 Embedding 模型名（未配置为空串） */
    public String model() {
        return settings.resolveEmbedding().model();
    }

    /** 按当前运行时 Embedding 配置向量化 */
    public Optional<float[]> embed(String text) {
        return embed(settings.resolveEmbedding(), text);
    }

    public Optional<float[]> embed(ModelTarget target, String text) {
        if (!target.configured()) {
            log.debug("embedding 未配置，跳过向量化");
            return Optional.empty();
        }
        try {
            Request request = new Request.Builder()
                    .url(join(target.baseUrl(), "embeddings"))
                    .post(RequestBody.create(
                            objectMapper.writeValueAsString(Map.of("model", target.model(), "input", text)),
                            JSON))
                    .header("Authorization", "Bearer " + target.apiKey())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("embedding HTTP " + response.code());
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode data = root.path("data");
                if (!data.isArray() || data.isEmpty()) {
                    throw new IOException("embedding 响应无 data 数组: " + root.toString());
                }
                JsonNode arr = data.get(0).path("embedding");
                float[] vec = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) {
                    vec[i] = (float) arr.get(i).asDouble();
                }
                recordUsage(target.model(), root.path("usage"), text);
                return Optional.of(vec);
            }
        } catch (IOException e) {
            throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /** 用量留痕：供应商回传 usage 用真实值（embedding 无输出 token），否则按输入文本估算 */
    private void recordUsage(String model, JsonNode usage, String text) {
        TokenUsageService.Usage u = null;
        if (usage.isObject() && (usage.has("prompt_tokens") || usage.has("total_tokens"))) {
            u = TokenUsageService.Usage.real(usage.path("prompt_tokens").asLong(), 0,
                    usage.path("total_tokens").asLong());
        }
        if (u == null) {
            u = TokenUsageService.Usage.estimated(TokenUsageService.estimateTokens(text), 0);
        }
        tokenUsage.record(TokenUsageService.SOURCE_EMBEDDING, model, u);
    }

    private static String join(String base, String path) {
        return (base.endsWith("/") ? base : base + "/") + path;
    }
}
