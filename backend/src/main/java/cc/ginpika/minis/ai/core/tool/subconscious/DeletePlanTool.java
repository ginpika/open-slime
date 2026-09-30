package cc.ginpika.minis.ai.core.tool.subconscious;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * delete_plan：撤掉一个「任务计划」——它不再出现在名册里，心跳主会话也就不会再派它。
 *
 * <p>只撤编制：它过去那些执行留痕（{@code sub_exec} / 写库清单）原样留在库里，
 * 仍然可以按执行 id 翻看，页面的资源使用统计也不会因此失真。
 */
@Component
public class DeletePlanTool implements AiTool {

    public static final String NAME = "delete_plan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "撤掉一个任务计划：它从名册上消失，此后不再被派活（历史留痕保留）。"
                + "只是暂时不需要就用 " + UpdatePlanTool.NAME + " 改掉它，别急着撤。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("plan")
                .put("type", "string")
                .put("description", "要撤哪个计划：填它的名字或 id");
        params.putArray("required").add("plan");
        return params;
    }
}
