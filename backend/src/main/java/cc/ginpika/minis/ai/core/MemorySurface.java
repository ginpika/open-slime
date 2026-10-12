package cc.ginpika.minis.ai.core;

/**
 * 一次记忆读写的**执行面**（memory-v6 §4.1）：这次读写发生在哪条流上。
 *
 * <p>为什么枚举化：v5 用的是裸字符串（{@code "CHAT"} / {@code "SYSTEM"}），写错不报错、读侧只能靠约定。
 * v6 把「面」变成编译期契约——各面召回链路（memory-v6 §8.5）据此决定读什么范围：
 * CHAT 读画像+项目+本 Agent+本会话+共享；SUBAGENT 读派发方作用域+黑板；
 * HEARTBEAT 读该 Agent 自己的私有记忆+绑定项目；ROUNDTABLE 读已发布面+本会话纪要。
 */
public enum MemorySurface {
    /** 前台会话（首页对话 / 圆桌的会话式复写） */
    CHAT,
    /** 子代理：派它的 Agent / 项目作用域 + 本会话黑板 */
    SUBAGENT,
    /** 心跳后台：某 Agent 的私有记忆 + 绑定项目 */
    HEARTBEAT,
    /** 圆桌讨论：成员各自可读的公开面 + 本会话纪要 */
    ROUNDTABLE,
    /** 职责 / 计划执行（AgentRunner） */
    PLAN,
    /** 系统 / 管理页 / 后台维护：人工作用域（human=true 时读全量） */
    SYSTEM
}
