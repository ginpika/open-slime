package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** edit_entity_props：新增/修改/删除实体属性 */
@Component
public class EditEntityPropsTool implements AiTool {

    public static final String NAME = "edit_entity_props";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "新增、修改或删除知识图谱中某个实体的 KV 属性。props 中同名属性会被覆盖，"
                + "remove_keys 中的属性会被删除。仅在用户明确要求修改知识库内容时调用。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
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
        props.putObject("entity").put("type", "string")
                .put("description", "目标实体名，可用 " + SearchGraphEntitiesTool.NAME + " 的返回结果");
        AiTool.putPropArraySchema(props, "props", "要新增或覆盖的属性（可选）");
        AiTool.putStringArraySchema(props, "remove_keys", "要删除的属性名（可选）");
        params.putArray("required").add("entity");
        return params;
    }
}
