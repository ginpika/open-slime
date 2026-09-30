package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * ask_user：请用户表态——「这一步需要你来定」。
 *
 * <p>它不是成员之间的表态。propose_end / go_silent / interject 都是圆桌内部的动作，圆桌自己就能
 * 往下走；这一条把决定权交回给人：需要用户的态度、许可或取舍（该选哪条路、能不能做、做到什么程度、
 * 边界在哪里）时用它。调用之后全桌暂停，等用户发下一句再继续。
 *
 * <p><b>人答完之后按人说的办</b>：不要绕过去自己拍板，也不要把已经问过的事再问一遍。这是这个工具
 * 存在的意义——否则「问」只是拖延。
 */
@Component
public class AskUserTool implements AiTool {

    public static final String NAME = "ask_user";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "请用户表态：这一步需要用户来定。当你要用户的态度、许可或取舍才能继续时调用"
                + "（该选哪条路、能不能做、做到什么程度、边界在哪里）。"
                + "调用后全桌暂停，等用户发下一句再继续；用户答完之后按用户说的办，"
                + "不要绕过去自己拍板，也不要把问过的事再问一遍。"
                + "只是想听同伴意见、自己还能往下推的，别用它——继续发言或先沉默即可。"
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
                .put("description", "一句话说明要用户定什么（会记进话题事件与公开记录，用户据此作答）");
        params.putArray("required").add("reason");
        return params;
    }
}
