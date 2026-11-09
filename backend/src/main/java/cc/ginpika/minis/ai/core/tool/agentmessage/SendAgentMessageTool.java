package cc.ginpika.minis.ai.core.tool.agentmessage;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolRisk;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class SendAgentMessageTool implements AiTool {
    public static final String NAME = "send_agent_message";
    public static final int MAX_AGENT_ID_LENGTH = 128;
    public static final int MAX_MESSAGE_LENGTH = 16_000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "向已登记的 Agent 发送一条工作消息并启动后台执行，立即返回消息 ID。"
                + "对方看不到当前对话，请在正文里交代目标、必要背景、范围和预期产出。"
                + "收件 Agent 可做只读检索、分析和起草，写入与命令执行仍由原有授权流程处理。"
                + "执行结束后结果回到本会话，也可以用 " + ReadAgentMessageTool.NAME + " 查看状态和结果。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    @Override
    public ToolRisk risk() {
        return ToolRisk.EXEC;
    }

    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public String validate(JsonNode args) {
        return validateArguments(args);
    }

    public static String validateArguments(JsonNode args) {
        if (args == null || !args.isObject()) {
            return "工具参数必须是 JSON 对象。";
        }
        String agentId = args.path("agent_id").textValue();
        if (agentId == null || agentId.isBlank() || agentId.length() > MAX_AGENT_ID_LENGTH) {
            return "agent_id 必须是成员 ID，长度最多 " + MAX_AGENT_ID_LENGTH + "。";
        }
        String message = args.path("message").textValue();
        if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
            return "message 必须是非空文本，长度最多 " + MAX_MESSAGE_LENGTH + "。";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("agent_id").put("type", "string").put("minLength", 1)
                .put("maxLength", MAX_AGENT_ID_LENGTH)
                .put("description", ListAgentsTool.NAME + " 返回的成员 ID");
        props.putObject("message").put("type", "string").put("minLength", 1)
                .put("maxLength", MAX_MESSAGE_LENGTH).put("description", "交给目标 Agent 的工作消息");
        params.putArray("required").add("agent_id").add("message");
        return params;
    }
}
