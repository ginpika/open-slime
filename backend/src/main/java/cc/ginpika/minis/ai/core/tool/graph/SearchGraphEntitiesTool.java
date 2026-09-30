package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** search_graph_entities：按实体名/别名关键词查找图谱实体 */
@Component
public class SearchGraphEntitiesTool implements AiTool {

    public static final String NAME = "search_graph_entities";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在本地知识图谱中按关键词（实体名或别名）查找实体，返回匹配实体的类型、摘要与属性。"
                + "问题涉及具体人物/组织/概念及其属性时，先用本工具定位实体，再用 "
                + QueryEntityRelationsTool.NAME + " 展开其关系。";
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
        params.putObject("properties").putObject("keywords")
                .put("type", "string")
                .put("description", "实体名或别名关键词，多个词用空格分隔");
        params.putArray("required").add("keywords");
        return params;
    }
}
