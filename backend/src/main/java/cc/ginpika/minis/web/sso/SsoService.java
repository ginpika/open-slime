package cc.ginpika.minis.web.sso;

import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.web.sso.store.UserStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * hostid cookie 域 SSO 校验。
 *
 * <p>事实来源（hostid 实码，见其 routes/sso.ts / index.ts）：
 * GET {server}/api/sso/session（带 sso_token cookie）→ {authenticated, user{id,username,nickname,email,role,avatarUrl}}；
 * POST {server}/api/sso/logout（带 cookie）→ 撤销会话；
 * 登录页 302：{server}/sso/login?redirect=…
 *
 * <p>缓存：按 token 正缓存，TTL 见 minis.sso.token-ttl（默认 5m）。
 */
@Service
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    private final SsoProps props;
    private final ObjectMapper objectMapper;
    private final UserStore userStore;
    private final OkHttpClient client = new OkHttpClient();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public SsoService(SsoProps props, ObjectMapper objectMapper, UserStore userStore) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.userStore = userStore;
    }

    /** server-url 为空 = 未接入 hostid（本地开发/直通，见 §9-3） */
    public boolean isEnabled() {
        return props.getServerUrl() != null && !props.getServerUrl().isBlank();
    }

    /** 校验登录态：优先缓存，miss 时调 hostid /api/sso/session */
    public Optional<SsoUser> session(String token) {
        Cached hit = cache.get(token);
        if (hit != null) {
            if (hit.expiresAt > System.currentTimeMillis()) {
                return Optional.of(hit.user);
            }
            cache.remove(token);
        }
        return remoteSession(token);
    }

    /** 撤销会话（服务端调用 hostid /api/sso/logout） */
    public void revoke(String token) {
        if (!isEnabled() || token == null || token.isBlank()) {
            return;
        }
        cache.remove(token);
        Request request = new Request.Builder()
                .url(props.getServerUrl() + "/api/sso/logout")
                .post(okhttp3.RequestBody.create(new byte[0], null))
                .header("Cookie", cookie(token))
                .build();
        try (Response ignored = execute(request)) {
            // 撤销结果无需处理
        } catch (IOException e) {
            log.warn("hostid logout failed: {}", e.getMessage());
        }
    }

    /** 构造浏览器登录跳转 URL */
    public String loginUrl(String currentUrl) {
        if (!isEnabled()) {
            return "";
        }
        String encoded = URLEncoder.encode(currentUrl, StandardCharsets.UTF_8);
        return props.getServerUrl() + "/sso/login?redirect=" + encoded;
    }

    private Optional<SsoUser> remoteSession(String token) {
        Request request = new Request.Builder()
                .url(props.getServerUrl() + "/api/sso/session")
                .header("Cookie", cookie(token))
                .get()
                .build();
        try (Response response = execute(request)) {
            if (response.body() == null) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            if (!root.path("authenticated").asBoolean(false)) {
                return Optional.empty();
            }
            SsoUser user = SsoUser.from(root.path("user"));
            userStore.upsert(user);
            cache.put(token, new Cached(user, System.currentTimeMillis() + ttlMillis()));
            return Optional.of(user);
        } catch (IOException e) {
            log.warn("hostid session check failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Response execute(Request request) throws IOException {
        Call call = client.newCall(request);
        return call.execute();
    }

    private String cookie(String token) {
        return props.getCookieName() + "=" + token;
    }

    private long ttlMillis() {
        Duration ttl = props.getTokenTtl();
        return ttl == null ? Duration.ofMinutes(5).toMillis() : ttl.toMillis();
    }

    private record Cached(SsoUser user, long expiresAt) {
    }
}
