package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * create_document：把一段 markdown 正文新建为知识库文档，由 DocumentStore 按标题/段落自动分块入库
 * （随即进入关键词检索可达范围）。
 */
@Component
public class CreateDocumentTool implements AiTool {

    public static final String NAME = "create_document";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "在本地知识库中新建一篇文档并自动分块入库（随后可被检索）。content 为 markdown 正文，"
                + "建议用 # 标题与空行分段以利于分块。仅在用户明确要求把内容写成新文档/笔记时调用。";
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
        return ToolDomain.DOC;
    }

    /** 校验节点（STEP 4）：标题 / 正文任一为空都当场拒——空文档入不了检索，只会白占一次分块入库 */
    @Override
    public String validate(JsonNode args) {
        if (args.path("title").asText("").isBlank()) {
            return "title 为空：请给文档起一个标题（检索与列表都按它指认）。";
        }
        if (args.path("content").asText("").isBlank()) {
            return "content 为空：请给出文档正文（markdown）。";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("title").put("type", "string").put("description", "文档标题");
        props.putObject("content").put("type", "string").put("description", "文档正文（markdown）");
        params.putArray("required").add("title").add("content");
        return params;
    }
}
