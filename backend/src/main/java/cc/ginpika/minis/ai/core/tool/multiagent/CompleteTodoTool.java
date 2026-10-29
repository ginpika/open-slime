package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * complete_todo：把共享任务区的一条置为完成，并写下结论。
 *
 * <p>结论会进入任务板（也进事件正本），后续讨论与其它成员都能看到——
 * 「埋头做完 → 通知彼此」里的「通知」，一半就落在这里。
 */
@Component
public class CompleteTodoTool implements AiTool {

    public static final String NAME = "complete_todo";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "提交当前执行的待办成果供另一位成员检查，暂不标记完成。result 必须包含实际产出位置、验证方法、结果和限制。检查通过才解锁下游。";
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
        return id.isEmpty() ? "id 不能为空：指明要完成任务区里的哪一条。" : null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "任务区里那一条的 id");
        props.putObject("result").put("type", "string").put("description", "完成结论（做了什么、结论、产出）");
        params.putArray("required").add("id").add("result");
        return params;
    }
}
