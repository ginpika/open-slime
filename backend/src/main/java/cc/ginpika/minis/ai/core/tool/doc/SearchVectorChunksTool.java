package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** search_vector_chunks：按关键词检索本地知识库 chunk（结果同时附带图谱结构证据） */
@Component
public class SearchVectorChunksTool implements AiTool {

    public static final String NAME = "search_vector_chunks";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "检索本地知识库的文档分块（chunk）。当用户问题可能涉及已导入的文档/资料时，"
                + "先用简短的关键词调用本工具获取相关片段，再结合片段内容回答；与知识库无关的闲聊无需调用。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.DOC;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("keywords")
                .put("type", "string")
                .put("description", "检索关键词，多个词用空格分隔");
        params.putArray("required").add("keywords");
        return params;
    }
}
