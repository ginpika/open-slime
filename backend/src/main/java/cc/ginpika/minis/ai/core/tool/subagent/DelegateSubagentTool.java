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
        return "派一个「子代理」独立去完成一件活：它只拿到你写的这段交代，看不到当前对话与历史；"
                + "它有自己的工具（默认只读检索），能自行查知识库、读文档、查图谱，最后只交回一段结论。"
                + "派活不占住你：在对话里当场只回一张受理回执（含执行 id），它跑完会把结论作为一条"
                + "新消息投回本会话并打未读点——所以别干等它，先答你能答的、或直接收尾；"
                + "心跳里则由它当场把结论交给你，你拿它继续裁量这一拍。"
                + "适合需要多步检索、或结果很长会挤占当前上下文的调研类任务；"
                + "闲聊与一句话能答完的问题不要用它。同一条消息里可以一次派好几个。"
                + "需要反复做、要有固定做法的活，立一个任务计划（" + CreatePlanTool.NAME + "）更合适。";
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
