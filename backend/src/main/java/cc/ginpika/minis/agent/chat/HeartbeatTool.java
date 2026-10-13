package cc.ginpika.minis.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 任务计划工具的宿主：会话侧只认识这个口子，不认识潜意识模块本身。
 *
 * <p>对话工具面（CHAT）里挂了几个心跳工具——
 * 派职责（delegate_heartbeat）与立/改/撤编制（create_heartbeat / update_heartbeat / delete_heartbeat，另有 list_heartbeats），
 * 执行要落到「潜意识」模块——由 {@link ChatToolDispatcher} 在工具分发时转交过来。
 * 抽成接口是为了让依赖方向单向：潜意识模块依赖 chat 包（会话编排与工具执行口），
 * 而 chat 包不反过来依赖潜意识模块。
 *
 * <p>子代理那套（{@code delegate_subagent} / {@code read_subagent_run}）走 {@link SubagentTool}：
 * 一个是常驻编制，一个是匿名临时 worker，语义不同、宿主也分开。
 */
public interface HeartbeatTool {

    /**
     * 执行一次计划 / 职责工具调用，返回给模型的 tool 消息内容；非计划工具返回「未知工具」。
     *
     * @param agentId 归属 Agent（{@code agent.id}）：这次调用是替哪个 Agent 管它的职责名册
     *                （首页对话 = 内置 Agent；圆桌 = 发言成员）
     */
    String runTool(String agentId, JsonNode call);
}
