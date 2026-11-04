package cc.ginpika.minis.ai.computer.integration;

import cc.ginpika.minis.ai.computer.browser.*;
import cc.ginpika.minis.ai.computer.window.WindowCapture;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.UserScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** Conversation identity is passed in; computer capabilities never read chat stores or orchestrate models. */
@Service
public class ComputerTools {
    private final WindowCapture windows;
    private final ChromeBrowser isolated;
    private final ChromeBridge chrome;
    private final ObjectMapper json;
    private final ChromeTool schema;
    public ComputerTools(WindowCapture windows, ChromeBrowser isolated, ChromeBridge chrome, ObjectMapper json, ChromeTool schema) {
        this.windows = windows; this.isolated = isolated; this.chrome = chrome; this.json = json; this.schema = schema;
    }
    public static boolean handles(String name) { return ChromeTool.NAME.equals(name) || WindowCaptureTool.NAME.equals(name); }
    public String run(String name, JsonNode args, String conversation) {
        if (!handles(name)) throw new IllegalArgumentException("未知 computer 工具");
        if (conversation == null || conversation.isBlank() || UserScope.get() == null || UserScope.get().isBlank())
            return "computer 工具缺少已认证用户或会话身份，未执行";
        try {
            if (WindowCaptureTool.NAME.equals(name)) {
                return switch (args.path("action").asText()) {
                    case "list" -> json.writeValueAsString(windows.windows());
                    case "capture" -> {
                        if (!args.path("window_id").isIntegralNumber() || !args.path("pid").isIntegralNumber())
                            throw new IllegalArgumentException("window_id 和 pid 必须来自 list 返回的同一窗口");
                        yield windows.capture(args.path("window_id").asLong(), args.path("pid").asLong());
                    }
                    default -> throw new IllegalArgumentException("窗口 action 只能是 list / capture");
                };
            }
            String error = schema.validate(args);
            if (error != null) throw new IllegalArgumentException(error);
            if ("status".equals(args.path("action").asText())) return json.createObjectNode()
                    .put("deployment", chrome.desktopLocal() ? "desktop_local" : "web_or_remote")
                    .put("user_connected", chrome.connected()).put("isolated_available", true)
                    .put("window_capture_available", windows.available()).toString();
            if ("open".equals(args.path("action").asText())) BrowserUrls.http(args.path("url").asText());
            String environment = args.path("environment").asText();
            String tab = args.path("tab_id").asText();
            String tabEnvironment = tab.startsWith("user:") ? "user" : tab.startsWith("isolated:") ? "isolated" : "";
            if (!environment.isBlank() && !tabEnvironment.isBlank() && !environment.equals(tabEnvironment))
                throw new IllegalArgumentException("environment 与 tab_id 所属环境不一致");
            if (environment.isBlank()) environment = !tabEnvironment.isBlank() ? tabEnvironment : chrome.connected() ? "user" : "isolated";
            if ("user".equals(environment)) {
                if (!chrome.desktopLocal()) throw new IllegalArgumentException("Web / 远程部署只能使用隔离 Chrome，不能连接本机 Chrome");
                if ("screenshot".equals(args.path("action").asText()))
                    throw new IllegalArgumentException("用户 Chrome 不激活标签页截图；请使用 read，或对用户指定的窗口使用 window_capture");
                return chrome.call(scope(conversation), args).toString();
            }
            return isolated.run(scope(conversation), args);
        } catch (java.util.concurrent.CancellationException error) { throw error; }
        catch (Exception error) { return json.createObjectNode().put("error", "computer_operation_failed").put("message", error.getMessage()).toString(); }
    }
    private static String scope(String conversation) throws Exception {
        String identity = UserScope.get() + "\n" + AgentScope.get() + "\n" + conversation;
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
}
