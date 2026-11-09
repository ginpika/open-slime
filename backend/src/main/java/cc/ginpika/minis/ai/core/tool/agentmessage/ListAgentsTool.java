package cc.ginpika.minis.ai.core.tool.agentmessage;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ListAgentsTool implements AiTool {
    public static final String NAME = "list_agents";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查找系统中可以接收工作消息的其他成员，返回成员 ID、名称、角色和简介。"
                + "根据任务选择成员，再用 " + SendAgentMessageTool.NAME + " 向对方发送消息。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return params;
    }
}
