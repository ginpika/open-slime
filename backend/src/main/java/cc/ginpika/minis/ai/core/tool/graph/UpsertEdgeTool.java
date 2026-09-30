package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** upsert_edge：写入 实体-关系-实体 边（对话与文档分析的入库流程共用） */
@Component
public class UpsertEdgeTool implements AiTool {

    public static final String NAME = "upsert_edge";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写入一条 实体-关系-实体 边；两端实体不存在会按字面名自动创建（CONCEPT），"
                + "同一条关系已存在则幂等跳过";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.COMMIT);
    }

    @Override
    public boolean writes() {
        return true;
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
        props.putObject("subject").put("type", "string").put("description", "关系主语实体名");
        props.putObject("predicate").put("type", "string").put("description", "关系词（谓词）");
        props.putObject("object").put("type", "string").put("description", "关系宾语实体名");
        params.putArray("required").add("subject").add("predicate").add("object");
        return params;
    }
}
