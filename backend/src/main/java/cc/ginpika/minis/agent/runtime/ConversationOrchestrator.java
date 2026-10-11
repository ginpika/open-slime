package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import java.util.Map;

/**
 * 会话协调器（设计二 §一）：单聊与群聊共用的<b>协调层抽象</b>。
 *
 * <p>设计二的边界主张：单聊与群聊可以共用模型调用、上下文构建、工具执行、记忆、权限等基础能力
 * （{@code AgentRuntime} + {@code ChatTurnRunner} 那一层），但它们的<b>对话生命周期、消息路由、
 * 调度策略、并发控制与中断语义</b>不该被同一个 {@code Turn} 循环绑死。于是两者各有自己的协调器：
 * <ul>
 *   <li>{@code DirectConversationRuntime}（单聊）：请求驱动的简单策略；</li>
 *   <li>{@link cc.ginpika.minis.agent.roundtable.RoundtableDiscussionRuntime}（群聊）：
 *       事件驱动策略——路由消息、唤醒成员、处理沉默、判断阻塞、响应打断、分配执行额度。</li>
 * </ul>
 *
 * <p>共同点只有一个：<b>事件从这里进，下一步由它决定</b>。{@code AgentRuntime} 只负责「执行一次
 * Agent 工作」（构建上下文、调用模型、处理工具、返回结构化结果），它不控制其他 Agent，也不决定
 * 整个讨论何时结束——那是本层的事。
 *
 * <p>接口只保留两件事：收事件（{@link #onEvent}）与看快照（{@link #snapshot}）。
 * 设计二里给出的 {@code scheduleNext()} 等更多方法只是边界示意，不强制照搬。
 */
public interface ConversationOrchestrator {

    /**
     * 事件入口：协调器接收一条事件，决定下一步（继续、重排、收束、取消…）。
     *
     * <p>它的职责边界是「决定谁运行」——Agent 只负责推理与提出行动。
     */
    void onEvent(RuntimeEvent event);

    /**
     * 会话快照（只读）：让外部（页面 / 观测台 / 测试）看到当前编排状态——
     * 正在跑没有、话题到哪了、各成员处在二维状态的哪一格。
     */
    ConversationSnapshot snapshot(String conversationId);

    /**
     * 一次编排的可观测快照。
     *
     * @param conversationId 会话 id
     * @param mode           会话模式（单聊 / 群聊）
     * @param running        此刻是否有一次推进正在跑
     * @param topicId        当前话题（无则 null）
     * @param topicStatus    当前话题状态（无则 null）
     * @param floorPeer      当前持有发言权的成员（无则 null）
     * @param communications 成员名 → 发言状态（{@code SPEAKING / LISTENING / SILENT}）
     * @param executions     成员名 → 执行状态（{@code READY / RUNNING / …}）
     */
    record ConversationSnapshot(String conversationId, String mode, boolean running, String topicId,
                                String topicStatus, String floorPeer,
                                Map<String, String> communications,
                                Map<String, String> executions) {
    }
}
