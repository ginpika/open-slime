package cc.ginpika.minis.ai.core;

/**
 * 一次记忆读写的执行作用域（memory-v5 §3.5，memory-v6 §4.1 扩展）。它回答「现在是谁、在哪个项目 /
 * 会话 / 任务、什么面、什么身份」，是读侧 {@code visible} 谓词与写侧归属判定的输入。
 *
 * <p><b>放在 {@code ai.core}</b>：它是跨层的运行时上下文（{@code ai.memory} 与 {@code agent} 都要读），
 * 与 {@link AgentScope} 同一理由——底层的 {@code ai.*} 不得依赖编排层 {@code agent.*}。
 *
 * <p><b>v6 变化</b>（memory-v6 §4.1）：
 * <ul>
 *   <li>{@code surface} 由裸字符串改 {@link MemorySurface} 枚举——面是编译期契约；</li>
 *   <li>新增 {@code turnId}：本轮唯一 id，是「快照回放」与写入审计（memory_revision.turn_id）的回放键；</li>
 *   <li>新增 {@link MemoryIdentity}：role-play 身份显式化；原 {@code narrative} 布尔不再单独存，
 *       由 {@link #narrative()} 从身份派生，避免两处真相。</li>
 * </ul>
 *
 * <p>多数场景由编排层显式传参（如 {@code ContextRequest}）；深层工具无法一路透传时，用
 * {@link MemoryScopeCtx} 在执行入口圈定，深处 {@link MemoryScopeCtx#get()} 读取（同 AgentScope 手法）。
 *
 * @param userId    当前用户（多租户预留；单用户阶段可空）
 * @param agentId   当前 Agent（agent.id）；空 → 规范化 {@link #BUILTIN}
 * @param projectId 当前项目（project.id）；可空
 * @param sessionId 当前会话（hub_session.id）；可空
 * @param taskId    当前任务（task.id）；可空
 * @param surface   执行面：CHAT / SUBAGENT / HEARTBEAT / ROUNDTABLE / PLAN / SYSTEM
 * @param human     是否人工操作（管理页 / 显式用户动作）→ 读侧 R6 放行全量
 * @param turnId    本轮唯一 id（快照 / 审计回放键）；无轮次概念的场合可空
 * @param identity  身份上下文（REAL / FICTION / PROJECT_PREF / INFERRED）→ 写侧 role-play 隔离
 */
public record MemoryExec(String userId, String agentId, String projectId, String sessionId,
                         Long taskId, MemorySurface surface, boolean human, String turnId,
                         MemoryIdentity identity) {

    /** 内置 Agent 的规范化 id：memory-v5 起 {@code agent_id=NULL} 不再表示全局画像，一律规范化到它 */
    public static final String BUILTIN = "builtin";

    /** 规范化归属 Agent：空 / 'builtin' → {@link #BUILTIN} */
    public String agentOrBuiltin() {
        return (agentId == null || agentId.isBlank()) ? BUILTIN : agentId;
    }

    /** 身份是否为空（老调用点未给身份时视为推断，保守不入画像） */
    public MemoryIdentity identityOrInferred() {
        return identity == null ? MemoryIdentity.INFERRED : identity;
    }

    /** 创作/虚构上下文：由身份派生（v6 起不再单独存布尔，避免与 identity 两处真相） */
    public boolean narrative() {
        return identityOrInferred() == MemoryIdentity.FICTION;
    }

    /** 后台 / 系统作用域（无会话、无项目）：@param agentId 可为空 */
    public static MemoryExec system(String agentId) {
        return new MemoryExec(null, agentId, null, null, null, MemorySurface.SYSTEM, true, null,
                MemoryIdentity.INFERRED);
    }

    /**
     * 无作用域（读侧保守退化）：只读得到正式画像，**绝不**退化为读全量（memory-v6 §4.2）。
     * 与 {@link #system(String)} 的区别：那个是「人工全量」作用域（human=true）。
     */
    public static MemoryExec none() {
        return new MemoryExec(null, "__no_memory_scope__", null, null, null, MemorySurface.SYSTEM, false, null,
                MemoryIdentity.INFERRED);
    }

    /** 对话作用域：前台会话一条最简单的构造（身份由项目叙事性派生） */
    public static MemoryExec chat(String agentId, String projectId, String sessionId, String turnId,
                                  boolean narrative) {
        return new MemoryExec(null, agentId, projectId, sessionId, null, MemorySurface.CHAT, false,
                turnId, MemoryIdentity.ofNarrative(narrative));
    }

    /** 对话作用域（带任务与显式身份）：ChatTurnRunner 等编排层使用 */
    public static MemoryExec chat(String agentId, String projectId, String sessionId, Long taskId,
                                 String turnId, MemoryIdentity identity) {
        return new MemoryExec(null, agentId, projectId, sessionId, taskId, MemorySurface.CHAT, false,
                turnId, identity == null ? MemoryIdentity.INFERRED : identity);
    }

    /** 后台面（子代理 / 心跳 / 计划）：无 human、无会话交互 */
    public static MemoryExec background(MemorySurface surface, String agentId, String projectId,
                                        String sessionId, Long taskId, String turnId,
                                        MemoryIdentity identity) {
        return new MemoryExec(null, agentId, projectId, sessionId, taskId, surface, false, turnId,
                identity == null ? MemoryIdentity.INFERRED : identity);
    }
}
