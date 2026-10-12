package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * note_working：把**临时的工作状态**记进工作集（memory-v6 §13 · L3）。
 *
 * <p>为什么需要它：设计大纲 §三 要求「临时结论」与「长期记忆」分开——
 * 一段对话里的临时假设、当前进度、在途决策，不该立刻变成长期记忆；它们属于**工作状态**。
 * 记进工作集后：本轮注入就能看到「这次做到哪一步」，会话收尾时再由系统蒸馏，
 * 只有确有长期价值的部分才升为长期记忆。
 *
 * <p>执行见 {@code KnowledgeToolbox} 的分发；写入走轮内缓冲（本轮写入下一轮才可见）。
 */
@Component
public class NoteWorkingTool implements AiTool {

    public static final String NAME = "note_working";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "记一条**临时工作状态**（进度 / 临时结论 / 在途决策）到本会话工作集。"
                + "适用：多轮任务的中途结论、当前进行到哪一步、待确认的临时判断。"
                + "与「长期记忆」的区别：这里的东西是本会话临时的，会话收尾会被整理，只有真正有长期价值的才留成记忆。"
                + "不要用它记用户偏好或项目既定事实（那类会被自动提炼）。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.MEMORY;
    }

    /** 写工作集（L3）：必须声明 writes——否则会被当成只读工具混进子代理的默认工具集 */
    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(JsonNode args) {
        if (args == null || args.path("body").asText("").trim().isEmpty()) {
            return "缺少 body：请给出要记的进度 / 临时结论";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("body")
                .put("type", "string")
                .put("description", "要记的内容：一句完整的中文陈述（进度 / 临时结论 / 待确认的判断）");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.put("description", "类型（缺省 progress）");
        kind.putArray("enum").add("progress").add("temp_conclusion")
                .add("tool_result").add("decision_in_flight");
        props.putObject("title")
                .put("type", "string")
                .put("description", "不超过 12 字的标题（可选）");
        params.putArray("required").add("body");
        return params;
    }
}
