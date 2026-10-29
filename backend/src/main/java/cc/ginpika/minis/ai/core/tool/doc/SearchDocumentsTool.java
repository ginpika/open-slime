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
        return "按关键词匹配本地资料标题，包含文档、文章和已保存快照，返回带 doc_id 的目录，不含正文。"
                + "多个关键词用空格分隔，需同时命中标题。支持来源、状态、时间筛选和 cursor 分页，按创建时间及 ID 倒序；"
                + "用返回的 next_cursor 继续查询。无关键词浏览资料用 list_documents。标题没命中不代表正文没有相关内容；"
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
        ListDocumentsTool.directoryProperties(params.putObject("properties"));
        params.putArray("required").add("keywords");
        return params;
    }
}
