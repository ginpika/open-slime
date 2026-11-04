package cc.ginpika.minis.coding.integration;

import cc.ginpika.minis.ai.core.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared agent gateway; project execution remains in the coding runtime. */
@Component
public class CodingTaskTool implements AiTool {
    public static final String NAME="coding_task";
    public String name(){return NAME;}
    public String description(){return "编码任务入口。list 列出本项目、executionAuthorized 和当前待办的 collaborationRuns；主控可用 start_work 的 assignments 批量派发独立待办，同一默认 Agent 配置的各次执行使用独立副本，运行名额不足会排队。普通聊天 start 仅 PLAN/EDIT，有效执行派工可 start(mode=EXECUTE) 修改副本并运行命令。read 等待同一 runId（最多 30 秒）；diff/operations 读取实际差异与工具证据；message/resume(mode=EXECUTE) 继续同一副本；cancel 停止。执行者必须要求 run_validation 绑定当前版本。检查者用 verify 整合最新项目中的其他改动并独立复跑验证，必须重新读取整合后的 diff/operations，再 review_todo 接受。相同路径的并发修改会报 APPLICATION_CONFLICT，保留双方内容交主控整合，不能强行覆盖。不能自行提交、推送或部署。";}

    public Set<ToolSurface> surfaces(){return Set.of(ToolSurface.CHAT);}
    public ToolRisk risk(){return ToolRisk.WRITE;}
    public ToolDomain domain(){return ToolDomain.CODING;}
    public boolean agentsMayCall(){return true;}
    public String validate(JsonNode args){
        try { CodingTaskCommand.parse(args); return null; }
        catch (IllegalArgumentException error) { return error.getMessage(); }
    }
    public ObjectNode parameters(ObjectMapper om){
        ObjectNode p=om.createObjectNode().put("type","object"), fields=p.putObject("properties");
        fields.putObject("action").put("type","string").putArray("enum").add("list").add("start").add("read").add("diff").add("operations").add("verify").add("message").add("resume").add("cancel");
        for(String key:Set.of("repositoryId","runId","prompt","input")){fields.putObject(key).put("type","string");}
        fields.withObject("/prompt").put("description","仅 start 的初始目标；message/resume 补充要求使用 input");
        fields.withObject("/input").put("description","message/resume 的实际补充要求；回执可核对实际接收内容");
        fields.putObject("mode").put("type","string").putArray("enum").add("PLAN").add("EDIT").add("EXECUTE");
        p.put("additionalProperties",false);p.putArray("required").add("action");return p;
    }
}
