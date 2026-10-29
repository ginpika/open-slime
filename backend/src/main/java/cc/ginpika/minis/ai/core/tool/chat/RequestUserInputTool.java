package cc.ginpika.minis.ai.core.tool.chat;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 首页对话中的一次选择题；用户回答以正常 user 消息进入下一轮。 */
@Component
public class RequestUserInputTool implements AiTool {
    public static final String NAME = "request_user_input";

    @Override public String name() { return NAME; }
    @Override public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.CHAT); }
    @Override public boolean agentsMayCall() { return false; }
    @Override public String description() {
        return "当确实需要用户澄清需求、选择方案或表达偏好才能继续时，向用户提出一个明确问题，"
                + "提供 2–6 个互不重复的预选答案。用户也始终可以自定义回复，不必添加‘其他’选项。"
                + "本工具必须单独调用，不能与其他工具同批调用；调用成功即结束本轮并等待用户，"
                + "不得自行替用户选择。用户回答后按回答继续，不要重复询问已经明确的信息。";
    }
    @Override public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode().put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("question").put("type", "string").put("maxLength", 1000)
                .put("description", "完整且能独立理解的问题");
        ObjectNode options = props.putObject("options").put("type", "array").put("minItems", 2).put("maxItems", 6);
        ObjectNode item = options.putObject("items").put("type", "object");
        ObjectNode fields = item.putObject("properties");
        fields.putObject("label").put("type", "string").put("maxLength", 120).put("description", "简短答案；如有推荐可在标签标注");
        fields.putObject("description").put("type", "string").put("maxLength", 500).put("description", "可选，一句话解释该选择的影响");
        item.putArray("required").add("label");
        params.putArray("required").add("question").add("options");
        return params;
    }

    /** 执行侧也校验并规范化，拒绝不可展示的题目。 */
    public static ObjectNode read(ObjectMapper om, String json) {
        final JsonNode args;
        try { args = om.readTree(json); }
        catch (Exception error) { throw new IllegalArgumentException("参数必须是 JSON 对象"); }
        if (args == null || !args.isObject()) throw new IllegalArgumentException("参数必须是 JSON 对象");
        String question = text(args.path("question"), 1000, false);
        JsonNode options = args.path("options");
        if (!options.isArray() || options.size() < 2 || options.size() > 6)
            throw new IllegalArgumentException("请提供 2–6 个预选答案");
        ObjectNode normalized = om.createObjectNode().put("question", question);
        var items = normalized.putArray("options");
        Set<String> labels = new HashSet<>();
        for (JsonNode option : options) {
            String label = text(option.path("label"), 120, false);
            if (!labels.add(label)) throw new IllegalArgumentException("预选答案不能重复");
            String description = text(option.path("description"), 500, true);
            items.addObject().put("label", label).put("description", description);
        }
        return normalized;
    }
    private static String text(JsonNode value, int max, boolean optional) {
        if (optional && (value.isMissingNode() || value.isNull())) return "";
        if (!value.isTextual()) throw new IllegalArgumentException("问题、答案和说明必须是文本");
        String text = value.asText().strip();
        if ((!optional && text.isBlank()) || text.length() > max)
            throw new IllegalArgumentException("问题或答案为空，或超出长度限制");
        return text;
    }
    /** 纯文本也保留全部选项，关闭工具上下文回放时模型仍可理解用户的答案。 */
    public static String content(JsonNode question) {
        StringBuilder text = new StringBuilder(question.path("question").asText());
        int i = 0;
        for (JsonNode option : question.path("options")) {
            text.append("\n\n").append(++i).append(". ").append(option.path("label").asText());
            String description = option.path("description").asText("");
            if (!description.isBlank()) text.append("：").append(description);
        }
        return text.append("\n\n也可以自定义回复。").toString();
    }
}
