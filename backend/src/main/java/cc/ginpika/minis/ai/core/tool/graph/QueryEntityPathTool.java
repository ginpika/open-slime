package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** query_entity_path：查询两实体间的最短路径 */
@Component
public class QueryEntityPathTool implements AiTool {

    public static final String NAME = "query_entity_path";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询知识图谱中两个实体之间的最短路径（最多返回 3 条，按跳数升序）。"
                + "用于回答「A 和 B 是什么关系 / 两者如何关联」这类需要跨多跳推理的问题；"
                + "source / target 传实体名，可用 " + SearchGraphEntitiesTool.NAME + " 的返回结果。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.GRAPH;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("source").put("type", "string").put("description", "起点实体名");
        props.putObject("target").put("type", "string").put("description", "终点实体名");
        params.putArray("required").add("source").add("target");
        return params;
    }
}
