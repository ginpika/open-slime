package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * propose_end：提议结束当前话题——「我这边说完了，这个话题可以收了」。
 *
 * <p>圆桌上没有主持人，收场靠共识：每个成员各自表态，全体都提议结束后话题才关闭。
 * 所以这个工具不是「终止命令」，而是「我这一票」。若还有人反对（继续发言），
 * 已经提议结束的人会重新拿到发言权、重新决策——它是一次可以被推翻的表态，不是不可逆的裁决。
 *
 * <p>该表什么态：你已经把要说的说完了，或确认这个话题与自己无关、没有可补充的。
 * 不该随便用：还有没查清、没说完的事，就先别投票，接着发言或先沉默。
 */
@Component
public class ProposeEndTool implements AiTool {

    public static final String NAME = "propose_end";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "提议结束当前话题：表示你这边说完了、认同这个话题可以收了。收场靠共识——"
                + "所有成员都提议结束后，话题才关闭；只要还有人在发言，你这一票会被撤回并"
                + "重新决策（因为新发言可能推翻你的结论）。"
                + "还有没说完、没查清的事就先别投，接着发言或先沉默。"
                + "调用本工具即声明你这一轮到此为止：要说的话放在同一次输出的 speech 字段里，"
                + "之后不要再补充说明。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("reason").put("type", "string")
                .put("description", "一句话说明为什么可以结束了（可选，会记在话题事件里供回看）");
        return params;
    }
}
