package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * add_todo：往圆桌的<b>共享任务区</b>加一条待办。
 *
 * <p>这是「讨论态 → 执行态」的入口之一：讨论中你判断出「这件事需要有人去做」，就在这里把它落成一条
 * TODO。任务区是会话级共享的——桌上所有人（以及他们的执行线程）都看得见，都可以继续增删改。
 *
 * <p>可以指定负责人（owner）；不指定就是公共条目，谁的执行线程先取到谁做。
 */
@Component
public class AddTodoTool implements AiTool {

    public static final String NAME = "add_todo";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "往圆桌共享任务区加一条待办：把「需要有人去做的事」落成一条可追踪的 TODO，"
                + "桌上所有人都看得见、都能改。讨论出结论后，用 " + StartWorkTool.NAME + " 让各成员开工，"
                + "他们的执行线程会按顺序把任务区的条目做掉（每条一次执行）。"
                + "title 写清要做什么、detail 写清验收标准或前提；owner 可指定负责人（写成员名），"
                + "不指定则为公共条目。若这一步必须等别的条目先完成，用 depends_on 声明前置"
                + "（写那些条目的标题或 id）：前置没完成前没有人会开工这条，"
                + "所以分工上有先后的活请务必声明——不然下游会在没有上游产出时白跑一趟。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    /** 改共享任务区（有副作用） */
    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(com.fasterxml.jackson.databind.JsonNode args) {
        String title = args == null ? "" : args.path("title").asText("").strip();
        return title.isEmpty() ? "title 不能为空：写清这一步要做什么。" : null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("title").put("type", "string")
                .put("description", "这一步要做什么（一句祈使句）");
        props.putObject("detail").put("type", "string")
                .put("description", "补充说明 / 验收标准 / 前提（可空）");
        props.putObject("owner").put("type", "string")
                .put("description", "负责人：写成员名（须是本桌成员）；不填 = 公共，谁有空谁做");
        props.putObject("depends_on").put("type", "array")
                .put("description", "前置依赖：这一步要等哪几条 TODO 先完成（写它们的标题或 id）；"
                        + "没有先后关系就留空。前置未完成前这条不会被任何人开工");
        props.putObject("depends_on").putObject("items").put("type", "string");
        params.putArray("required").add("title");
        return params;
    }
}
