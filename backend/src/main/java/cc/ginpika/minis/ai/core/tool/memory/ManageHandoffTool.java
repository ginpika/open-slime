package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ManageHandoffTool implements AiTool {
    public String name() { return "manage_handoff"; }
    public String description() { return "查看发给当前 Agent/项目/会话的交接，或接受、拒绝、完成交接。完成前须先接受。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT); }
    public ToolDomain domain() { return ToolDomain.MEMORY; }
    public boolean writes() { return true; }
    public String validate(JsonNode args) {
        String action = args == null ? "" : args.path("action").asText("");
        if (!Set.of("list", "accepted", "rejected", "done").contains(action)) { return "action 非法"; }
        return !"list".equals(action) && args.path("handoff_id").asLong(0) <= 0 ? "缺少 handoff_id" : null;
    }
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode p = om.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("action").put("type", "string").putArray("enum")
                .add("list").add("accepted").add("rejected").add("done");
        props.putObject("handoff_id").put("type", "integer");
        p.putArray("required").add("action");
        return p;
    }
}
