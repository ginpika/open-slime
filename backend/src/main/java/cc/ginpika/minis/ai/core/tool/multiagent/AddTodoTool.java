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
        return "主控向共享任务区加入一项有明确边界的工作。title 写目标，detail 必须写边界、产出及验收方法，"
                + "owner 明确执行成员（名字或ID）；depends_on 使用已有条目ID。优先修订已有条目，避免同因补救任务重复扩张。"
                + "新增后不会自行开工，由主控用 start_work 指定本次执行和独立检查。";
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
        var dependencies=props.putObject("depends_on").put("type", "array")
                .put("description", "前置依赖：这一步要等哪几条 TODO 先完成（写它们的标题或 id）；"
                        + "没有先后关系就留空。前置未完成前这条不会被任何人开工");
        dependencies.putObject("items").put("type", "string");
        props.set("contract", contractSchema(om));
        params.putArray("required").add("title");
        return params;
    }

    static ObjectNode contractSchema(ObjectMapper om) {
        var schema=om.createObjectNode().put("type","object").put("description","可执行工作契约：预期成果、可检查标准、何时收工。滚动细化近期工作，远期方向留在计划里。");
        var fields=schema.putObject("properties");
        fields.putObject("outcome").put("type","string").put("description","能交付或回答什么问题");
        fields.putObject("stopCondition").put("type","string").put("description","达到什么条件即可收工，避免无限优化");
        var criteria=fields.putObject("acceptanceCriteria").put("type","array").put("minItems",1).putObject("items").put("type","object");
        var item=criteria.putObject("properties");
        item.putObject("id").put("type","string");item.putObject("text").put("type","string");
        criteria.putArray("required").add("id").add("text");
        schema.putArray("required").add("outcome").add("acceptanceCriteria").add("stopCondition");
        return schema;
    }
}
