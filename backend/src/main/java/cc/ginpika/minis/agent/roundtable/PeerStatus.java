package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;

/**
 * 成员的二维状态：{@link CommunicationState}（向不向讨论室发言）× {@link ExecutionState}
 * （工作走到哪）——设计一 §四的落地。
 *
 * <p>原先单一的 {@code ACTIVE / SILENT} 无法回答「Agent 是在生成、等待工具，还是只是暂时不说话」。
 * 拆成两个维度之后，几种此前说不清的场面变得可表达：
 * <ul>
 *   <li>{@code SILENT + RUNNING}：不在讨论室发言，但正在执行分析；</li>
 *   <li>{@code SILENT + WAITING_EVENT}：不发言，正在等工具 / 用户返回；</li>
 *   <li>{@code LISTENING + READY}：正在监听事件，有任务时可以被调度；</li>
 *   <li>{@code SILENT + COMPLETED}：这一步的工作完成了，但不代表它永久退出讨论。</li>
 * </ul>
 *
 * <p>状态折叠的正本仍是 {@code roundtable_topic_event}（见 {@link TopicStateMachine}）；本类只是把
 * 轮次参与状态（{@link PeerState}）<b>投影</b>成这两个维度——不新增任何持久化状态，也就不存在
 * 两处状态打架的可能。
 */
public record PeerStatus(CommunicationState communication, ExecutionState execution) {

    /**
     * 把轮次参与状态投影成二维状态。
     *
     * <p>{@code PROMPTED}（被征询待表态）投影为 {@code BLOCKED}：整条推进链确实在等它表态，
     * 所以这是「被阻塞」而不是「就绪」——这正是二维模型比单枚举更诚实的例子。
     */
    public static PeerStatus of(PeerState state) {
        if (state == null) {
            return new PeerStatus(CommunicationState.SILENT, ExecutionState.READY);
        }
        return switch (state) {
            case SPEAKING -> new PeerStatus(CommunicationState.SPEAKING, ExecutionState.RUNNING);
            case ACTIVE -> new PeerStatus(CommunicationState.LISTENING, ExecutionState.READY);
            case SILENT -> new PeerStatus(CommunicationState.SILENT, ExecutionState.COMPLETED);
            case PROMPTED -> new PeerStatus(CommunicationState.LISTENING, ExecutionState.BLOCKED);
            case AWAIT_USER -> new PeerStatus(CommunicationState.SILENT, ExecutionState.WAITING_EVENT);
            case PROPOSE_END -> new PeerStatus(CommunicationState.SILENT, ExecutionState.COMPLETED);
            case INACTIVE -> new PeerStatus(CommunicationState.SILENT, ExecutionState.COMPLETED);
        };
    }
}
