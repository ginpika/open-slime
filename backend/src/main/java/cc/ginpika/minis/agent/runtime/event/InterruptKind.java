package cc.ginpika.minis.agent.runtime.event;

/**
 * 打断分级（设计一 §三）：打断是系统层的行为，不是「给 Agent 再发一条消息」。
 *
 * <p>大多数模型 API 的一次生成并不能像真人聊天那样在任意位置暂停、保存内部思维状态再原地继续，
 * 所以这里实现的是<b>系统层</b>的打断：取消或停止旧生成、标记旧结果过期、按最新状态重新调度。
 * 四类不能混为一谈——一条普通的场外报告并不意味着必须取消所有正在运行的模型。
 *
 * <table>
 *   <tr><td>{@link #INTERRUPT}</td><td>用户补充了新要求，改变当前任务</td>
 *       <td>取消或停止旧生成，更新目标后重新调度</td></tr>
 *   <tr><td>{@link #PREEMPT}</td><td>高优先级事件到达</td>
 *       <td>暂停低优先级任务的调度资格，先处理紧急任务</td></tr>
 *   <tr><td>{@link #NOTIFY}</td><td>场外 Agent 发现新证据</td>
 *       <td>写入事件日志，判断哪些 Agent 需要被唤醒（不取消任何在跑执行）</td></tr>
 *   <tr><td>{@link #CANCEL}</td><td>用户明确要求停止</td>
 *       <td>取消相关生成和可取消的工具任务，清理后续调度</td></tr>
 * </table>
 */
public enum InterruptKind {

    /** 用户补充新要求、改变当前任务：让受影响的工作基于最新状态重新生成（旧结果标过期） */
    INTERRUPT(0),
    /** 高优先级事件到达：暂停低优先级任务的调度资格，先处理紧急任务（M0 只预留，不实现通用抢占算法） */
    PREEMPT(0),
    /** 场外 Agent 报告：只写事件日志 + 唤醒相关成员，不取消任何在跑执行 */
    NOTIFY(0),
    /** 用户明确取消：取消相关生成与可取消的工具任务，清理后续调度 */
    CANCEL(1);

    private final int strength;

    InterruptKind(int strength) {
        this.strength = strength;
    }

    /** 强度：{@link #CANCEL} 高于其余三类；同强度时按事件 priority 裁决 */
    public int strength() {
        return strength;
    }

    /** 是否要求「立即停止当前生成」（只有 CANCEL 与 INTERRUPT 需要；NOTIFY / PREEMPT 不直接停） */
    public boolean stopsGeneration() {
        return this == CANCEL || this == INTERRUPT;
    }
}
