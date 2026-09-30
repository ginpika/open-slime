package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * read_document：按 id 分页读文档正文——每页字符数与页码由模型自选，
 * 后端实时切片并回带页信息（总字数/总页数/本页范围），让模型自己决定读多少、读哪里。
 */
@Component
public class ReadDocumentTool implements AiTool {

    public static final String NAME = "read_document";

    /** 读文档工具的分页参数：缺省每页字符数 */
    public static final int PAGE_DEFAULT = 2000;
    /** 读文档工具的分页参数：单页上限（防止一次读爆上下文） */
    public static final int PAGE_MAX = 4000;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按文档 id 分页读取知识库文档的正文，每页字符数与页码自选。返回本页正文与页信息"
                + "（总字数/总页数/本页字符范围，未读完会提示下一页）；要继续读就把 page 加一。"
                + "doc_id 来自 " + SearchDocumentsTool.NAME + " 的结果。";
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
        ObjectNode props = params.putObject("properties");
        props.putObject("doc_id").put("type", "integer").put("description", "文档 id");
        props.putObject("page").put("type", "integer").put("description", "页码，从 1 开始（缺省 1）");
        props.putObject("page_size").put("type", "integer")
                .put("description", "每页字符数，缺省 " + PAGE_DEFAULT + "，上限 " + PAGE_MAX);
        params.putArray("required").add("doc_id");
        return params;
    }
}
