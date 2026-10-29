package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ManagePlanTool implements AiTool {
    public static final String NAME = "manage_plan";
    public String name() { return NAME; }
    public String description() { return "仅主控使用：revise 保存整体验收标准和调整依据；finish 在全部工作和议题结束后，附实际整体验证证据确认交付。"; }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.PEER); }
    public boolean writes() { return true; }
    public ObjectNode parameters(ObjectMapper om) {
        var out = om.createObjectNode(); out.put("type", "object");
        var p = out.putObject("properties");
        p.putObject("action").put("type", "string").putArray("enum").add("revise").add("finish");
        p.putObject("criteria").put("type", "string").put("description", "revise 必填：仅本次会话的完成条件、保留行为及验收方法；不能扩大为项目终局目标");
        p.putObject("deliverables").put("type","string").put("description","revise 必填：本次实际交付物");
        p.putObject("exclusions").put("type","string").put("description","revise 必填：本次不包含的工作，明确未来需求边界");
        p.putObject("baseline").put("type","string").put("description","revise 必填：已实查的现状、工具能力与证据；未知项明确标未知");
        p.putObject("roadmap").put("type","string").put("description","后续方向，只记录不自动派工；取得本步证据后再细化");
        p.putObject("note").put("type", "string").put("description", "revise 必填：创建或调整计划的依据");
        p.putObject("evidence").put("type", "string").put("description", "finish 必填：实际整体检查方法、结果和产出位置");
        out.putArray("required").add("action"); return out;
    }
}
