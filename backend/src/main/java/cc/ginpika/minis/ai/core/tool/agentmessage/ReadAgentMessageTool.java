package cc.ginpika.minis.ai.core.tool.agentmessage;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReadAgentMessageTool implements AiTool {
    public static final String NAME = "read_agent_message";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "读取当前成员在本会话中发出的一条工作消息的执行状态、结果或失败原因。"
                + "消息 ID 取自 " + SendAgentMessageTool.NAME + " 的回执。查询立即返回，请勿连续轮询。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    @Override
    public boolean agentsMayCall() {
        return false;
    }

    public static String validateArguments(JsonNode args) {
        JsonNode id = args == null ? null : args.get("id");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0) {
            return "id 必须是发送消息时返回的正整数 ID。";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("id").put("type", "integer").put("minimum", 1)
                .put("description", SendAgentMessageTool.NAME + " 返回的消息 ID");
        params.putArray("required").add("id");
        return params;
    }
}
