package cc.ginpika.minis.ai.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ComfyUiSettings {
    public record Endpoint(String id, String name, String baseUrl, String apiKey) {}
    public record Profile(String id, String name, String description, String endpointId, ObjectNode workflow,
            String promptNodeId, String promptInput, String seedNodeId, String seedInput, String outputNodeId, Integer timeoutSeconds) {}
    public record View(List<Endpoint> endpoints, List<Profile> profiles, String defaultProfileId) {}
    private final ComfyUiStore store;
    private final ObjectMapper json;
    public ComfyUiSettings(ComfyUiStore store, ObjectMapper json) { this.store = store; this.json = json; }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("ComfyUI 配置数据无效", e); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("ComfyUI 配置无法保存", e); }
    }
    public View view() {
        return new View(store.endpoints().stream().map(v -> decode(v, Endpoint.class)).toList(),
            store.profiles().stream().map(v -> decode(v, Profile.class)).toList(), store.defaultProfile());
    }
    public Endpoint endpoint(String id) {
        return view().endpoints().stream().filter(e -> e.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "ComfyUI 端点不存在"));
    }
    public Profile resolve(String id) {
        String selected = text(id).isEmpty() ? store.defaultProfile() : id;
        return view().profiles().stream().filter(p -> p.id().equals(selected)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "ComfyUI 档案不存在，请先在设置页配置并选择默认档案"));
    }
    public static Endpoint validateEndpoint(Endpoint e) {
        require(e != null, "请填写 ComfyUI 端点");
        require(!text(e.name()).isEmpty(), "请填写端点名称");
        String base = text(e.baseUrl()).replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            require(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null
                && uri.getPort() <= 65535
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null, "请输入有效的 HTTP(S) 服务根地址");
        } catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 HTTP(S) 服务根地址"); }
        String key = text(e.apiKey());
        require(key.chars().allMatch(c -> c >= 32 && c < 127), "API Key 只能包含可打印的 ASCII 字符");
        return new Endpoint(e.id(), text(e.name()), base, key);
    }
    @Transactional
    public View saveEndpoint(String id, Endpoint draft) {
        Endpoint e = validateEndpoint(draft);
        if (id != null) endpoint(id);
        String actual = id == null ? UUID.randomUUID().toString() : id;
        store.saveEndpoint(actual, encode(new Endpoint(actual, e.name(), e.baseUrl(), e.apiKey())));
        return view();
    }
    @Transactional
    public View deleteEndpoint(String id) {
        endpoint(id);
        require(view().profiles().stream().noneMatch(p -> id.equals(p.endpointId())), "端点仍被工作流档案引用，请先修改或删除档案");
        store.deleteEndpoint(id);
        return view();
    }
    @Transactional
    public View saveProfile(String id, Profile p) {
        require(p != null && !text(p.name()).isEmpty(), "请填写档案名称");
        endpoint(p.endpointId());
        if (id != null) resolve(id);
        ObjectNode workflow = p.workflow();
        require(workflow != null && !workflow.isEmpty() && !workflow.has("nodes"), "请导入 ComfyUI 导出的 API 格式工作流 JSON（非画布格式）");
        require(workflow.toString().length() <= 2_000_000, "工作流 JSON 不能超过 2 MB");
        workflow.properties().forEach(entry -> require(entry.getValue().isObject()
            && entry.getValue().path("class_type").isTextual() && !entry.getValue().path("class_type").asText().isBlank()
            && entry.getValue().path("inputs").isObject(), "工作流必须使用 API 格式：每个节点包含 class_type 和 inputs"));
        String promptNode = text(p.promptNodeId()), promptInput = fallback(p.promptInput(), "text");
        require(workflow.path(promptNode).path("inputs").path(promptInput).isTextual(), "提示词映射必须指向现有的字符串输入，不能覆盖节点连线");
        String seedNode = text(p.seedNodeId()), seedInput = fallback(p.seedInput(), "seed");
        if (!seedNode.isEmpty()) require(workflow.path(seedNode).path("inputs").path(seedInput).isIntegralNumber(), "种子映射必须指向现有的整数输入");
        require(seedNode.isEmpty() || !seedNode.equals(promptNode) || !seedInput.equals(promptInput), "提示词和种子不能使用同一个输入");
        String output = text(p.outputNodeId());
        require(output.isEmpty() || workflow.has(output), "输出节点 ID 不存在");
        int timeout = p.timeoutSeconds() == null ? 600 : p.timeoutSeconds();
        require(timeout >= 10 && timeout <= 3600, "等待超时必须在 10 到 3600 秒之间");
        String actual = id == null ? UUID.randomUUID().toString() : id;
        store.saveProfile(actual, encode(new Profile(actual, text(p.name()), text(p.description()), p.endpointId(), workflow.deepCopy(),
            promptNode, promptInput, seedNode, seedInput, output, timeout)));
        if (store.defaultProfile() == null) store.select(actual);
        return view();
    }
    @Transactional
    public View deleteProfile(String id) {
        resolve(id);
        store.deleteProfile(id);
        if (id.equals(store.defaultProfile())) store.select(view().profiles().stream().map(Profile::id).findFirst().orElse(null));
        return view();
    }
    public View select(String id) { if (!text(id).isEmpty()) resolve(id); store.select(id); return view(); }
    static String text(String value) { return value == null ? "" : value.strip(); }
    private static String fallback(String value, String fallback) { return text(value).isEmpty() ? fallback : text(value); }
    static void require(boolean condition, String message) { if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
