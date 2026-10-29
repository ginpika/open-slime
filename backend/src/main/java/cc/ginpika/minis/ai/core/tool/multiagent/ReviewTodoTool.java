package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Only offered during an independently assigned review. */
@Component
public class ReviewTodoTool implements AiTool {
    public static final String NAME = "review_todo";
    public String name() { return NAME; }
    public String description() { return "检查当前待办的实际产出后提交检查结论。accept=true 通过；false 按具体问题返工。必须说明检查了哪些证据和结果，不能仅转述执行者的自述。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return true; }
    public ObjectNode parameters(ObjectMapper om) {
        var schema = om.createObjectNode().put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("id").put("type", "string");
        props.putObject("accept").put("type", "boolean");
        props.putObject("evidence").put("type", "string").put("description", "实际检查的文件、命令、测试或其他证据，以及发现；失败时包含返工要求");
        var finding=props.putObject("findings").put("type","array").put("description","结构化检查意见；BLOCKER 阻碍验收，MINOR 作为通过后的建议。引用契约标准或具体正确性风险，不因措辞偏好返工。").putObject("items").put("type","object");
        var fields=finding.putObject("properties");
        for(String key: new String[]{"id","location","expected","basis"})fields.putObject(key).put("type","string");
        fields.putObject("severity").put("type","string").putArray("enum").add("BLOCKER").add("MINOR");
        finding.putArray("required").add("id").add("severity").add("location").add("expected").add("basis");
        schema.putArray("required").add("id").add("accept").add("evidence");
        return schema;
    }
}
