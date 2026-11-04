package cc.ginpika.minis.ai.computer.browser;

import cc.ginpika.minis.config.DesktopProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Authenticated local extension transport. The extension keeps cookies and login state inside Chrome. */
@Service
public class ChromeBridge {
    private final DesktopProps desktop;
    private final ObjectMapper json;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private String secret;
    private long pairUntil;
    private String connection;
    private Consumer<String> sender;
    private Runnable closer;
    public ChromeBridge(DesktopProps desktop, ObjectMapper json) { this.desktop = desktop; this.json = json; }

    public synchronized String pair() {
        if (!desktopLocal()) throw new IllegalStateException("连接用户 Chrome 需要带本机令牌的桌面端本机模式");
        disconnect();
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        pairUntil = System.currentTimeMillis() + 10 * 60_000;
        return secret;
    }
    public synchronized boolean accepts(String token) {
        return desktopLocal() && secret != null && token != null
                && System.currentTimeMillis() < pairUntil
                && MessageDigest.isEqual(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public synchronized boolean connected() { return sender != null; }
    public boolean desktopLocal() { return desktop.isEnabled() && !desktop.getLocalToken().isBlank(); }
    public synchronized void attach(String id, String token, Consumer<String> send, Runnable close) {
        if (!accepts(token) || sender != null) throw new IllegalArgumentException("Chrome 连接码失效或已有扩展连接");
        connection = id; sender = send; closer = close;
        // Reconnects use the same app-session credential; a new pairing or restart revokes it.
        pairUntil = Long.MAX_VALUE;
    }
    public synchronized void detach(String id) {
        if (!java.util.Objects.equals(connection, id)) return;
        sender = null; connection = null; closer = null;
        failPending("Chrome 扩展连接已断开，操作结果未知；重新观察页面后再继续");
    }
    public synchronized void disconnect() {
        Runnable close = closer;
        secret = null; sender = null; connection = null; closer = null;
        failPending("Chrome 连接已撤销");
        if (close != null) close.run();
    }
    private void failPending(String message) {
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException(message)));
        pending.clear();
    }
    public void receive(String connectionId, JsonNode response) {
        synchronized (this) { if (!java.util.Objects.equals(connection, connectionId)) return; }
        var future = pending.remove(response.path("id").asText());
        if (future == null) return;
        if (response.hasNonNull("error")) future.completeExceptionally(new IllegalStateException(response.path("error").asText()));
        else future.complete(response.path("result"));
    }
    public JsonNode call(String scope, JsonNode command) {
        String id = UUID.randomUUID().toString();
        var result = new CompletableFuture<JsonNode>();
        synchronized (this) {
            if (sender == null) throw new IllegalStateException("用户 Chrome 未连接，请在工作空间页连接扩展；需要登录态时不能改用隔离浏览器");
            if (pending.size() >= 16) throw new IllegalStateException("Chrome 操作队列已满");
            pending.put(id, result);
            try { sender.accept(json.createObjectNode().put("id", id).put("scope", scope)
                    .put("deadline", System.currentTimeMillis() + 30_000).set("command", command).toString()); }
            catch (RuntimeException error) { pending.remove(id); throw new IllegalStateException("Chrome 连接已断开，操作结果未知，请重新观察", error); }
        }
        try { return result.get(35, TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("Chrome 操作已停止；已发送的操作可能已生效，请重新观察"); }
        catch (java.util.concurrent.TimeoutException error) { throw new IllegalStateException("Chrome 操作超时，结果未知；请先 read / list 重新观察，不要直接重试写入操作"); }
        catch (java.util.concurrent.ExecutionException error) { throw new IllegalStateException(error.getCause().getMessage(), error.getCause()); }
        finally { pending.remove(id); }
    }
}
