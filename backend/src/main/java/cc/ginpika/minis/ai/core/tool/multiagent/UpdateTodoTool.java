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
        return "改圆桌共享任务区里的一条待办：可改状态（pending/doing/blocked/done/dropped）、"
                + "负责人（owner）、补充说明（detail），标记受阻时在 reason 里写清原因。"
                + "标 blocked 时先用 with 做一次决策——找谁商量：可写多个成员名（用「、」分隔，只你们"
                + "定向沟通一轮、其余成员不受打扰）；写「用户」= 请用户拍板（全桌暂停等用户发言）；"
                + "留空或填「全员」= 全桌一起议。"
                + "id 就是任务区里那一条的 id。要标完成请用 " + CompleteTodoTool.NAME + "（能带上结论）。";
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
        props.putObject("id").put("type", "string").put("description", "任务区里那一条的 id");
        ObjectNode status = props.putObject("status");
        status.put("type", "string").put("description", "新状态：pending | doing | blocked | done | dropped");
        status.putArray("enum").add("pending").add("doing").add("blocked").add("done").add("dropped");
        props.putObject("owner").put("type", "string").put("description", "改负责人（成员名）；不填不改");
        props.putObject("detail").put("type", "string").put("description", "补充说明；不填不改");
        props.putObject("reason").put("type", "string").put("description", "受阻原因（status=blocked 时写）");
        props.putObject("with").put("type", "string")
                .put("description", "status=blocked 时的决策：找谁。可多个成员名（用「、」分隔，只你们"
                        + "定向沟通一轮）；写「用户」= 请用户拍板（全桌暂停等用户发言）；"
                        + "留空或填「全员」= 全桌一起议");
        params.putArray("required").add("id");
        return params;
    }
}
