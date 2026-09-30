package cc.ginpika.minis.ai.core.tool.subconscious;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * update_plan：改写一个已有「任务计划」——只改你给出的字段，没提的一律保持原样。
 *
 * <p>与 {@link CreatePlanTool} 共用同一套字段：自己立的编制，自己也能随时改。
 */
@Component
public class UpdatePlanTool implements AiTool {

    public static final String NAME = "update_plan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "改写一个已有任务计划的定义。只需给出要改的字段，其余保持原样；"
                + "名字也可以改（改名后派活要用新名字）。先想清楚再改——它下一轮被派出时就按新定义做事。";
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
        ObjectNode props = params.putObject("properties");
        props.putObject("plan").put("type", "string")
                .put("description", "要改哪个计划：填它的名字或 id");
        props.putObject("name").put("type", "string").put("description", "改成什么名字（不改就不填）");
        props.putObject("content").put("type", "string")
                .put("description", "新的提示词正文（不改就不填），可用 {{插槽}}");
        props.putObject("role").put("type", "string")
                .put("description", "正文组装成哪种消息：system 或 user（不改就不填）");
        AiTool.putStringArraySchema(props, "tools",
                "它允许调用的知识库工具名清单（整体替换；要清空就给空数组）");
        AiTool.putEnumArraySchema(props, "channels",
                "交付渠道（整体替换）：silent=只留痕；thread=可用 open_thread / send_message 对人说话",
                "silent", "thread");
        props.putObject("interval_ms").put("type", "integer")
                .put("description", "节拍参考（毫秒）；填 0 表示恢复「每拍都可以考虑」");
        params.putArray("required").add("plan");
        return params;
    }
}
