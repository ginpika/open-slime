package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * delete_graph_item：按 target 分派——entity 按实体名删除该实体（连带它参与的全部关系），
 * relation 按 subject + predicate + object 三元组删除一条关系。删除不可逆，仅供用户明确要求时调用。
 */
@Component
public class DeleteGraphItemTool implements AiTool {

    public static final String NAME = "delete_graph_item";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "从本地知识图谱中删除一个实体或一条关系（不可逆，删除后无法恢复）。"
                + "target=entity 时按 entity 删除该实体，并连带删除它参与的全部关系；"
                + "target=relation 时按 subject + predicate + object 删除一条关系。"
                + "系统预置的锚点实体（如「用户」）不可删除，会被拒绝；其上的关系可正常删除。"
                + "仅在用户明确要求删除知识库内容时调用；删除前建议先用 "
                + SearchGraphEntitiesTool.NAME + " / " + QueryEntityRelationsTool.NAME
                + " 确认要删的对象。";
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
        ObjectNode target = props.putObject("target");
        target.put("type", "string");
        target.put("description", "删除对象类型：entity=实体（连带其全部关系），relation=一条关系");
        target.putArray("enum").add("entity").add("relation");
        props.putObject("entity").put("type", "string")
                .put("description", "要删除的实体名（target=entity 时必填）");
        props.putObject("subject").put("type", "string")
                .put("description", "关系主语实体名（target=relation 时必填）");
        props.putObject("predicate").put("type", "string")
                .put("description", "关系词（谓词）（target=relation 时必填）");
        props.putObject("object").put("type", "string")
                .put("description", "关系宾语实体名（target=relation 时必填）");
        params.putArray("required").add("target");
        return params;
    }
}
