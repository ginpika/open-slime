package cc.ginpika.minis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Official account snapshots only. Credentials and upstream error bodies never enter the view/cache. */
@Service
public class DeepSeekBalanceService {
    public record Balance(String currency, String totalBalance, String grantedBalance, String toppedUpBalance) {}
    public record View(String status, Boolean available, List<Balance> balances,
                       Long checkedAt, Long attemptedAt, String error) {}
    private static final String ENDPOINT = "https://api.deepseek.com/user/balance";
    private final ObjectMapper json;
    private final OkHttpClient http;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private static final class Entry { private View view; }

    @Autowired
    public DeepSeekBalanceService(ObjectMapper json) {
        this(json, new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build(), Clock.systemUTC());
    }

    DeepSeekBalanceService(ObjectMapper json, OkHttpClient http, Clock clock) {
        this.json = json;
        this.http = http;
        this.clock = clock;
    }

    public static boolean official(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl.strip());
            String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
            return "https".equalsIgnoreCase(uri.getScheme()) && "api.deepseek.com".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (path.isEmpty() || path.equals("/v1"));
        } catch (IllegalArgumentException e) { return false; }
    }

    public View balance(String baseUrl, String apiKey, boolean refresh) {
        if (!official(baseUrl)) return new View("unsupported", null, List.of(), null, null, "仅支持 DeepSeek 官方 HTTPS 端点");
        if (apiKey == null || apiKey.isBlank()) return new View("unconfigured", null, List.of(), null, null, "请先保存 API Key");
        String key = apiKey.strip();
        if (!key.chars().allMatch(c -> c >= 33 && c < 127))
            return new View("error", null, List.of(), null, clock.millis(), "API Key 格式无效，请检查已保存配置");
        Entry entry = entry(key);
        // Coalesce the same account across model rows, Search/Fetch, and concurrent tabs.
        synchronized (entry) {
            View previous = entry.view;
            long now = clock.millis();
            long ttl = refresh ? 3_000 : previous != null && previous.status().equals("error") ? 10_000 : 25_000;
            if (previous != null && now - previous.attemptedAt() < ttl) return previous;
            try {
                Request request = new Request.Builder().url(ENDPOINT).header("Authorization", "Bearer " + key).get().build();
                try (var response = http.newCall(request).execute()) {
                    if (response.code() != 200) throw new BalanceFailure(httpError(response.code()));
                    if (response.body() == null) throw new BalanceFailure("官方余额响应为空");
                    byte[] bytes = response.body().byteStream().readNBytes(65_537);
                    if (bytes.length > 65_536) throw new BalanceFailure("官方余额响应格式无效");
                    JsonNode root = json.readTree(bytes);
                    if (root == null || !root.path("is_available").isBoolean() || !root.path("balance_infos").isArray()
                            || root.path("balance_infos").isEmpty()) throw new BalanceFailure("官方余额响应格式无效");
                    List<Balance> balances = new ArrayList<>();
                    for (JsonNode item : root.path("balance_infos")) {
                        String currency = item.path("currency").asText();
                        if (!currency.equals("CNY") && !currency.equals("USD")) throw new BalanceFailure("官方余额响应币种无效");
                        balances.add(new Balance(currency, amount(item, "total_balance"), amount(item, "granted_balance"), amount(item, "topped_up_balance")));
                    }
                    long finished = clock.millis();
                    return entry.view = new View("ready", root.path("is_available").asBoolean(), List.copyOf(balances), finished, finished, null);
                }
            } catch (BalanceFailure e) {
                return entry.view = failed(previous, e.getMessage());
            } catch (IOException | IllegalArgumentException e) {
                return entry.view = failed(previous, "余额查询网络失败、超时或响应无效，请稍后刷新");
            }
        }
    }

    private View failed(View previous, String error) {
        return new View("error", previous == null ? null : previous.available(), previous == null ? List.of() : previous.balances(),
                previous == null ? null : previous.checkedAt(), clock.millis(), error);
    }

    private synchronized Entry entry(String apiKey) {
        String fingerprint;
        try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        Entry result = cache.computeIfAbsent(fingerprint, ignored -> new Entry());
        if (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
        return result;
    }

    private static String amount(JsonNode item, String field) {
        JsonNode node = item.path(field);
        if (!node.isTextual() || node.asText().length() > 64) throw new BalanceFailure("官方余额响应金额无效");
        String raw = node.asText();
        if (!raw.matches("-?\\d+(\\.\\d+)?")) throw new BalanceFailure("官方余额响应金额无效");
        return new BigDecimal(raw).toPlainString();
    }

    private static String httpError(int status) {
        return "DeepSeek 余额查询 HTTP " + status + "：" + switch (status) {
            case 401 -> "API Key 无效，请检查已保存配置";
            case 402 -> "账户余额不足，请前往官方控制台核对";
            case 403 -> "无权查询账户余额";
            case 429 -> "请求过于频繁，请稍后刷新";
            default -> "官方服务暂时无法查询，请稍后刷新";
        };
    }
    private static final class BalanceFailure extends RuntimeException {
        private BalanceFailure(String message) { super(message); }
    }
}
