package cc.ginpika.minis.ai.knowledge.web;

import com.microsoft.playwright.BrowserContext;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/** Bounded streaming downloads: never buffer an arbitrarily large media response. */
final class WebMediaArchive implements AutoCloseable {
    private final WebUrlPolicy policy;
    private final BrowserContext context;
    private final HttpClient client;
    private final String userAgent, language, referer;
    private int totalBytes, imageBytes;
    private record Archived(String data, int bytes) {}
    private final Map<String, Archived> downloaded = new HashMap<>();
    private final long started = System.nanoTime();

    WebMediaArchive(WebUrlPolicy policy, BrowserContext context, String proxy, String userAgent, String language, String pageUrl) {
        this.policy = policy; this.context = context; this.userAgent = userAgent; this.language = language;
        URI page = URI.create(pageUrl);
        referer = page.getScheme() + "://" + page.getRawAuthority() + "/";
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER);
        if (!proxy.isBlank()) {
            URI address = URI.create(proxy.contains("://") ? proxy : "http://" + proxy);
            if (!"http".equals(address.getScheme()) || address.getHost() == null) throw new IllegalArgumentException("离线媒体需要 HTTP 代理");
            builder.proxy(ProxySelector.of(new InetSocketAddress(address.getHost(), address.getPort() < 0 ? 80 : address.getPort())));
        }
        client = builder.build();
    }

    String download(String raw, Set<String> types, int maxBytes, boolean image) {
        if (raw == null || raw.isBlank()) return "";
        Archived cached = downloaded.get(raw);
        if (cached != null && SnapshotHtml.dataMedia(cached.data(), types) && cached.bytes() <= maxBytes) return cached.data();
        if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(60)) return "";
        int limit = Math.min(maxBytes, 80_000_000 - totalBytes);
        if (image) limit = Math.min(limit, 64_000_000 - imageBytes);
        if (limit <= 0) return "";
        try {
            if (SnapshotHtml.dataMedia(raw, types)) {
                if (raw.length() > (long)limit * 4 / 3 + 100) return "";
                byte[] data = Base64.getDecoder().decode(raw.substring(raw.indexOf(";base64,") + 8));
                if (data.length > limit) return "";
                totalBytes += data.length; if (image) imageBytes += data.length;
                downloaded.put(raw, new Archived(raw, data.length));
                return raw;
            }
            String url = raw;
            for (int redirect = 0; redirect <= 3; redirect++) {
                URI uri = URI.create(policy.validate(url));
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                        .header("User-Agent", userAgent).header("Accept-Language", language)
                        .header("Referer", referer).header("Accept", String.join(",", types));
                String cookies = context.cookies(uri.toString()).stream().map(c -> c.name + "=" + c.value).collect(Collectors.joining("; "));
                if (!cookies.isBlank()) request.header("Cookie", cookies);
                final int bound = limit;
                CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request.GET().build(), info -> {
                    if (info.statusCode() >= 300 && info.statusCode() < 400) return bounded(4096);
                    String type = info.headers().firstValue("content-type").orElse("").split(";")[0].strip().toLowerCase(Locale.ROOT);
                    if (info.statusCode() != 200 || !types.contains(type)
                            || info.headers().firstValueAsLong("content-length").orElse(0) > bound)
                        throw new IllegalArgumentException("媒体不可获取、格式不支持或超过大小限制");
                    return bounded(bound);
                });
                HttpResponse<byte[]> response;
                try { response = pending.get(4, TimeUnit.SECONDS); }
                catch (Exception e) { pending.cancel(true); throw e; }
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    url = uri.resolve(response.headers().firstValue("location").orElseThrow()).toString();
                    continue; // validate each hop; cookies are scoped to the new destination
                }
                byte[] data = response.body();
                if (data.length == 0) return "";
                totalBytes += data.length; if (image) imageBytes += data.length;
                String type = response.headers().firstValue("content-type").orElseThrow().split(";")[0].strip().toLowerCase(Locale.ROOT);
                String embedded = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(data);
                downloaded.put(raw, new Archived(embedded, data.length));
                return embedded;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* sanitizer provides a visible missing-resource warning */ }
        return "";
    }

    private static HttpResponse.BodySubscriber<byte[]> bounded(int limit) {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private int received;
            private boolean done;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; delegate.onSubscribe(subscription); }
            public void onNext(List<ByteBuffer> buffers) {
                if (done) return;
                for (ByteBuffer buffer : buffers) {
                    if (buffer.remaining() > limit - received) {
                        done = true; subscription.cancel(); delegate.onError(new IOException("媒体超过大小限制")); return;
                    }
                    received += buffer.remaining();
                }
                delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
            public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
        };
    }

    public void close() { client.shutdownNow(); }
}
