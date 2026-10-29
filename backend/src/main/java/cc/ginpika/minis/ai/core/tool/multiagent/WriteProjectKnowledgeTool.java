package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class WriteProjectKnowledgeTool implements AiTool {
    public static final String NAME="write_project_knowledge";
    public String name() { return NAME; }
    public String description() { return "仅当前协作主控保存项目共享文档。overview 维护项目现状/阶段/阻塞/下一步；decision 记录决策、理由和替代方案；reference 记录成果位置和使用方法。内容为 Markdown，必须区分已验证、未验证、建议。修订前先读取最新版；每次保存保留历史。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return true; }
    public ObjectNode parameters(ObjectMapper om) {
        var out=om.createObjectNode();out.put("type","object");var p=out.putObject("properties");
        p.putObject("key").put("type","string").put("description","稳定文档标识，如 overview 或 decision-storage；字母数字点横线下划线，最多 120 字");
        p.putObject("kind").put("type","string").putArray("enum").add("overview").add("decision").add("reference");
        p.putObject("title").put("type","string").put("maxLength",160);
        p.putObject("content").put("type","string").put("maxLength",40000).put("description","完整文档；保留仍有效的信息，补充依据、成果位置、限制和后续建议");
        p.putObject("expected_version").put("type","integer").put("minimum",0).put("description","新文档为 0；修订为刚读到的版本号，冲突后重新读取并合并");
        p.putObject("retired").put("type","boolean").put("description","已过期/被替代资料可设为 true，保留历史但不再作为默认交接资料");
        out.putArray("required").add("key").add("kind").add("title").add("content").add("expected_version");return out;
    }
}
