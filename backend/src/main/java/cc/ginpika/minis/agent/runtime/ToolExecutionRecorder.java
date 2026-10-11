package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 工具执行的记录器：把每一次工具调用包成一个<b>有生命周期的执行</b>（设计一 §五-2 / §六）。
 *
 * <p>包在 {@code AgentRuntime.ToolDispatch} 上——那是所有流（首页对话、圆桌成员发言）唯一的
 * 工具分发入口，于是「每次调用都有记录、都发事件」只需在一处保证。
 *
 * <p>它对结果<b>不做任何改写</b>：成功原样返回、异常原样抛出。它只负责：
 * <ul>
 *   <li>落一条 {@code running} → 终态的记录（{@link ToolExecutionStore}）；</li>
 *   <li>发 {@code TOOL_STARTED} / {@code TOOL_COMPLETED} / {@code TOOL_FAILED} 事件——事件是
 *       系统推进的基本单位，工具结果因此也进事件正本，而不是只塞回模型上下文。</li>
 * </ul>
 */
@Component
public class ToolExecutionRecorder {

    /** 结果摘要的字符上限（只留可读的一小段，全文不落库） */
    private static final int RESULT_MAX = 500;

    private final ToolExecutionStore store;
    private final RuntimeEventBus events;

    public ToolExecutionRecorder(ToolExecutionStore store, RuntimeEventBus events) {
        this.store = store;
        this.events = events;
    }

    /**
     * 执行一次工具调用，并记录其生命周期。
     *
     * @param sessionId  归属会话
     * @param taskId     归属任务（可空）
     * @param batchRunId 本次派活的批次（可空；与调用一起构成幂等键）
     * @param call       工具调用（含 name 与 arguments）
     * @param action     真正的执行动作
     */
    public String record(String sessionId, String taskId, String batchRunId, JsonNode call,
                         Supplier<String> action) {
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("");
        String idemKey = idemKey(sessionId, batchRunId, name, args);
        /* cancellable 保守记 false：是否支持安全取消由工具语义决定，M0 不逐工具声明 */
        long id = store.start(sessionId, taskId, name, args, idemKey, false);
        publish(sessionId, RuntimeEventType.TOOL_STARTED, name, idemKey, null);
        try {
            String result = action.get();
            store.finish(id, ToolExecutionStore.STATUS_DONE, summarize(result), null);
            publish(sessionId, RuntimeEventType.TOOL_COMPLETED, name, idemKey, null);
            return result;
        } catch (RuntimeException e) {
            store.finish(id, ToolExecutionStore.STATUS_FAILED, null, e.getMessage());
            publish(sessionId, RuntimeEventType.TOOL_FAILED, name, idemKey, e.getMessage());
            throw e;
        }
    }

    /**
     * 幂等键：同一会话、同一批次、同一工具、同一参数视为「同一次调用」。
     *
     * <p>它不在这里阻断重复（是否判重是业务决定），只是把「同一次调用的重复投递」标出来，
     * 供「结果不确定时先查状态、再决定要不要重试」使用（见 {@link ToolExecutionStore#byIdemKey}）。
     */
    private static String idemKey(String sessionId, String batchRunId, String name, String args) {
        return (sessionId == null ? "" : sessionId) + "|" + (batchRunId == null ? "" : batchRunId)
                + "|" + name + "|" + Integer.toHexString((args == null ? "" : args).hashCode());
    }

    /** 结果摘要：压平空白并截断，避免把大段产物塞进事件正本 */
    private static String summarize(String result) {
        if (result == null) {
            return null;
        }
        String flat = result.replaceAll("\\s+", " ").strip();
        return flat.length() <= RESULT_MAX ? flat : flat.substring(0, RESULT_MAX) + "…";
    }

    private void publish(String sessionId, RuntimeEventType type, String tool, String idemKey,
                         String error) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tool", tool);
        payload.put("idemKey", idemKey);
        if (error != null) {
            payload.put("error", error);
        }
        events.publish(RuntimeEvent.of(type, sessionId, "tool", null, null, null, payload));
    }
}
