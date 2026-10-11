package cc.ginpika.minis.agent.roundtable;

/**
 * 执行状态（Execution State）——设计一 §四的第二个维度。
 *
 * <p>它回答的是：这位成员手上的<b>工作</b>走到哪了——在跑、等依赖、被阻塞，还是已经完成 / 取消。
 * 与 {@link CommunicationState} 正交：一位成员可以「不发言但正在执行分析」（SILENT + RUNNING），
 * 也可以「不发言、任务已完成」（SILENT + COMPLETED，不代表永久退出讨论）。
 *
 * <p>终态是 {@link #COMPLETED} / {@link #CANCELLED}（一条执行只能走到其中一个）。
 */
public enum ExecutionState {

    /** 就绪：有任务时可被调度 */
    READY("就绪"),
    /**
     * 待推进：自己手头的活已全部顺利做完、此刻无活可领，正等全桌一起集结
     * （成员级：先做完的先到这里；整桌级：全员都到这里就进入集结讨论，谈交付或新一轮）。
     *
     * <p>它只在「执行态」页（由任务板 + 有没有 worker 在跑推导，见
     * {@code RoundtableWorkflowService}）出现，不由轮次状态机投影而来——所以不是
     * {@link PeerStatus#of} 的产物，也不改任何持久化状态。
     */
    ADVANCED("待推进"),
    /** 执行中：正在生成 / 正在调用工具 */
    RUNNING("执行中"),
    /** 等待事件：等待外部事件（工具返回、用户表态、其他成员的结果） */
    WAITING_EVENT("等待事件"),
    /** 被阻塞：因依赖未满足而推不动（如被征询却还没表态，整条推进链都在等它） */
    BLOCKED("被阻塞"),
    /** 已完成：这一步的工作已产出（不代表结束讨论） */
    COMPLETED("已完成"),
    /** 已取消：这一步的工作被取消（用户停止 / 被新要求取代） */
    CANCELLED("已取消");

    private final String label;

    ExecutionState(String label) {
        this.label = label;
    }

    /** 中文标签（前端与状态块展示用） */
    public String label() {
        return label;
    }
}
