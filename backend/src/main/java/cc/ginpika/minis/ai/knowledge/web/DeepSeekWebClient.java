package cc.ginpika.minis.ai.knowledge.web;

import cc.ginpika.minis.ai.knowledge.dto.SearchDtos.WebResult;
import cc.ginpika.minis.config.DeepSeekWebSettings.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Component;

/** Raw JSON tools API: no model parameter and no fabricated token usage. */
@Component
public class DeepSeekWebClient {
    public record Page(String title, String url, String content) {}
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final ObjectMapper json;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    // Search and Fetch each have an account-wide 5 QPS limit, independent of API key.
    private final RateGate searches = new RateGate(), fetches = new RateGate();
    public DeepSeekWebClient(ObjectMapper json) { this.json = json; }

    public List<WebResult> search(Config c, String query, int count, boolean content) {
        String q = query == null ? "" : query.strip();
        if (q.isBlank() || q.codePointCount(0, q.length()) > 128) throw new IllegalArgumentException("搜索词需为 1 到 128 个 Unicode 字符");
        if (count < 1 || count > 50) throw new IllegalArgumentException("搜索条数必须在 1 到 50 之间");
        JsonNode root = post(c, "search", Map.of("query", q, "count", count, "need_content", content), searches);
        if (!root.path("results").isArray()) throw new IllegalStateException("DeepSeek Search 响应缺少 results 数组");
        Map<String, WebResult> results = new LinkedHashMap<>();
        for (JsonNode item : root.path("results")) {
            if (results.size() >= count) break;
            String url;
            try { url = publicUrl(item.path("url").asText("")); }
            catch (IllegalArgumentException ignored) { continue; }
            results.putIfAbsent(url, new WebResult(text(item, "title", 500), url, text(item, "snippet", 1000),
                    text(item, "summary", 4000), content ? text(item, "content", 20_000) : "",
                    text(item, "site_name", 200), text(item, "date", 100), item.path("rerank_score").asDouble(0),
                    item.path("authority").asInt(-1)));
        }
        return List.copyOf(results.values());
    }
    public Page fetch(Config c, String url) {
        String safe = publicUrl(url);
        JsonNode root = post(c, "fetch", Map.of("url", safe), fetches);
        String content = text(root, "content", 100_000);
        if (content.isBlank()) throw new IllegalStateException("DeepSeek Fetch 未返回可用正文");
        return new Page(text(root, "title", 500), safe, content);
    }
    public static String publicUrl(String raw) {
        try {
            URI uri = URI.create(raw == null ? "" : raw.strip());
            if (uri.toString().length() > 8192 || uri.getHost() == null || uri.getRawUserInfo() != null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException();
            return uri.normalize().toASCIIString();
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("请输入不含用户名和密码的 HTTP(S) 网页地址（最多 8192 字符）"); }
    }
    private JsonNode post(Config c, String tool, Map<String, Object> body, RateGate gate) {
        if (!c.configured()) throw new IllegalStateException("尚未配置 DeepSeek API Key");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(c.timeoutSeconds());
        String base = c.baseUrl().replaceAll("/+$", "");
        String endpoint = base + (base.endsWith("/v1") ? "" : "/v1") + "/tools/" + tool;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                gate.await(deadline);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("timeout");
                Request request = new Request.Builder().url(endpoint).header("Authorization", "Bearer " + c.apiKey())
                        .post(RequestBody.create(json.writeValueAsString(body), JSON)).build();
                Call call = http.newCall(request);
                call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
                int status;
                try (Response response = call.execute()) {
                    status = response.code();
                    if (status == 200) {
                        byte[] bytes = response.body() == null ? new byte[0] : response.body().byteStream().readNBytes(4_000_001);
                        if (bytes.length > 4_000_000) throw new IllegalStateException("DeepSeek 响应超过 4 MB 限制");
                        JsonNode result = json.readTree(new String(bytes, StandardCharsets.UTF_8));
                        if (result == null || !result.isObject()) throw new IllegalStateException("DeepSeek 返回了无效 JSON 对象");
                        return result;
                    }
                }
                if (attempt == 0 && (status == 408 || status == 429 || status >= 500) && deadline - System.nanoTime() > TimeUnit.MILLISECONDS.toNanos(300)) {
                    Thread.sleep(250);
                    continue;
                }
                throw new IllegalStateException("DeepSeek " + tool + " HTTP " + status + "：" + reason(status));
            }
            throw new IllegalStateException("DeepSeek 请求失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek 请求已取消");
        } catch (IOException e) {
            // Do not reflect gateway bodies, request headers or URLs containing credentials into logs/UI.
            throw new IllegalStateException("DeepSeek " + tool + " 网络请求失败、超时或响应格式无效");
        }
    }
    private static String reason(int status) {
        return switch (status) {
            case 400 -> "请求参数无效"; case 401 -> "API Key 无效"; case 402 -> "账号余额不足";
            case 403 -> "抓取被安全策略拒绝"; case 404 -> "未获取到可用网页正文或接口不存在";
            case 408, 504 -> "请求超时"; case 429 -> "请求过于频繁，请稍后重试";
            default -> "服务请求失败";
        };
    }
    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        String s = value.isTextual() ? value.asText() : "";
        return s.length() <= max ? s : s.substring(0, max) + "\n[内容已截断]";
    }
    private static final class RateGate {
        private long next;
        synchronized void await(long deadline) throws InterruptedException, IOException {
            long now = System.nanoTime(), wait = Math.max(0, next - now);
            if (now + wait >= deadline) throw new IOException("rate limit timeout");
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(210);
        }
    }
}
