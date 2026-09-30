package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * 思考模式续轮的协议约定回归。
 *
 * <p>DeepSeek V4 / deepseek-flash 等思考模型有一条硬要求：请求里带 {@code tools} 时，
 * 凡带 {@code tool_calls} 的 assistant 消息必须把 {@code reasoning_content} 一并回传，
 * 缺字段续轮直接 400（实测对照：缺字段 400、带空串 200）。而模型并不保证每个工具轮次
 * 都产出思考，所以缺了就补空串——这里把三种形态都钉住，免得日后有人「顺手清掉空字段」。
 */
class ChatServiceReasoningTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 带 tool_calls 但模型没产出思考：补空串（正是当初 400 的那种轮次） */
    @Test
    void toolCallRoundWithoutReasoningGetsEmptyString() {
        ObjectNode round = toolCallRound();

        JsonNode out = ChatService.withReasoningForToolCalls(round);

        assertTrue(out.has("reasoning_content"), "缺该字段会被供应商拒绝");
        assertEquals("", out.path("reasoning_content").asText());
    }

    /** 模型产出了思考：原样保留，不能被空串覆盖 */
    @Test
    void existingReasoningIsKept() {
        ObjectNode round = toolCallRound();
        round.put("reasoning_content", "先查一下再答。");

        JsonNode out = ChatService.withReasoningForToolCalls(round);

        assertEquals("先查一下再答。", out.path("reasoning_content").asText());
    }

    /** 普通回答（无 tool_calls）：不添字段——只有工具轮次才受那条协议约束 */
    @Test
    void plainAssistantRoundIsUntouched() {
        ObjectNode round = OM.createObjectNode();
        round.put("role", "assistant");
        round.put("content", "直接回答");

        JsonNode out = ChatService.withReasoningForToolCalls(round);

        assertFalse(out.has("reasoning_content"));
    }

    private static ObjectNode toolCallRound() {
        ObjectNode round = OM.createObjectNode();
        round.put("role", "assistant");
        round.putNull("content");
        round.putArray("tool_calls").addObject()
                .put("id", "call_1").put("type", "function")
                .putObject("function").put("name", "propose_end").put("arguments", "{}");
        return round;
    }
}
