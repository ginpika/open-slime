package cc.ginpika.minis.config;

import cc.ginpika.minis.config.store.DeepSeekWebStore;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Search/Fetch credentials are independent of the selected chat model. Views never return the key. */
@Service
public class DeepSeekWebSettings {
    public record Config(String baseUrl, String apiKey, boolean fastEnabled, boolean ragEnabled,
                         boolean fetchEnabled, int count, int timeoutSeconds) {
        public boolean configured() { return !apiKey.isBlank(); }
        @Override public String toString() { return "DeepSeekWebConfig[credentials redacted]"; }
    }
    public record View(String baseUrl, boolean hasApiKey, boolean fastEnabled, boolean ragEnabled,
                       boolean fetchEnabled, int count, int timeoutSeconds) {}
    /** null key preserves the stored key; an explicit empty key clears it. */
    public record Draft(String baseUrl, String apiKey, boolean fastEnabled, boolean ragEnabled,
                        boolean fetchEnabled, int count, int timeoutSeconds) {
        @Override public String toString() { return "DeepSeekWebDraft[credentials redacted]"; }
    }
    private final DeepSeekWebStore store;
    public DeepSeekWebSettings(DeepSeekWebStore store) { this.store = store; }
    public Config resolve() {
        return store.read().orElse(new Config("https://api.deepseek.com", "", false, false, true, 5, 15));
    }
    public View view() { return view(resolve()); }
    private static View view(Config c) {
        return new View(c.baseUrl(), c.configured(), c.fastEnabled(), c.ragEnabled(), c.fetchEnabled(), c.count(), c.timeoutSeconds());
    }
    public Config validate(Draft d, boolean requireKey) {
        require(d != null, "请填写 DeepSeek Search / Fetch 配置");
        String base = d.baseUrl() == null ? "" : d.baseUrl().strip().replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            require(("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getPort() <= 65535 && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null, "请输入有效的 HTTP(S) API 根地址");
        } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 HTTP(S) API 根地址"); }
        String key = d.apiKey() == null ? resolve().apiKey() : d.apiKey().strip();
        require(key.chars().allMatch(c -> c >= 33 && c < 127), "API Key 不能包含空白或非 ASCII 字符");
        require(!(requireKey || d.fastEnabled() || d.ragEnabled()) || !key.isBlank(), "请先填写 DeepSeek API Key");
        require(d.count() >= 1 && d.count() <= 50, "搜索条数必须在 1 到 50 之间");
        require(d.timeoutSeconds() >= 2 && d.timeoutSeconds() <= 60, "超时必须在 2 到 60 秒之间");
        return new Config(base, key, d.fastEnabled(), d.ragEnabled(), d.fetchEnabled(), d.count(), d.timeoutSeconds());
    }
    public View save(Draft d) { Config c = validate(d, false); store.save(c); return view(c); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
