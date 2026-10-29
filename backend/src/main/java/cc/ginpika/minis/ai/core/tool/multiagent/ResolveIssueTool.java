package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ResolveIssueTool implements AiTool {
    public static final String NAME = "resolve_issue";
    public String name() { return NAME; }
    public String description() { return "仅在议题会话中使用：成员已充分沟通后，提交可执行结论并回传主任务。写清决定、理由、下一步和未解决限制。无法形成结论时 request_discussion 找用户，不要假称已有共识。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return true; }
    public ObjectNode parameters(ObjectMapper om) {
        var schema = om.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("conclusion").put("type", "string");
        schema.putArray("required").add("conclusion");
        return schema;
    }
}
