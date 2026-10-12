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
        return "查记忆：本系统关于你的长期记忆（画像 / 项目 / 情景 / 指令）。"
                + "默认返回已提炼的记忆条目（用户是谁、在做什么、答应过什么），按与 query 的语义相关度排序；"
                + "mode=event 时下钻原始事件流（说过/做过的原始对话与执行结论），供核对细节。"
                + "要纠正记错的内容用 update_memory；要忘掉某条用 forget_memory（两者都要先拿到 id）。"
                + "与 search_vector_chunks 的分工：那个查知识库文档，这个查关于用户 / 项目的事。";
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
                .put("description", "要回忆的内容，一句话即可（将与记忆做语义比对）");
        ObjectNode scope = props.putObject("scope");
        scope.put("type", "string");
        scope.put("description", "记忆类型过滤（缺省不限）");
        scope.putArray("enum").add("directive").add("profile").add("project").add("episode");
        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "item=记忆条目（缺省）；event=原始事件流下钻（核对细节用）");
        mode.putArray("enum").add("item").add("event");
        props.putObject("days")
                .put("type", "number")
                .put("description", "（仅 mode=event）时间窗：只查最近 N 天（缺省不限）");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.put("description", "（仅 mode=event）事件类型过滤（缺省不限）");
        kind.putArray("enum").add("chat").add("exec");
        props.putObject("limit")
                .put("type", "number")
                .put("description", "返回条数，1–30，默认 8");
        params.putArray("required").add("query");
        return params;
    }
}
