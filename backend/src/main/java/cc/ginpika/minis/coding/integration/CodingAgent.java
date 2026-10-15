package cc.ginpika.minis.coding.integration;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.member.AgentPromptStore;
import cc.ginpika.minis.agent.member.AgentStore;
import cc.ginpika.minis.coding.api.CodingDtos.Agent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** One directory identity and configuration for chat and the specialized coding runtime. */
@Component
@DependsOn({"schemaColumnPatches", "agentSeeder"})
public class CodingAgent {
    private final AgentStore agents;
    private final AgentPromptService prompts;

    public CodingAgent(AgentStore agents, AgentPromptService prompts, AgentPromptStore versions) {
        this.agents = agents;
        this.prompts = prompts;
        if (agents.peer(BuiltinAgents.CODING).isEmpty()) {
            String name = "编码 Agent";
            for (int n = 1; agents.peerByName(name).isPresent(); n++) name = "编码 Agent " + n;
            long now = System.currentTimeMillis();
            agents.insert(new AgentStore.AgentRow(BuiltinAgents.CODING, name, true,
                    "[\"coding_task\"]", null, null, agents.maxSort() + 1,
                    "围绕工作空间项目分析、修改和验证代码；对话与编程工作台共用任务。",
                    null, null, now, now, "编码", "#38bdf8"));
            // Seed only the newly created agent. Never overwrite an existing user's prompt or model.
            if (versions.versions(BuiltinAgents.CODING).isEmpty()) {
                String version = "builtin-coding-identity-v1";
                versions.insertVersion(new AgentPromptStore.VersionRow(version, BuiltinAgents.CODING,
                        "identity", "编码 Agent 默认身份", """
                        你是个人助手中的编码 Agent，负责工作空间项目的分析、代码修改与验证。
                        先通过 coding_task 的 list 获取当前用户项目，确认项目再以 PLAN 或 EDIT 发起任务。
                        编程任务异步运行；保存并返回任务链接，读取真实任务状态，不把受理当作完成。
                        新建或打开项目、授权命令执行和接受变更请引导用户到编程工作台。
                        你与编程工作台是同一个智能体，模型和身份配置共用，任务过程和结果可以互相查看。
                        不得擅自提交、推送、部署或提升权限；环境受阻时如实报告，避免盲目重试。
                        """, now));
                versions.saveBinding(BuiltinAgents.CODING, "identity", version);
            }
        }
    }

    public Agent profile() {
        var row = agents.peer(BuiltinAgents.CODING)
                .orElseThrow(() -> new IllegalStateException("内置编码 Agent 不存在"));
        return new Agent(row.id(), row.name(), row.modelId());
    }

    public String instructions() { return prompts.system(BuiltinAgents.CODING); }
    public String modelId(String requested) {
        return requested == null || requested.isBlank() ? profile().modelId() : requested;
    }
}
