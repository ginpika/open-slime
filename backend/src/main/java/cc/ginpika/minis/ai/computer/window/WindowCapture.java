package cc.ginpika.minis.ai.computer.window;

import cc.ginpika.minis.ai.core.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** The desktop executable runs a capture-only helper before initializing Tauri or single-instance focus logic. */
@Service
public class WindowCapture {
    private final String helper;
    private final ObjectMapper json;
    private final cc.ginpika.minis.config.DesktopProps desktop;
    public WindowCapture(@Value("${minis.computer.window-helper:}") String helper, ObjectMapper json,
                         cc.ginpika.minis.config.DesktopProps desktop) {
        this.helper = helper; this.json = json; this.desktop = desktop;
    }
    public boolean available() {
        try { return desktop.isEnabled() && !desktop.getLocalToken().isBlank() && !helper.isBlank()
                && Path.of(helper).isAbsolute() && Files.isRegularFile(Path.of(helper)); }
        catch (RuntimeException error) { return false; }
    }
    public JsonNode windows() { return invoke(List.of("list")); }
    public String capture(long id, long pid) {
        if (id <= 0 || pid <= 0 || id > 0xffff_ffffL || pid > 0xffff_ffffL)
            throw new IllegalArgumentException("window_id 和 pid 必须来自 list 返回的窗口");
        JsonNode result = invoke(List.of("capture", Long.toString(id), Long.toString(pid)));
        String image = result.path("image").asText();
        ToolOutput.validateImage(image);
        var metadata = json.createObjectNode().put("window_id", id).put("pid", pid)
                .put("title", result.path("title").asText()).put("captured_at", result.path("capturedAt").asLong());
        return ToolOutput.image(metadata.toString(), image);
    }
    private JsonNode invoke(List<String> arguments) {
        if (!available()) throw new IllegalStateException("窗口截图需要 Windows / macOS 桌面端本机模式");
        var command = new java.util.ArrayList<String>();
        command.add(helper); command.add("--computer-use"); command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process child = process;
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (var input = child.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (bytes.size() + size > ToolOutput.MAX_IMAGE_CHARS + 100_000)
                            throw new IllegalStateException("截图返回内容过大");
                        bytes.write(buffer, 0, size);
                    }
                    return bytes.toByteArray();
                } catch (java.io.IOException error) { throw new IllegalStateException("读取截图失败", error); }
            });
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IllegalStateException("窗口截图超时，请检查系统屏幕录制权限");
            JsonNode response = json.readTree(output.get(2, TimeUnit.SECONDS));
            if (response == null || !response.path("ok").asBoolean())
                throw new IllegalStateException(response == null ? "截图进程没有返回结果" : response.path("error").asText("截图失败"));
            return response.path("result");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("窗口截图已停止");
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("窗口截图失败：" + error.getMessage(), error); }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
