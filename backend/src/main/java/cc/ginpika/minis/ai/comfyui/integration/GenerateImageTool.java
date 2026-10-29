package cc.ginpika.minis.ai.comfyui.integration;

import cc.ginpika.minis.ai.comfyui.ComfyUiService;
import cc.ginpika.minis.ai.comfyui.ComfyUiSettings;
import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 工具适配层只负责 schema 与结果表达，绘图能力归 ComfyUiService。 */
@Component
public class GenerateImageTool implements AiTool {
    public static final String NAME = "generate_image";
    private final ComfyUiSettings settings;
    private final ComfyUiService service;
    private final ObjectMapper json;
    public GenerateImageTool(ComfyUiSettings settings, ComfyUiService service, ObjectMapper json) {
        this.settings = settings; this.service = service; this.json = json;
    }
    public String name() { return NAME; }
    public String description() {
        return "根据用户要求生成图片，调用已配置的 ComfyUI 工作流并等待生成结果，可能需要数分钟。"
            + "当用户要求新图、再来一张、换角色或换风格，且你决定执行时，必须在当前轮实际发起本工具调用；"
            + "仅在思考或正文中说准备生成、正在生成，不代表已经执行。角色扮演或叙述中的动作也不能代替真实工具调用。"
            + "prompt 是完整绘图提示词；profile_id 从可用档案中选择，省略时使用默认档案。"
            + "只有本轮工具成功返回 images 后，才能声称新图片已生成，并将 images 中的 url 用 Markdown 图片语法 ![生成图片](url) 原样展示。"
            + "历史图片地址只代表历史产物，不能复用为新生成结果；展示历史图片时应明确说明。"
            + "禁止猜测、拼接或编造图片 URL、文件名及 placeholder 占位地址，不能把示例地址当作工具结果。"
            + "未配置档案时请提示用户到设置页配置 ComfyUI。失败或超时后如实说明，不要自动重试，以免重复生成。"
            + "如果需要澄清或无法满足请求，应直接说明，不能伪造执行过程或成功结果。";
    }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.CHAT); }
    public ToolDomain domain() { return ToolDomain.IMAGE; }
    public ToolRisk risk() { return ToolRisk.WRITE; }
    public String validate(JsonNode args) {
        return !args.path("prompt").isTextual() || args.path("prompt").asText().isBlank() || args.path("prompt").asText().length() > 20_000
            ? "prompt 必须是 1 到 20000 字符的绘图提示词" : null;
    }
    public ObjectNode parameters(ObjectMapper om) {
        var params = om.createObjectNode().put("type", "object");
        var props = params.putObject("properties");
        props.putObject("prompt").put("type", "string").put("description", "完整的绘图提示词，保留用户对画面和风格的要求");
        var profile = props.putObject("profile_id").put("type", "string");
        var view = settings.view();
        profile.put("description", "工作流档案 ID（省略使用默认档案）。可用档案：" + view.profiles().stream()
            .map(p -> p.id() + " = " + p.name() + (p.id().equals(view.defaultProfileId()) ? "（默认）" : "") + " " + p.description()).collect(java.util.stream.Collectors.joining("；")));
        if (!view.profiles().isEmpty()) { var choices = profile.putArray("enum"); view.profiles().forEach(p -> choices.add(p.id())); }
        params.putArray("required").add("prompt");
        params.put("additionalProperties", false);
        return params;
    }
    public String execute(JsonNode args) {
        String invalid = validate(args);
        if (invalid != null) return invalid;
        var result = service.generate(args.path("profile_id").asText(null), args.path("prompt").asText());
        ObjectNode response = json.valueToTree(result);
        response.put("instruction", "这是本次调用实际生成的图片。请将每张图片用 ![生成图片](url) 展示在最终回复中，地址保持原样。"
            + "这些地址仅证明本次生成已完成；后续若需要新图片，必须重新调用 generate_image，不能复用这些地址冒充新结果。");
        return response.toString();
    }
}
