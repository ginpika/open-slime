package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** recall_memory：查记忆事件流——本系统「说过/做过」的统一时间线（执行见 KnowledgeToolbox 的分发） */
@Component
public class RecallMemoryTool implements AiTool {

    public static final String NAME = "recall_memory";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查记忆事件流：本系统「说过/做过」的统一时间线（用户提问、回答、计划与子代理的执行结论）。"
                + "当需要回忆之前聊过什么、某件事当时的结论、或按时间/主角翻旧账时调用；"
                + "结果按与 query 的语义相关度排序（向量不可用时按时间倒序）。"
                + "与 search_vector_chunks 的分工：那个查知识库文档，这个查发生过的事。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.MEMORY;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("query")
                .put("type", "string")
                .put("description", "要回忆的内容，一句话即可（将与事件做语义比对）");
        props.putObject("days")
                .put("type", "number")
                .put("description", "时间窗：只查最近 N 天（缺省不限，取最近一段事件）");
        ObjectNode kind = props.putObject("kind")
                .put("type", "string")
                .put("description", "事件类型过滤（缺省不限）");
        kind.putArray("enum").add("chat").add("exec");
        ObjectNode actor = props.putObject("actor")
                .put("type", "string")
                .put("description", "主角过滤（缺省不限）");
        actor.putArray("enum").add("user").add("assistant")
                .add("heartbeat").add("plan").add("agent");
        props.putObject("limit")
                .put("type", "number")
                .put("description", "返回条数，1–30，默认 8");
        params.putArray("required").add("query");
        return params;
    }
}
