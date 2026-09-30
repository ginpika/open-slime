package cc.ginpika.minis.ai.core.tool.subconscious;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * delegate_plan：把一个「任务计划」派出去干一件具体的事，拿回它的结论。
 *
 * <p>任务计划是常驻编制：它有自己的正文（管「你是谁、怎么做事」）与工具权限，这一轮要做什么由
 * {@code instruction} 补上。计划每被派出一次，就是起一个匿名实例跑一遍——与
 * {@link DelegateSubagentTool} 共用同一个执行引擎，区别只在「这次有没有带一份常驻档案」。
 *
 * <p>只想干一件一次性的活、不值得立编制时，用 {@link DelegateSubagentTool}。
 */
@Component
public class DelegatePlanTool implements AiTool {

    public static final String NAME = "delegate_plan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把一个「任务计划」派出去干一件具体的事：计划自带正文（它是谁、怎么做事）与工具权限，"
                + "你只需说清这一轮做什么。适合已经立好编制、要反复跑的活；"
                + "名册里没有合适的计划时，先用 " + CreatePlanTool.NAME + " 立一个，"
                + "或对一次性的活改用 " + DelegateSubagentTool.NAME + "。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    /** 计划实例不能再派计划：派活是「大模型自己那一层」的事，不然会一层套一层 */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("instruction").put("type", "string")
                .put("description", "这一轮要计划做什么：要它查什么、产出什么，尽量具体"
                        + "（它看不到当前对话，也不记得上一轮）");
        props.putObject("plan").put("type", "string")
                .put("description", "派哪个计划：填它的名字或 id");
        params.putArray("required").add("instruction").add("plan");
        return params;
    }
}
