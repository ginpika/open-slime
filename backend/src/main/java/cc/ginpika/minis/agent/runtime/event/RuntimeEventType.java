package cc.ginpika.minis.agent.runtime.event;

/**
 * 运行时事件类型（设计一 §六的事件协议）。
 *
 * <p>系统推进的<b>基本单位是事件</b>，不是 Turn：用户输入、成员发言、工具结果、任务状态变化，
 * 都先成为一条事件，而不是直接塞进某个正在运行的模型上下文。事件的默认优先级只用于排序；
 * 真正要不要抢占，取决于明确策略（见 {@link InterruptKind} 与 {@code RuntimeEventBus} 的处理）。
 *
 * <p>注意：事件驱动<b>不等于</b>每来一条事件就调一次模型。大部分事件由确定性代码合并、过滤或
 * 直接改状态，只有确实需要智能判断时才触发一次推理。
 */
public enum RuntimeEventType {

    /** 用户往会话里说了一句话（必须被接收与持久化；不一定自动取消所有执行） */
    USER_MESSAGE(50),
    /** 某个成员说了一句话（一轮发言的产物，发布后进入讨论事实） */
    AGENT_MESSAGE(30),
    /** 场外报告：另一个任务里的成员/子代理发现了值得讨论的事（如安全发现） */
    AGENT_REPORT(80),
    /** 讨论定案、该开工了：各成员的核心线程据此开始消费自己的 TODO（讨论态 → 执行态） */
    WORK_STARTED(85),
    /** 执行遇阻、请再次激活讨论（执行态 → 讨论态） */
    DISCUSSION_REQUESTED(80),
    /**
     * 全员顺利完成：这张桌子上「手头的活都做完了、且无人受阻」（执行态 → 集结讨论）。
     *
     * <p>它是「顺利推进」这条回边的信号——遇阻走 {@link #DISCUSSION_REQUESTED} / {@link #TASK_BLOCKED}，
     * 顺利做完走这里。没有它，最后一条做完就再没人被叫起来，交付断在那里（此前正是如此）。
     * 讨论运行时接住它，把全桌重新叫到一起：要么开新一轮工作，要么按共识收场（交付）。
     */
    ALL_WORK_DONE(85),
    /** 一项工作被创建（需要推进的任务） */
    TASK_CREATED(20),
    /** 一项工作被阻塞（等待依赖、等待用户、等待工具） */
    TASK_BLOCKED(20),
    /** 一项工作完成 */
    TASK_COMPLETED(20),
    TASK_SUBMITTED(85),
    TASK_REVISED(85),
    ISSUE_RESOLVED(85),
    /** 一次工具调用开始（副作用生命周期的起点） */
    TOOL_STARTED(40),
    /** 一次工具调用正常结束 */
    TOOL_COMPLETED(40),
    /** 一次工具调用失败 */
    TOOL_FAILED(60),
    /** 到了某个期限（超时 / 预算耗尽 / 截止时间） */
    DEADLINE_REACHED(70),
    /** 有人请求打断当前执行（用户插话改变任务约束，或高优先级事件到达） */
    INTERRUPT_REQUESTED(90),
    /** 有人请求取消当前执行（用户明确要求停止） */
    CANCEL_REQUESTED(100);

    private final int priority;

    RuntimeEventType(int priority) {
        this.priority = priority;
    }

    /** 默认优先级（数值越大越优先）；事件自身可以覆写它 */
    public int priority() {
        return priority;
    }

    /**
     * 这类事件是否属于「普通通知」——同一任务短时间内多条可以合并处理。
     *
     * <p>用户新指令、明确取消、严重安全事件（{@link #AGENT_REPORT} / {@link #INTERRUPT_REQUESTED} /
     * {@link #CANCEL_REQUESTED}）不属于普通通知，绝不能被合并掉（设计一 §五-3 的防事件风暴约定）。
     */
    public boolean coalescible() {
        return this == AGENT_MESSAGE || this == TOOL_STARTED || this == TOOL_COMPLETED
                || this == TASK_CREATED;
    }
}
