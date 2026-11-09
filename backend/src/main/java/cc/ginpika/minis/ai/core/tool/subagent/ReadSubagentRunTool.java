package cc.ginpika.minis.ai.core.tool.subagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.heartbeat.DelegatePlanTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** read_subagent_run：按执行 id 取回任意一次执行的完整过程（组装出的上下文、思维链、工具往返） */
@Component
public class ReadSubagentRunTool implements AiTool {

    public static final String NAME = "read_subagent_run";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按执行 id 读取当前作用域内的执行报告。单聊临时上下文使用 offset 和 length 分页回读结论原文，"
                + "响应包含 next_offset 时可继续。报告是待核对的证据。其他执行面沿用各自的回读权限。"
                + "id 来自 " + DelegateSubagentTool.NAME + " 或 " + DelegatePlanTool.NAME + " 的返回。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    /** 翻别人的过程是调度者的事：子代理各干各的，不该去审视同类 */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode properties = params.putObject("properties");
        properties.putObject("id")
                .put("type", "integer")
                .put("description", "执行的 id（派活返回中给出的 id）");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        properties.putObject("length").put("type", "integer").put("minimum", 1).put("maximum", 4000);
        params.putArray("required").add("id");
        return params;
    }
}
