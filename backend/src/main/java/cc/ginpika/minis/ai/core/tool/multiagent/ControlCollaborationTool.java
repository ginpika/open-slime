package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** A semantic request from the current human message; only the collaboration runtime applies it. */
@Component
public class ControlCollaborationTool implements AiTool {
    public static final String NAME = "control_collaboration";
    public String name() { return NAME; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return true; }
    public boolean agentsMayCall() { return false; }
    public String description() {
        return "当前用户明确要求恢复/重试原工作时用 resume，调整工作、重开待办或重排优先级时用 replan，要求停止工作时用 stop。"
                + "按语义结合会话判断，不按词语匹配：询问进度、能力、原因、关心成员或假设讨论时不要调用；不确定先澄清。"
                + "basis 必须逐字引用本条用户消息中的指令依据。只提交控制请求，不直接改待办、执行命令或扩大原目标；运行时校验后生效。";
    }
    public String validate(JsonNode args) {
        if(args==null || !Set.of("resume","replan","stop").contains(args.path("action").asText()))return "action 必须为 resume、replan 或 stop。";
        if(!args.path("basis").isTextual() || args.path("basis").asText().isBlank())return "basis 必须引用当前用户指令。";
        return null;
    }
    public ObjectNode parameters(ObjectMapper json) {
        var out=json.createObjectNode().put("type","object");
        var props=out.putObject("properties");
        props.putObject("action").put("type","string").putArray("enum").add("resume").add("replan").add("stop");
        props.putObject("basis").put("type","string").put("description","本条用户消息中表达执行意图的原文，不得引用历史或模型自己的话");
        out.putArray("required").add("action").add("basis");return out;
    }
}
