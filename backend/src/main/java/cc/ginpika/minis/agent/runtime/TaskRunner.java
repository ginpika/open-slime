package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.ai.core.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 执行引擎的契约：把「一次独立上下文的执行」跑完，过程全部留痕。
 *
 * <p>这是运行层留给编排层的口子——调用方只管给一份组装好的 messages 与一个工具分发，
 * 引擎负责无状态地调 LLM、转工具往返、把结论与过程落进 {@code task}（执行就是它那行，见
 * {@link TaskStore#beginExec}）。
 * 现有唯一实现是 {@link AgentRunner}；STEP 1 把它从「唯一引擎」降为「其中一种实现」，
 * 后续 IDE 长任务（后台队列 / 沙箱）可以另起实现而不用动调用方。
 *
 * <p>契约刻意保持最小的三件事：跑一次（登记 + 执行）、预登记拿 id（异步派活要立刻回执）、
 * 跑一次已登记的执行（后台线程续跑）。输入与结果都是值对象，引擎自身不持有状态。
 */
public interface TaskRunner {

    /**
     * 一次执行的输入。
     *
     * @param runId    归属批次（{@code heartbeat_run.id}）
     * @param kind     执行来源：心跳 / 计划 / 子代理（见 {@link ExecStore#KIND_HEARTBEAT} 等），
     *                 落进任务行的 {@code origin} 列——读侧按它分类
     * @param planId   {@code kind=plan} 时的编制 id（{@code heartbeat_plan.id}）；其余为 null
     * @param parentId 派它的那个任务（心跳 → 计划 → 子代理 这条链靠它连起来）；顶层执行传 null
     * @param sessionId 归属会话（子代理：结论要投回的那个会话）；无会话的执行传 null
     * @param agentId  归属 Agent（{@code agent.id}）：这次执行是哪个智能体产生的；可为 null
     * @param payload  重跑所需的原始输入（JSON）；无可重跑输入时为 null
     */
    record Request(String runId, String kind, String planId, String title, String modelId,
                   ArrayNode messages, ArrayNode toolSchemas, Dispatch dispatch, Long parentId,
                   String sessionId, String agentId, String payload) {
    }

    /** 一次执行的结果：execId 可在留痕里回看这次执行的全过程 */
    record Outcome(long execId, String status, String output, String error) {
    }

    /** 工具分发：把模型请求的一次调用落地成一条 tool 结果（不同调用方带不同的工具面） */
    interface Dispatch {

        ToolResult run(JsonNode call, long execId);

        /** 多个调用同时到达时，本工具能否并行执行（默认不能：多数工具有顺序语义） */
        default boolean parallelizable(String name) {
            return false;
        }
    }

    /** 跑一次执行：登记 → 多轮工具往返 → 落结论与过程 */
    Outcome run(Request req);

    /**
     * 预登记一次执行：先把那行任务建出来（带上下文与来源，状态 running）并返回它的 id，
     * 再交给 {@link #run(Request, long)} 去跑。
     *
     * <p>异步派活走这条路——调用方要<b>立刻</b>拿到 id 回一张受理回执，不能等这次执行整个跑完
     * 才拿到；后台跑起来与回执无关，两边只在这一行上相遇。
     */
    long openExec(Request req);

    /** 跑一次已登记的执行（execId 由 {@link #openExec} 给出） */
    Outcome run(Request req, long execId);
}
