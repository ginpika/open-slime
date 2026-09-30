package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** query_entity_relations：展开某实体的一跳关系 */
@Component
public class QueryEntityRelationsTool implements AiTool {

    public static final String NAME = "query_entity_relations";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询指定实体在知识图谱中的一跳关系：该实体作为主语或宾语的全部边"
                + "（关系词 + 另一端实体名与类型）。entity 传实体名，可用 "
                + SearchGraphEntitiesTool.NAME + " 的返回结果。";
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
        params.putObject("properties").putObject("entity")
                .put("type", "string")
                .put("description", "实体名");
        params.putArray("required").add("entity");
        return params;
    }
}
