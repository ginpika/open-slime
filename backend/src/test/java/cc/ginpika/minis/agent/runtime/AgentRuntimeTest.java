package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.agent.chat.ChatTrace;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Agent 内核（唯一那条循环）的回归：末轮撤工具的约定、工具往返的协议闭合、以及三种收尾。
 *
 * <p>内核是最要紧的一块——会话、子代理、心跳、计划全跑在它上面，任何一处改动都得先在这里钉住。
 */
class AgentRuntimeTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 依次回放给定的 message；同时记下每次请求体，供断言「最后一轮撤掉 tools」 */
    private static final class Stub {
        final Deque<JsonNode> pending = new ArrayDeque<>();
        final List<ObjectNode> bodies = new ArrayList<>();
        final List<String> dispatched = new ArrayList<>();

        OpenAiChatClient client() {
            OpenAiChatClient client = mock(OpenAiChatClient.class);
            when(client.chatRaw(any(), any(), any(), any())).thenAnswer(inv -> {
                ObjectNode body = inv.getArgument(1);
                bodies.add(body.deepCopy());
                return new OpenAiChatClient.RawResult(pending.poll(), "raw-" + bodies.size());
            });
            return client;
        }
    }

    private static Stub stub(ObjectNode... messages) {
        Stub stub = new Stub();
        for (ObjectNode m : messages) {
            stub.pending.add(m);
        }
        return stub;
    }

    private static ObjectNode bodyWithTools() {
        ObjectNode body = OM.createObjectNode();
        body.put("model", "m");
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "user").put("content", "问");
        body.putArray("tools").addObject().put("type", "function").putObject("function")
                .put("name", "t");
        return body;
    }

    private static ObjectNode answer(String content) {
        ObjectNode m = OM.createObjectNode();
        m.put("role", "assistant");
        m.put("content", content);
        return m;
    }

    /** 一次工具调用（id=c1，工具名 t）；reasoning 非空时一并带上 */
    private static ObjectNode toolCall(String reasoning) {
        ObjectNode m = OM.createObjectNode();
        m.put("role", "assistant");
        m.putNull("content");
        if (reasoning != null) {
            m.put("reasoning_content", reasoning);
        }
        m.putArray("tool_calls").addObject()
                .put("id", "c1").put("type", "function")
                .putObject("function").put("name", "t").put("arguments", "{}");
        return m;
    }

    private static ModelTarget target() {
        ModelTarget target = mock(ModelTarget.class);
        when(target.model()).thenReturn("m");
        return target;
    }

    private static AgentRuntime.Spec spec(Stub stub, int maxRounds, ObjectNode body,
                                          AgentRuntime.Listener listener,
                                          AgentRuntime.RunState state) {
        return new AgentRuntime.Spec(target(), "test", body,
                new AgentRuntime.ToolDispatch() {
                    @Override
                    public String run(JsonNode call) {
                        stub.dispatched.add(call.path("function").path("name").asText());
                        return "工具结果";
                    }
                },
                maxRounds, listener, state);
    }

    @Test
    void dropsToolsWhenOnlyOneRoundIsAllowed() {
        Stub stub = stub(answer("答"));
        AgentRuntime.RunState state = new AgentRuntime.RunState();

        AgentRuntime.Result result = new AgentRuntime(stub.client())
                .run(spec(stub, 0, bodyWithTools(), null, state));

        assertEquals(AgentRuntime.End.ANSWERED, result.end());
        assertEquals("答", result.content());
        /* 末轮（此处是唯一一轮）不挂 tools */
        assertFalse(stub.bodies.get(0).has("tools"));
    }

    @Test
    void keepsToolsUntilLastRoundAndPairsToolRounds() {
        Stub stub = stub(toolCall(null), answer("终"));
        AgentRuntime.RunState state = new AgentRuntime.RunState();

        AgentRuntime.Result result = new AgentRuntime(stub.client())
                .run(spec(stub, 1, bodyWithTools(), null, state));

        assertEquals(AgentRuntime.End.ANSWERED, result.end());
        assertEquals("终", result.content());
        assertTrue(stub.bodies.get(0).has("tools"), "首轮要带工具");
        assertFalse(stub.bodies.get(1).has("tools"), "末轮撤掉工具逼结论");
        assertEquals(List.of("t"), stub.dispatched);
        /* messages 里按 protocol 续上 assistant(含 tool_calls) 与配对的 tool 结果 */
        ArrayNode msgs = (ArrayNode) stub.bodies.get(1).path("messages");
        assertEquals(3, msgs.size());
        assertEquals("c1", msgs.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("c1", msgs.get(2).path("tool_call_id").asText());
        assertEquals("工具结果", msgs.get(2).path("content").asText());
        /* 累计态：工具往返两条（assistant + tool），分段里有一条工具分段 */
        assertEquals(2, state.toolRounds().size());
        assertEquals(ChatTrace.SEG_TOOL, state.trace().get(0).type());
    }

    @Test
    void accumulatesReasoningAcrossRoundsWithSeparator() {
        Stub stub = stub(toolCall("想一"), answer("答"));
        ((ObjectNode) stub.pending.peekLast()).put("reasoning_content", "想二");

        AgentRuntime.RunState state = new AgentRuntime.RunState();
        new AgentRuntime(stub.client()).run(spec(stub, 1, bodyWithTools(), null, state));

        assertEquals("想一" + ChatTrace.REASONING_SEPARATOR + "想二", state.reasoning());
    }

    @Test
    void emptyContentIsReportedAsEmptyContent() {
        Stub stub = stub(answer(null));
        AgentRuntime.RunState state = new AgentRuntime.RunState();

        AgentRuntime.Result result = new AgentRuntime(stub.client())
                .run(spec(stub, 0, bodyWithTools(), null, state));

        assertEquals(AgentRuntime.End.EMPTY_CONTENT, result.end());
        assertNull(result.content());
    }

    @Test
    void fallsBackToReasoningWhenContentIsBlank() {
        Stub stub = stub(answer(null));
        ((ObjectNode) stub.pending.peekLast()).put("reasoning_content", "想出来的答案");

        AgentRuntime.Result result = new AgentRuntime(stub.client())
                .run(spec(stub, 0, bodyWithTools(), null, new AgentRuntime.RunState()));

        assertEquals(AgentRuntime.End.ANSWERED, result.end());
        assertEquals("想出来的答案", result.content());
    }

    @Test
    void exhaustedRoundsAreReported() {
        Stub stub = stub(toolCall(null), toolCall(null));

        AgentRuntime.Result result = new AgentRuntime(stub.client())
                .run(spec(stub, 1, bodyWithTools(), null, new AgentRuntime.RunState()));

        assertEquals(AgentRuntime.End.ROUNDS_EXHAUSTED, result.end());
        assertNull(result.content());
    }
}
