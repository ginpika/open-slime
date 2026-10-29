package cc.ginpika.minis.ai.comfyui;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 可被聊天工具、HTTP 及未来其他功能直接复用的同步绘图入口；无会话或模型编排依赖。 */
@Service
public class ComfyUiService {
    public record Image(String id, String url) {}
    public record Result(String generationId, String profileId, String promptId, List<Image> images) {}
    public record TestResult(boolean ok, long elapsedMs, String detail, String error) {}
    private final ComfyUiSettings settings;
    private final ComfyUiClient client;
    private final ComfyUiStore store;
    public ComfyUiService(ComfyUiSettings settings, ComfyUiClient client, ComfyUiStore store) {
        this.settings = settings; this.client = client; this.store = store;
    }
    public TestResult testEndpoint(ComfyUiSettings.Endpoint draft) {
        var endpoint = ComfyUiSettings.validateEndpoint(draft);
        long start = System.nanoTime();
        try {
            var stats = client.systemStats(endpoint);
            if (!stats.path("system").isObject() || !stats.path("devices").isArray())
                return new TestResult(false, elapsed(start), null, "响应不是 ComfyUI 系统信息，请检查服务根地址");
            return new TestResult(true, elapsed(start), "ComfyUI 连接成功", null);
        } catch (ResponseStatusException error) { return new TestResult(false, elapsed(start), null, error.getReason()); }
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    public Result generate(String profileId, String prompt) {
        ComfyUiSettings.require(prompt != null && !prompt.isBlank() && prompt.length() <= 20_000, "提示词不能为空，且不能超过 20000 字符");
        var profile = settings.resolve(profileId);
        var endpoint = settings.endpoint(profile.endpointId());
        ObjectNode workflow = profile.workflow().deepCopy();
        ((ObjectNode) workflow.path(profile.promptNodeId()).path("inputs")).put(profile.promptInput(), prompt);
        if (!profile.seedNodeId().isBlank()) ((ObjectNode) workflow.path(profile.seedNodeId()).path("inputs"))
            .put(profile.seedInput(), ThreadLocalRandom.current().nextLong(1L << 53));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(profile.timeoutSeconds());
        String generationId = store.start(profile.id(), prompt);
        try {
            String promptId = client.submit(endpoint, workflow, deadline);
            store.submitted(generationId, promptId);
            var images = client.awaitImages(endpoint, promptId, profile.outputNodeId(), deadline);
            return new Result(generationId, profile.id(), promptId, store.complete(generationId, images));
        } catch (RuntimeException error) {
            store.failed(generationId, error instanceof ResponseStatusException status ? status.getReason() : "图片生成失败");
            throw error;
        }
    }
    public ComfyUiClient.ImageData image(String id) { return store.image(id); }
}
