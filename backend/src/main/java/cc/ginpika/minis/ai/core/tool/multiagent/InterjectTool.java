package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * interject：抢答——「我有一件事必须现在说，请让出话头」。
 *
 * <p>圆桌默认一人说完再轮到下一个。抢答是这条默认的例外：当前正在发言的成员会就此打住，
 * 把发言权交给抢答者。所以它是有代价的动作——被打断那位已经说出口的话就停在那里了，
 * 只在「再不说就误事」时才用（例如对方的结论建立在错误前提上、或漏掉了关键约束）。
 *
 * <p>与人在外面按键打断不同：那是人对整场对话的打断，这个是成员之间的插话，
 * 两者落的是同一张事件表里不同 actor 的 interrupt。
 */
@Component
public class InterjectTool implements AiTool {

    public static final String NAME = "interject";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "抢答：要求立刻发言，当前正在发言的成员会让出话头。"
                + "默认秩序是一人说完再轮到下一个，抢答是例外——被打断的话就停在那里了，"
                + "所以只在确有要紧事时用：前提错了、漏了关键约束、结论会被推翻。"
                + "若只是想补充或换个角度，等轮到你再说更合适。"
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
                .put("description", "一句话说明为什么必须现在插话（会记在话题事件里，供被打断者与人事后回看）");
        params.putArray("required").add("reason");
        return params;
    }
}
