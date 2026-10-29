package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.chat.RequestUserInputTool;
import cc.ginpika.minis.ai.core.tool.memory.ManageProjectTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DelegatePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DeletePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.UpdatePlanTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 单聊工具路由：先校验参数，再交给相应能力；授权仍由能力域负责。 */
@Service
public class ChatToolDispatcher {
    private final ObjectMapper om;
    private final ToolValidator validator;
    private final TodoService todo;
    private final HeartbeatTool heartbeat;
    private final SubagentTool subagent;
    private final TerminalService terminal;
    private final KnowledgeToolbox toolbox;
    private final ProjectWorkflowService projectWorkflows;
    private cc.ginpika.minis.coding.CodingFacade coding;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCoding(@org.springframework.context.annotation.Lazy cc.ginpika.minis.coding.CodingFacade coding) { this.coding=coding; }
    private static final Set<String> PLAN_TOOLS = Set.of(
            DelegatePlanTool.NAME, CreatePlanTool.NAME, UpdatePlanTool.NAME, DeletePlanTool.NAME);

    /** 由子代理模块执行的工具：派一个匿名子代理 + 按 id 翻任意一次执行的过程 */
    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            DelegateSubagentTool.NAME, ReadSubagentRunTool.NAME);

    public ChatToolDispatcher(ObjectMapper om,
            ToolValidator validator,
            TodoService todo,
            HeartbeatTool heartbeat,
            SubagentTool subagent,
            TerminalService terminal,
            KnowledgeToolbox toolbox,
            ProjectWorkflowService projectWorkflows) {
        this.om = om;
        this.validator = validator;
        this.todo = todo;
        this.heartbeat = heartbeat;
        this.subagent = subagent;
        this.terminal = terminal;
        this.toolbox = toolbox;
        this.projectWorkflows = projectWorkflows;
    }

    public String runTool(JsonNode call, String batchRunId, String hubId) {
        String name = call.path("function").path("name").asText("");
        /* 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（只读放行），拒了不落执行 */
        String invalid = validator.validate(name,
                call.path("function").path("arguments").asText(""));
        if (invalid != null) {
            return invalid;
        }
        if (RequestUserInputTool.NAME.equals(name)) {
            try {
                var question = RequestUserInputTool.read(om,
                        call.path("function").path("arguments").asText("{}"));
                question.put("status", "waiting_for_user");
                return question.toString();
            } catch (IllegalArgumentException error) {
                return "询问参数无效：" + error.getMessage();
            }
        }
        if (cc.ginpika.minis.coding.integration.CodingTaskTool.NAME.equals(name)) {
            String owner=cc.ginpika.minis.ai.core.UserScope.get();
            if(owner==null || owner.isBlank() || coding==null){return "当前执行缺少已认证用户身份，请从编程工作台发起任务。";}
            try {
                JsonNode args=om.readTree(call.path("function").path("arguments").asText("{}"));
                return coding.assistantTool(owner,args,"chat:"+hubId+":"+call.path("id").asText(),hubId);
            } catch(Exception error){return "编程任务操作失败："+error.getMessage();}
        }
        if (ManageProjectTool.NAME.equals(name)) {
            try {
                JsonNode args = om.readTree(call.path("function").path("arguments").asText("{}"));
                return projectWorkflows.runTool(hubId, args);
            } catch (Exception e) {
                return "项目工具参数无效：" + e.getMessage();
            }
        }
        if (TodoService.TOOLS.contains(name)) {
            /* 个人清单归属当前 Agent：首页 = 内置 Agent，圆桌执行态 = 当前发言成员（见 AgentScope）。
               刻意与下方 agentId（含内置兜底）分开——TodoProvider 回灌时用同一口径 AgentScope#get()，
               两边必须逐字一致，否则写进去的清单读不回来 */
            return todo.runTool(call, hubId, AgentScope.get());
        }
        /* 归属 Agent：首页对话 = 内置 Agent；圆桌那条流由 RoundtableChatService 在 AgentScope 里设好成员身份 */
        String agentId = AgentScope.get() == null ? AgentDirectory.BUILTIN_ID : AgentScope.get();
        if (PLAN_TOOLS.contains(name)) {
            return heartbeat.runTool(agentId, call);
        }
        if (SUBAGENT_TOOLS.contains(name)) {
            return subagent.runChatTool(agentId, call, batchRunId, hubId);
        }
        if (TerminalService.handles(name)) {
            return terminal.run(call, TerminalService.SOURCE_CHAT).content();
        }
        return toolbox.run(call, "chat").content();
    }
}
