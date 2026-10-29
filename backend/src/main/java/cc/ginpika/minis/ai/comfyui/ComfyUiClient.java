package cc.ginpika.minis.ai.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 自托管 ComfyUI 的 HTTP 协议；提交不自动重试，避免网络故障时重复生成。 */
@Component
public class ComfyUiClient {
    public record ImageData(String mediaType, byte[] bytes) {}
    private static final int IMAGE_LIMIT = 20 * 1024 * 1024;
    private final ObjectMapper json;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    public ComfyUiClient(ObjectMapper json) { this.json = json; }

    public JsonNode systemStats(ComfyUiSettings.Endpoint endpoint) {
        return jsonRequest(endpoint, "/system_stats", null, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }
    public String submit(ComfyUiSettings.Endpoint endpoint, ObjectNode workflow, long deadline) {
        ObjectNode body = json.createObjectNode();
        body.set("prompt", workflow);
        body.put("client_id", java.util.UUID.randomUUID().toString());
        JsonNode response = jsonRequest(endpoint, "/prompt", body, deadline);
        String id = response.path("prompt_id").asText("");
        if (id.isBlank() || response.hasNonNull("error")) throw upstream("工作流提交失败，请检查模型、节点和参数配置");
        return id;
    }
    public List<ImageData> awaitImages(ComfyUiSettings.Endpoint endpoint, String promptId, String outputNode, long deadline) {
        while (true) {
            checkInterrupted();
            String path = "/history/" + java.net.URLEncoder.encode(promptId, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode entry = jsonRequest(endpoint, path, null, deadline).path(promptId);
            JsonNode status = entry.path("status");
            if ("error".equals(status.path("status_str").asText())) throw upstream("工作流执行失败，请检查远程 ComfyUI 的模型、节点和显存");
            for (JsonNode message : status.path("messages")) {
                if ("execution_error".equals(message.path(0).asText()) || "execution_interrupted".equals(message.path(0).asText()))
                    throw upstream("工作流执行失败或已被中断，请检查远程 ComfyUI");
            }
            if (status.path("completed").asBoolean() || "success".equals(status.path("status_str").asText())
                    || (!entry.has("status") && entry.path("outputs").isObject())) {
                List<JsonNode> outputs = new ArrayList<>();
                if (outputNode != null && !outputNode.isBlank()) outputs.add(entry.path("outputs").path(outputNode));
                else entry.path("outputs").forEach(outputs::add);
                List<JsonNode> files = new ArrayList<>();
                for (JsonNode output : outputs) for (JsonNode image : output.path("images")) {
                    if (outputNode != null && !outputNode.isBlank() || "output".equals(image.path("type").asText("output"))) files.add(image);
                }
                if (files.isEmpty()) throw upstream("工作流已完成，但没有图片输出；请使用 SaveImage 节点或配置正确的输出节点 ID");
                if (files.size() > 8) throw upstream("一次最多接收 8 张图片，请降低工作流的批量数量");
                List<ImageData> images = new ArrayList<>();
                for (JsonNode file : files) images.add(download(endpoint, file, deadline));
                return images;
            }
            try { Thread.sleep(Math.min(1000, remainingMillis(deadline))); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw upstream("图片生成等待已中断；远程任务可能仍在运行，请勿重复提交"); }
        }
    }
    private ImageData download(ComfyUiSettings.Endpoint endpoint, JsonNode file, long deadline) {
        String filename = file.path("filename").asText("");
        if (filename.isBlank()) throw upstream("ComfyUI 图片输出缺少文件名");
        HttpUrl url = HttpUrl.get(endpoint.baseUrl() + "/view").newBuilder()
            .addQueryParameter("filename", filename).addQueryParameter("subfolder", file.path("subfolder").asText(""))
            .addQueryParameter("type", file.path("type").asText("output")).build();
        byte[] bytes = request(endpoint, url.toString(), null, deadline, IMAGE_LIMIT);
        String type = imageType(bytes);
        if (type == null) throw upstream("ComfyUI 未返回有效的 PNG、JPEG、WebP 或 GIF 图片");
        return new ImageData(type, bytes);
    }
    private JsonNode jsonRequest(ComfyUiSettings.Endpoint endpoint, String path, ObjectNode body, long deadline) {
        byte[] bytes = request(endpoint, endpoint.baseUrl() + path, body, deadline, 4 * 1024 * 1024);
        try {
            JsonNode value = json.readTree(bytes);
            if (value == null || !value.isObject()) throw upstream("ComfyUI 响应格式无效");
            return value;
        } catch (IOException error) { throw upstream("ComfyUI 未返回有效 JSON，请检查服务地址"); }
    }
    private byte[] request(ComfyUiSettings.Endpoint endpoint, String url, ObjectNode body, long deadline, int limit) {
        checkInterrupted();
        long timeout = Math.min(30_000, remainingMillis(deadline));
        Request.Builder request = new Request.Builder().url(url).header("Accept", body == null ? "*/*" : "application/json");
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) request.header("Authorization", "Bearer " + endpoint.apiKey());
        if (body != null) {
            byte[] payload = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // one-shot 同时禁止 HTTP 503 + Retry-After: 0 等响应触发 OkHttp 重放提交。
            request.post(new RequestBody() {
                @Override public MediaType contentType() { return MediaType.get("application/json; charset=utf-8"); }
                @Override public long contentLength() { return payload.length; }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { sink.write(payload); }
            });
        }
        var call = http.newCall(request.build());
        call.timeout().timeout(timeout, TimeUnit.MILLISECONDS);
        try (var response = call.execute()) {
            if (!response.isSuccessful()) {
                String hint = switch (response.code()) {
                    case 401, 403 -> "认证失败，请检查端点 API Key 和网关权限";
                    case 404 -> "接口不存在，请检查服务根地址";
                    case 400, 422 -> "工作流校验失败，请检查模型、节点及输入映射";
                    case 429 -> "服务繁忙，请稍后重试";
                    default -> "远程服务请求失败";
                };
                throw upstream(hint + "（HTTP " + response.code() + "）");
            }
            var responseBody = response.body();
            if (responseBody == null) throw upstream("ComfyUI 返回了空响应");
            if (responseBody.contentLength() > limit) throw upstream("ComfyUI 响应过大，单张图片上限为 20 MB");
            byte[] bytes = responseBody.byteStream().readNBytes(limit + 1);
            if (bytes.length > limit) throw upstream("ComfyUI 响应超过大小限制");
            remainingMillis(deadline);
            return bytes;
        } catch (IOException error) {
            checkInterrupted();
            throw upstream("ComfyUI 连接失败或请求超时；若任务已提交，远程可能仍在生成，请勿立即重复提交");
        }
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw upstream("图片生成等待已中断；远程任务可能仍在运行，请勿重复提交");
    }
    private static long remainingMillis(long deadline) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "等待 ComfyUI 生成图片超时，远程任务可能仍在运行，请勿立即重复提交");
        return remaining;
    }
    static String imageType(byte[] b) {
        if (b.length >= 8 && b[0] == (byte)137 && b[1] == 80 && b[2] == 78 && b[3] == 71 && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) return "image/png";
        if (b.length >= 3 && b[0] == (byte)255 && b[1] == (byte)216 && b[2] == (byte)255) return "image/jpeg";
        if (b.length >= 12 && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        if (b.length >= 6 && (new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF89a")
                || new String(b, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF87a"))) return "image/gif";
        return null;
    }
    private static ResponseStatusException upstream(String message) { return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message); }
}
