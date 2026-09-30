package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * search_documents_by_title：按关键词匹配文档标题（多词 AND），只返回文档清单（标题/来源/摘要），
 * 不返回正文——要正文片段走 RAG 检索工具。
 */
@Component
public class SearchDocumentsTool implements AiTool {

    public static final String NAME = "search_documents_by_title";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按关键词匹配文档标题，在本地知识库中查找文档（返回标题/摘要/来源，不含正文）。"
                + "多个关键词用空格分隔，需同时命中标题。想确认「有没有这篇文档」「哪篇文档讲过 X」时用它；"
                + "要找正文片段用 " + SearchVectorChunksTool.NAME + "。";
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
                .put("description", "标题关键词，多个词用空格分隔（需同时命中）");
        params.putArray("required").add("keywords");
        return params;
    }
}
