package cc.ginpika.minis.ai.core.tool.subagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.heartbeat.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DelegatePlanTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * delegate_subagent：派一个「子代理」独立去干一件活，拿回它的结论。
 *
 * <p>子代理是匿名的一次性 worker：不建档案、跑完即逝，下一次派活就是全新的一个。派它时只需给一段交代——
 * 它看不到当前对话，也没有历史；它有自己的工具（默认只读检索），最后只交回一段结论。
 * 这一层是主流 sub-agent 的语义：独立上下文 + 窄工具集 + 结论回流。
 *
 * <p>派活不阻塞调用方：对话里当场只回一张受理回执，结论稍后作为一条新消息投回本会话；
 * 心跳里则由它当场交出结论（心跳主会话要拿它继续裁量这一拍）。
 *
 * <p>与「任务计划」（{@link DelegatePlanTool}）的区别：计划是常驻编制（有名字、有排班、
 * 能被反复派），子代理是临时的兵。一次性的活派子代理；反复要做的活才值得立一个计划。
 */
@Component
public class DelegateSubagentTool implements AiTool {

    public static final String NAME = "delegate_subagent";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "委派一个独立上下文的临时协作者，适合多步检索、候选比较、长文档提炼和独立证据核查。"
                + "它看不到当前对话：交代目标、范围、已知事实和预期证据，只取回结论与执行 id。"
                + "对话默认 background=true，后台执行后回报本会话；当前决策依赖调查结果时用 background=false，直接返回结论继续判断。"
                + "心跳中始终同步取回结论。同一条消息可派多个独立问题；简单工作直接完成，不机械委派。"
                + "协作项目中只读调查，代码实施交 coding_task 独立副本，不能借子代理绕过派工、独立检查或扩大计划。"
                + "需要长期反复运行的职责才使用任务计划（" + CreatePlanTool.NAME + "）。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    /** 子代理不能再派子代理：派活是「大模型自己那一层」的事，不然会一层套一层 */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    /** 互不相干的几件活：一条消息里派多个子代理时并发下发（对话侧本身已转后台跑，
     *  这里省下的只是受理回执之间的串行等待；心跳侧则是几件活同时开跑） */
    @Override
    public boolean parallelizable() {
        return true;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("background").put("type","boolean").put("description","默认 true 后台回报；false 在本次工具返回结论，供主控继续判断");
        props.putObject("instruction").put("type", "string")
                .put("description", "交给子代理的交代：要它查什么、产出什么。它看不到当前对话，"
                        + "所以该给的前提、该要的产出都要写全");
        props.putObject("name").put("type", "string")
                .put("description", "给它起个短名（展示用，便于在运行记录里指认）；不填则从交代里自动截取");
        AiTool.putStringArraySchema(props, "tools",
                "允许它调用的工具名；不填 = 只读检索（search_vector_chunks / search_graph_entities / "
                        + "query_entity_relations / query_entity_path / explore_subgraph / "
                        + "search_documents_by_title / read_document）。要它写知识库才需要显式点名写库工具");
        params.putArray("required").add("instruction");
        return params;
    }
}
