package cc.ginpika.minis.ai.core.tool.analyze;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** submit_triples：一次性提交三元组清单（含两端实体的 KV 属性与别名） */
@Component
public class SubmitTriplesTool implements AiTool {

    public static final String NAME = "submit_triples";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "提交从文档中抽取的三元组清单";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.EXTRACT);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode triples = props.putObject("triples");
        triples.put("type", "array");
        triples.put("description", "实体-关系-实体 三元组列表");
        ObjectNode item = triples.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("subject").put("type", "string");
        ip.putObject("predicate").put("type", "string");
        ip.putObject("object").put("type", "string");
        ip.putObject("subject_type").put("type", "string")
                .put("description", "实体 subject 的类型，大写英文单词（如 CHARACTER/PERSON/ORG/PLACE/ITEM/EVENT/WORK/CONCEPT），按语义自由判断");
        ip.putObject("object_type").put("type", "string")
                .put("description", "实体 object 的类型，同 subject_type");
        AiTool.putPropArraySchema(ip, "subject_props", "实体 subject 的 KV 属性（如 学号=38号）");
        AiTool.putPropArraySchema(ip, "object_props", "实体 object 的 KV 属性");
        AiTool.putStringArraySchema(ip, "subject_aliases",
                "实体 subject 的别名/简称/全称/外文名等他称（用于检索命中，别名不要写进 props）");
        AiTool.putStringArraySchema(ip, "object_aliases", "实体 object 的别名/简称/全称/外文名等他称");
        item.putArray("required").add("subject").add("predicate").add("object");
        props.putObject("summary").put("type", "string")
                .put("description", "对文档内容的 1~2 句概括（可选）");
        params.putArray("required").add("triples");
        return params;
    }
}
