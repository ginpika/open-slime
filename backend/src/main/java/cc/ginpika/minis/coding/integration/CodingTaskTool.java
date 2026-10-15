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
    public String description(){return "内置编码 Agent 的统一任务入口。list 列出当前用户工作空间项目和仓库；start 按项目 id（repositoryId）发起 PLAN 分析或 EDIT 修改，返回同一任务的 runId 和编程工作台链接；read 读取状态/回答/验证；message 为运行中任务补充 input；resume 继续已暂停的 PLAN/EDIT 任务；cancel 停止任务。任务异步运行，受理不代表完成。只能访问已认证用户自己的项目和任务；EXECUTE 授权、结果未知确认和应用变更由用户在工作台操作，不能提交/推送/部署。";}
    public Set<ToolSurface> surfaces(){return Set.of(ToolSurface.CHAT);}
    public ToolRisk risk(){return ToolRisk.WRITE;}
    public ToolDomain domain(){return ToolDomain.CODING;}
    public boolean agentsMayCall(){return true;}
    public String validate(JsonNode args){
        String action=args.path("action").asText();
        if(!Set.of("list","start","read","message","resume","cancel").contains(action)){return "action 必须是 list/start/read/message/resume/cancel";}
        if("start".equals(action)){
            if(args.path("repositoryId").asText().isBlank() || args.path("prompt").asText().isBlank()){return "开始编程任务需要 repositoryId 和 prompt";}
            if(!Set.of("PLAN","EDIT").contains(args.path("mode").asText("PLAN"))){return "此入口仅支持 PLAN/EDIT";}
        }else if(!"list".equals(action) && args.path("runId").asText().isBlank()){return "需要 runId";}
        if("message".equals(action) && args.path("input").asText().isBlank()){return "补充指令 input 不能为空";}
        return null;
    }
    public ObjectNode parameters(ObjectMapper om){
        ObjectNode p=om.createObjectNode().put("type","object"), fields=p.putObject("properties");
        fields.putObject("action").put("type","string").putArray("enum").add("list").add("start").add("read").add("message").add("resume").add("cancel");
        for(String key:Set.of("repositoryId","runId","prompt","input")){fields.putObject(key).put("type","string");}
        fields.putObject("mode").put("type","string").putArray("enum").add("PLAN").add("EDIT");
        p.putArray("required").add("action");return p;
    }
}
