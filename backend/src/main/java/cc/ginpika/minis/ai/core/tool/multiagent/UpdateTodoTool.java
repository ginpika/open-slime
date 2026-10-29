package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * update_todo：改圆桌共享任务区里的一条——改状态、改负责人、补说明，或标记受阻。
 *
 * <p>执行途中发现问题时，把那条置为 {@code blocked} 并写清原因——那是发起讨论的凭据
 * （见 {@link RequestDiscussionTool}）：全桌据此决定是改条目、换人，还是先解决前置问题。
 */
@Component
public class UpdateTodoTool implements AiTool {

    public static final String NAME = "update_todo";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "主控修订原待办的目标、说明、负责人和依赖，可重新排队返工（pending）或放弃（dropped）。"
                + "规划阶段可将 blocked、dropped 或 done 的原条目重开为 pending，须给出 reason；执行者只能将自己当前条目标 blocked 并写清原因和证据。"
                + "with 可建议相关成员，主控统一决定如何处理；with=用户 会暂停等待用户。不能跳过成果和独立检查。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(com.fasterxml.jackson.databind.JsonNode args) {
        String id = args == null ? "" : args.path("id").asText("").strip();
        return id.isEmpty() ? "id 不能为空：指明要改任务区里的哪一条。" : null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("title").put("type", "string").put("description", "主控修订工作目标；不填不改");
        props.putObject("depends_on").put("type", "array").put("description", "主控修订前置条目ID列表，空数组清空依赖")
                .putObject("items").put("type", "string");
        props.putObject("id").put("type", "string").put("description", "任务区里那一条的 id");
        ObjectNode status = props.putObject("status");
        status.put("type", "string").put("description", "新状态：pending | blocked | dropped；不能跳过成果检查");
        status.putArray("enum").add("pending").add("blocked").add("dropped");
        props.putObject("owner").put("type", "string").put("description", "改负责人（成员名）；不填不改");
        props.putObject("detail").put("type", "string").put("description", "补充说明；不填不改");
        props.set("contract", AddTodoTool.contractSchema(om));
        props.putObject("reason").put("type", "string").put("description", "受阻、重开或返工依据；标 blocked 或将 blocked/dropped/done 重开为 pending 时必填");
        props.putObject("with").put("type", "string")
                .put("description", "status=blocked 时的决策：找谁。可多个成员名（用「、」分隔，只你们"
                        + "定向沟通一轮）；写「用户」= 请用户拍板（全桌暂停等用户发言）；"
                        + "留空或填「全员」= 全桌一起议");
        params.putArray("required").add("id");
        return params;
    }
}
