package cc.ginpika.minis.coding.integration;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.member.AgentStore;
import cc.ginpika.minis.coding.api.CodingDtos.Agent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Adapter for the default agent while it is working in the programming IDE. */
@Component
@DependsOn({"schemaMigrations", "agentSeeder"})
public class CodingAgent {
    private final AgentStore agents;
    private final AgentPromptService prompts;
    public CodingAgent(AgentStore agents, AgentPromptService prompts) {
        this.agents = agents;
        this.prompts = prompts;
    }
    public Agent profile() {
        var row = agents.peer(BuiltinAgents.CORE)
                .orElseThrow(() -> new IllegalStateException("默认 Agent 不存在"));
        return new Agent(row.id(), row.name(), row.modelId(), row.avatar());
    }
    public String instructions() { return prompts.ideSystem(); }
    public String modelId(String requested) {
        return requested == null || requested.isBlank() ? profile().modelId() : requested;
    }
}
