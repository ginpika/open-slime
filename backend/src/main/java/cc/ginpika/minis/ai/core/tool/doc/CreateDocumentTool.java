package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
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
