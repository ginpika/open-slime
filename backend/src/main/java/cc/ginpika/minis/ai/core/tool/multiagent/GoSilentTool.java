package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * go_silent：转入沉默——「我还在听，但这一轮不打算发言」。
 *
 * <p>它表达的是「让位给别人」，不是「退出话题」：状态仍是参与者，别人的发言照常进入你的视野，
 * 你随时可以直接发言重新加入。与 {@link ProposeEndTool} 的区别是后者在给话题投结束票，
 * 而沉默只是这一轮不开口。
 *
 * <p>该表什么态：别人正在说的正是关键、你的话现在说出来只是重复或打断；或者你在等某个前提。
 * 用它的结果是：本轮的发言权顺位往后传给下一个人。
 */
@Component
public class GoSilentTool implements AiTool {

    public static final String NAME = "go_silent";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "转入沉默：继续旁听这一轮，但不发言——把机会让给其他人。"
                + "你随时可以直接开口重新参与，不必先取消沉默。"
                + "想表达「这个话题与我无关、可以收了」请用 " + ProposeEndTool.NAME + "（那是投结束票）。"
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
                .put("description", "一句话说明为什么这一轮先不出声（可选，会记在话题事件里供回看）");
        return params;
    }
}
