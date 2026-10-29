package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ReadProjectKnowledgeTool implements AiTool {
    public static final String NAME="read_project_knowledge";
    public String name() { return NAME; }
    public String description() { return "读取当前协作项目的共享成果、现状和决策；list 搜索/分页索引，read 读全文或指定版本，history 读版本索引，progress 读实时未完成工作。只能访问当前项目，不读取其他会话私有记忆。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return false; }
    public ObjectNode parameters(ObjectMapper om) {
        var out=om.createObjectNode();out.put("type","object");var p=out.putObject("properties");
        p.putObject("action").put("type","string").putArray("enum").add("list").add("read").add("history").add("progress");
        p.putObject("key").put("type","string").put("description","read/history 必填：索引中的文档 key");
        p.putObject("query").put("type","string").put("description","list：标题和正文关键词");
        p.putObject("version").put("type","integer").put("minimum",1).put("description","read：不填读取最新版");
        p.putObject("offset").put("type","integer").put("minimum",0);
        p.putObject("limit").put("type","integer").put("minimum",1).put("maximum",50);
        p.putObject("include_retired").put("type","boolean");out.putArray("required").add("action");return out;
    }
}
