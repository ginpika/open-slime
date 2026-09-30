package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** explore_subgraph：PPR 单步多跳子图探索 */
@Component
public class ExploreSubgraphTool implements AiTool {

    public static final String NAME = "explore_subgraph";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "从若干实体出发在知识图谱中做相关度扩散（个性化 PageRank），一次返回与它们最相关的"
                + "周边实体、其间的关系子图与关联文档片段。适合「A 和 B 是什么关系」「与 X 相关的"
                + "都有哪些」这类多跳/聚合问题——优先用本工具，比逐实体展开更省轮次。"
                + "entities 传 1~4 个实体名，可用 " + SearchGraphEntitiesTool.NAME + " 的返回结果。";
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
        ObjectNode entities = params.putObject("properties").putObject("entities");
        entities.put("type", "array");
        entities.put("description", "出发实体名列表（1~4 个）");
        entities.putObject("items").put("type", "string");
        params.putArray("required").add("entities");
        return params;
    }
}
