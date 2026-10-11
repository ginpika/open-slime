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
        return "宣布「讨论到此，开始执行」：让桌上各成员的执行线程开工，按顺序完成共享任务区里各自"
                + "名下的待办（每一条 = 一次独立执行）。只有在讨论确实得出可执行结论、且任务区已有条目时"
                + "才调用它。开工后讨论不会停——成员仍可继续发言、增删改任务区；执行途中若遇阻，"
                + "由遇阻者调用 " + RequestDiscussionTool.NAME + " 请大家再议。note 里写一句开工说明。";
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
        props.putObject("note").put("type", "string")
                .put("description", "开工说明（一句话：按什么共识开工、先做哪些）");
        return params;
    }
}
