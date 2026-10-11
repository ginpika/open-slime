package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆写入的门面：agent 侧「说过/做过」在发生点顺手 push 进事件流。
 *
 * <p>方向约束：memory 属 ai 一侧，不回头去读 agent 的库——所以是 push 不是 pull
 *（ChatService 落 chat_message 时、AgentRunner 落执行结论时各推一条），
 * agent → ai 的依赖方向与潜意识模块一致。
 *
 * <p>两条硬规则：
 * <ul>
 *   <li><b>失败隔离</b>——记忆是旁路，写不进绝不能拖垮聊天/执行主流程，异常吞掉只留日志；</li>
 *   <li><b>幂等</b>——event_key 用正本行 id（chat_message:{id} / task:{id}），
 *       重复 push 由 UNIQUE 约束静默去重。</li>
 * </ul>
 *
 * <p>M0 只收两种事件：对话（user 提问 / assistant 回答）与执行结论（done）；
 * 失败的执行、子代理气泡等先不进流——等有真实事件量再定取舍。
 *
 * <p>M1 起 push 成功后顺手触发向量轴补齐（{@link MemoryEmbeddingWriter#complementAsync}，
 * 异步且同样失败隔离）：事件落库与向量落库解耦，embedding 抖动不影响记忆流本身。
 */
@Component
public class MemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriter.class);

    /** 事件种类：对话 */
    public static final String KIND_CHAT = "chat";
    /** 事件种类：一次执行出了结论 */
    public static final String KIND_EXEC = "exec";

    /** 正本表名（ref_type 取值）：对话消息 */
    public static final String REF_CHAT_MESSAGE = "chat_message";
    /** 正本表名（ref_type 取值）：一次执行 */
    public static final String REF_TASK = "task";

    /** 正文投影的截断长度：记忆事件是索引不是正本，长了挤占注入预算 */
    private static final int PAYLOAD_LIMIT = 500;

    private final MemoryEventStore events;
    private final MemoryEmbeddingWriter embeddings;

    public MemoryWriter(MemoryEventStore events, MemoryEmbeddingWriter embeddings) {
        this.events = events;
        this.embeddings = embeddings;
    }

    /** 落 chat_message 时推：role 为 user/assistant，sessionTitle 供注入侧认出发生在哪 */
    public void onChatMessage(long messageId, String role, String sessionTitle, String content) {
        push(REF_CHAT_MESSAGE + ":" + messageId, KIND_CHAT, role, sessionTitle, content,
                REF_CHAT_MESSAGE, messageId);
    }

    /** 执行出结论时推（AgentRunner 的 done 分支）：kind 为 heartbeat/plan/agent，output 为结论 */
    public void onExecDone(long execId, String kind, String title, String output) {
        push(REF_TASK + ":" + execId, KIND_EXEC, kind, title, output, REF_TASK, execId);
    }

    private void push(String eventKey, String kind, String actor, String title, String payload,
                      String refType, long refId) {
        try {
            if (events.append(eventKey, kind, actor, trim(title), truncate(payload), refType, refId)) {
                /* 只有真的写入（非幂等重放）才值得花一次 embedding 调用 */
                embeddings.complementAsync(eventKey);
            }
        } catch (RuntimeException e) {
            log.warn("记忆事件写入失败（不影响主流程）：{} {}", eventKey, e.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank() || s.length() <= PAYLOAD_LIMIT) {
            return s;
        }
        return s.substring(0, PAYLOAD_LIMIT) + "…";
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
