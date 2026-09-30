package cc.ginpika.minis.ai.core.tool;

/**
 * 工具面：一次 LLM 调用里「模型能看见哪些工具」的契约。
 *
 * <p>同一个工具可属于多个工具面（如 upsert_node 既在对话里可写、也在文档分析的入库流程里可用），
 * 由 {@link ToolManager} 按面聚合出该次调用要挂的 tools 数组。
 */
public enum ToolSurface {

    /** 对话主工具面：全部知识库工具（读 + 写）+ 潜意识（派活 / 翻过程） */
    CHAT,

    /** 心跳主会话工具面：只读检索 + 潜意识；刻意不含写库——主会话是调度者，自己不下场改记忆 */
    HEARTBEAT,

    /** 子代理会话工具面：open_thread / send_message，子代理「说给人听」的通道 */
    THREAD,

    /**
     * 多智能体成员工具面：圆桌的状态机工具（提议结束 / 转入沉默 / 抢答 / 请用户表态）。
     *
     * <p>刻意不并进 {@link #CHAT}：它们只对「圆桌话题」有意义——没有一个话题在手时，
     * 「提议结束」无从谈起。混进对话面会让主对话也拿到它们，凭空多出无法兑现的动作。
     */
    PEER,

    /** 文档分析：三元组抽取（submit_triples） */
    EXTRACT,

    /** 文档分析：确认入库（upsert_node / upsert_edge） */
    COMMIT
}
