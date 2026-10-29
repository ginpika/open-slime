package cc.ginpika.minis.ai.core.tool.graph;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** upsert_node：写入/更新实体节点（对话与文档分析的入库流程共用） */
@Component
public class UpsertNodeTool implements AiTool {

    public static final String NAME = "upsert_node";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写入或更新一个实体节点：不存在则新建；同名实体不重复创建，"
                + "类型并入全量清单（主类型保持首次写入），别名/摘要按本次提交覆盖";
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
        props.putObject("name").put("type", "string").put("description", "实体名（原文表述）");
        props.putObject("identity_key").put("type","string").put("description","同名实体的稳定身份键；确认它们是不同对象时分别使用不同键，更新时复用该键");
        props.putObject("type").put("type", "string")
                .put("description", "实体类型，缺省 CONCEPT；实体可兼具多类身份，多类型用逗号分隔（如 PERSON,CHARACTER）");
        props.putObject("summary").put("type", "string").put("description", "一句话摘要（可选）");
        AiTool.putStringArraySchema(props, "aliases", "实体别名/简称/全称/外文名等他称（可选）");
        params.putArray("required").add("name");
        return params;
    }
}
