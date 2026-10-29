package cc.ginpika.minis.ai.core.tool.doc;

import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ListDocumentsTool implements AiTool {
    public static final String NAME = "list_documents";
    public String name() { return NAME; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT); }
    public ToolDomain domain() { return ToolDomain.DOC; }
    public String description() {
        return "分页列举本地资料目录，不需要关键词。source=snapshot 列已保存快照，doc 列内部文档，blog 列文章；省略列全部。"
                + "可按标题关键词、检索启用状态和创建时间筛选，按创建时间及 ID 倒序。返回 items、has_more、next_cursor；"
                + "继续列举时保持筛选不变并传回 cursor。每项包含 doc_id、snapshot_id（如适用）、local_url 和摘要。"
                + "用 read_document 读正文，用 read_web_snapshot 读快照元信息、论点和证据；浏览器活跃页面用 web_browser.list。";
    }
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode p = om.createObjectNode().put("type", "object");
        directoryProperties(p.putObject("properties"));
        return p;
    }
    static void directoryProperties(ObjectNode f) {
        f.putObject("keywords").put("type", "string").put("description", "可选标题关键词，多词空格分隔且同时命中；省略可浏览全库");
        f.putObject("source").put("type", "string").putArray("enum").add("doc").add("blog").add("snapshot");
        f.putObject("status").put("type", "string").put("description", "检索启用状态，默认 all；不代表文档权限或分析完成状态")
                .putArray("enum").add("all").add("enabled").add("disabled");
        f.putObject("cursor").put("type", "string").put("description", "上一页 next_cursor，首次省略；翻页时保持筛选不变");
        f.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", 30).put("description", "每页条数，默认 10，上限 30");
        for (String key : new String[]{"created_from", "created_to"})
            f.putObject(key).put("type", "integer").put("description", "创建时间 Unix 毫秒，包含边界");
    }
}
