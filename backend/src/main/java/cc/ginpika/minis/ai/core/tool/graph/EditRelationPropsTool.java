package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * edit_relation_props：新增/修改/删除关系属性。
 * 语义与 {@link EditEntityPropsTool} 一致——props 为「新增或覆盖」的 KV 列表（同名 k 覆盖其值），
 * remove_keys 为待删除的属性名。
 */
@Component
public class EditRelationPropsTool implements AiTool {

    public static final String NAME = "edit_relation_props";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "新增、修改或删除知识图谱中某条关系的 KV 属性。关系由 subject + predicate + object "
                + "三元组定位；props 中同名属性会被覆盖，remove_keys 中的属性会被删除。"
                + "仅在用户明确要求修改知识库内容时调用。";
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
        props.putObject("subject").put("type", "string").put("description", "关系主语实体名");
        props.putObject("predicate").put("type", "string").put("description", "关系词（谓词）");
        props.putObject("object").put("type", "string").put("description", "关系宾语实体名");
        AiTool.putPropArraySchema(props, "props", "要新增或覆盖的属性（可选）");
        AiTool.putStringArraySchema(props, "remove_keys", "要删除的属性名（可选）");
        params.putArray("required").add("subject").add("predicate").add("object");
        return params;
    }
}
