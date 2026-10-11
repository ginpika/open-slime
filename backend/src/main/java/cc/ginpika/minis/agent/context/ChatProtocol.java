package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对话补全消息的协议与解码约定：出站消息该怎么拼、历史留痕该怎么读回来。
 *
 * <p>这些约定被多条流共用——对话循环（{@code ChatService#runChat}）、执行引擎（{@code AgentRunner}）、
 * 历史回放（{@link cc.ginpika.minis.agent.chat.turn.History}）——与「谁在组装」无关，故独立于此，不挂在任何业务类上：
 * 组装搬进 {@link ContextBuilder} 之后，若这些助手还留在 {@code ChatService}，
 * 上下文子系统再想用它们就成了反向依赖。
 */
public final class ChatProtocol {

    private static final Logger log = LoggerFactory.getLogger(ChatProtocol.class);

    private ChatProtocol() {
    }

    /**
     * 给「带工具调用的 assistant 轮次」补齐 reasoning_content 字段。
     *
     * <p>思考模式（DeepSeek V4 / deepseek-flash 等）有一条硬性协议：请求里只要带 {@code tools}，
     * 凡带 {@code tool_calls} 的 assistant 消息就必须把 {@code reasoning_content} 一并回传；
     * 缺这个字段，续轮直接 400（{@code The reasoning_content in the thinking mode must be passed
     * back to the API.}）——实测对照：缺字段 400，带空串 200。
     *
     * <p>而模型并不保证每个工具轮次都产出思考（deepseek-flash 常常直接给 tool_calls），
     * 所以这里缺了就补空串。补的是同一个对象，落库的 tool_rounds 与回放因此保持一致；
     * 其它供应商收到这个陌生字段会忽略，不受影响。
     */
    public static JsonNode withReasoningForToolCalls(JsonNode round) {
        if (round instanceof ObjectNode obj) {
            JsonNode calls = obj.path("tool_calls");
            JsonNode reasoning = obj.get("reasoning_content");
            if (calls.isArray() && !calls.isEmpty()
                    && (reasoning == null || reasoning.isNull())) {
                obj.put("reasoning_content", "");
            }
        }
        return round;
    }

    /**
     * 反序列化子代理气泡的元信息（{@code chat_message.meta} 里的 {@link ChatDtos.SubagentBrief}）。
     *
     * <p>仅 {@code kind=subagent} 时有；普通对话或解析失败返回 null，由调用方退化为「只显示标题」。
     */
    public static ChatDtos.SubagentBrief readSubagentBrief(ObjectMapper om, String kind, String meta) {
        if (!ChatMessageStore.KIND_SUBAGENT.equals(kind) || meta == null || meta.isBlank()) {
            return null;
        }
        try {
            return om.readValue(meta, ChatDtos.SubagentBrief.class);
        } catch (Exception e) {
            log.warn("子代理气泡元信息反序列化失败（该气泡只显示标题）: {}", e.getMessage());
            return null;
        }
    }
}
