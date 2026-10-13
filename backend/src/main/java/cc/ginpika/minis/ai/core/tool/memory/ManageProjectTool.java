package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ManageProjectTool implements AiTool {
    public static final String NAME="manage_project";
    public String name() { return NAME; }
    public String description() {
        return "管理当前对话的项目与续接。list 查已有项目和同 Agent 会话；suggest 只建议、不创建。"
                +"用户明确要求后，create 创建或复用同名项目并将当前会话整理、绑定；bind 绑定已有项目。"
                +"continue 在当前会话续接指定同 Agent、同项目（或均无项目）的来源会话；只有用户要求继续时才授权。"
                +"progress 读取已授权的最近进度；read 分页读取已授权来源对话（page=1 最新，逐页向前）。"
                +"长消息可用 message_id 与 offset 分段读取，每段 8000 字，totalChars 给出总长。"
                +"revoke 取消指定来源授权。多个来源可能匹配时先请用户选择，不自行猜测。"
                +"小说/剧本用 kind=story 或 novel，创作设定不进入真实用户画像。绑定从下一轮生效，工具返回的进度可立即用于本轮。";
    }
    public Set<ToolSurface> surfaces() { return Set.of(ToolSurface.CHAT); }
    public ToolDomain domain() { return ToolDomain.MEMORY; }
    public boolean writes() { return true; }
    public boolean agentsMayCall() { return false; }
    public String validate(JsonNode args) {
        if (args==null || !Set.of("list","suggest","create","bind","continue","progress","read","revoke")
                .contains(args.path("action").asText(""))) { return "请提供有效的 action"; }
        return null;
    }
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode root=om.createObjectNode(); root.put("type","object");
        ObjectNode props=root.putObject("properties");
        props.putObject("action").put("type","string").putArray("enum")
                .add("list").add("suggest").add("create").add("bind").add("continue").add("progress").add("read").add("revoke");
        props.putObject("name").put("type","string").put("description","项目名；create 必填，同名复用");
        props.putObject("proposed_action").put("type","string").put("description","suggest 建议的操作，默认 create；用户确认后执行相同参数")
                .putArray("enum").add("create").add("bind").add("continue");
        props.putObject("kind").put("type","string").putArray("enum").add("other").add("software").add("research").add("novel").add("story").add("rpg");
        props.putObject("note").put("type","string").put("description","可选项目简介");
        props.putObject("project_id").put("type","string");
        props.putObject("source_session_id").put("type","string");
        props.putObject("page").put("type","integer").put("minimum",1);
        props.putObject("message_id").put("type","integer").put("description","read 时读取指定来源消息");
        props.putObject("offset").put("type","integer").put("minimum",0).put("description","指定消息的字符偏移，默认 0");
        root.putArray("required").add("action"); return root;
    }
}
