package cc.ginpika.minis.ai.core.tool.subagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subconscious.DelegatePlanTool;
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
        return "按执行 id 取回某一次执行的完整过程（当时组装出的上下文、思维链、每一次工具调用与结果）。"
                + "子代理、任务计划、心跳主会话的执行都能按 id 翻——"
                + "只在需要核对细节、怀疑结论不实或想了解它查了哪里时调用；"
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
        params.putObject("properties").putObject("id")
                .put("type", "integer")
                .put("description", "执行的 id（派活返回中给出的 id）");
        params.putArray("required").add("id");
        return params;
    }
}
