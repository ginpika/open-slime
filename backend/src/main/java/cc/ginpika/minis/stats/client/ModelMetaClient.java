package cc.ginpika.minis.stats.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型元信息客户端：GET {base}/models，尽力解析模型的上下文窗口。
 *
 * <p>OpenAI 官方 /models 不返回上下文长度；部分供应商会附带，字段名各异
 * （OpenRouter: context_length、vLLM: max_model_len、部分网关: context_window /
 * max_context_length）。本类逐个尝试，取不到返回 null，由调用方兜底默认值。
 */
@Component
public class ModelMetaClient {

    private static final Logger log = LoggerFactory.getLogger(ModelMetaClient.class);

    /** 上下文窗口候选字段（按优先级） */
    private static final List<String> CONTEXT_FIELDS =
            List.of("context_window", "context_length", "max_context_length", "max_model_len");

    private final ObjectMapper objectMapper;
    private final OkHttpClient client;

    public ModelMetaClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /** 尽力获取模型的上下文窗口（token 数）；接口不返回或调用失败时返回 null */
    public Integer fetchContextWindow(String baseUrl, String apiKey, String model) {
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return null;
        }
        try {
            Request request = new Request.Builder()
                    .url(join(baseUrl, "models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.debug("模型元信息获取失败 HTTP {}: {}", response.code(), model);
                    return null;
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                return extract(root.path("data"), model);
            }
        } catch (IOException e) {
            log.debug("模型元信息获取失败 {}: {}", model, e.getMessage());
            return null;
        }
    }

    /** 在 data[] 中定位 model 条目并取上下文窗口；无匹配且仅一条时用该条 */
    private static Integer extract(JsonNode data, String model) {
        if (!data.isArray() || data.isEmpty()) {
            return null;
        }
        JsonNode hit = null;
        for (JsonNode node : data) {
            if (model.equalsIgnoreCase(node.path("id").asText(""))) {
                hit = node;
                break;
            }
        }
        if (hit == null && data.size() == 1) {
            hit = data.get(0);
        }
        return hit == null ? null : readContext(hit);
    }

    /** 按候选字段读取正整数；额外兼容 OpenRouter 的 top_provider.context_length */
    private static Integer readContext(JsonNode node) {
        for (String field : CONTEXT_FIELDS) {
            int v = node.path(field).asInt(0);
            if (v > 0) {
                return v;
            }
        }
        int nested = node.path("top_provider").path("context_length").asInt(0);
        return nested > 0 ? nested : null;
    }

    private static String join(String base, String path) {
        return (base.endsWith("/") ? base : base + "/") + path;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
