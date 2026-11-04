package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * start_work：讨论定了——宣布「该开工了」，让各成员的执行线程去消费共享任务区。
 *
 * <p>它是「讨论态 → 执行态」的开关。调用它的那一刻，运行时会给任务区里有待办的成员
 * 各起一个执行线程（每个线程按序完成自己名下的条目，每条一次执行）。讨论仍可继续，
 * 两态并行——直到有人遇阻发起讨论（见 {@link RequestDiscussionTool}）。
 */
@Component
public class StartWorkTool implements AiTool {

    public static final String NAME = "start_work";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "主控派工：指定 id（待办ID）、reviewer（独立检查成员名或ID）与 note（本次依据），"
                + "或用 assignments=[{id,reviewer},...] 一批派出 1–8 项已就绪、互不依赖的工作。相同默认 Agent 配置也能并行执行多个独立副本，逐项检查合入，整批回报主控。"
                + "共享文件修改或有前后依赖的工作应分批。不会自动执行剩余任务；先明确负责人和整体验收标准。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("id").put("type", "string").put("description", "本次派工的待办ID");
        props.putObject("reviewer").put("type", "string").put("description", "另一位检查成员的名字或ID，不能是执行者");
        ObjectNode batch=props.putObject("assignments").put("type","array").put("minItems",1).put("maxItems",8);
        ObjectNode item=batch.putObject("items").put("type","object");
        ObjectNode fields=item.putObject("properties");
        fields.putObject("id").put("type","string"); fields.putObject("reviewer").put("type","string");
        item.putArray("required").add("id").add("reviewer"); item.put("additionalProperties",false);
        props.putObject("note").put("type", "string")
                .put("description", "开工说明（一句话：按什么共识开工、先做哪些）");
        return params;
    }
}
