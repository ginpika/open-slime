package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.chat.RequestUserInputTool;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Real chat orchestration + shared model loop + question dispatcher; only model and storage are mocked. */
class ChatUserInputTest {
    private final ObjectMapper om = new ObjectMapper();
    private final OpenAiChatClient client = mock(OpenAiChatClient.class);
    private final ChatMessageStore messages = mock(ChatMessageStore.class);
    private final HubSessionStore sessions = mock(HubSessionStore.class);
    private final ChatContextService context = mock(ChatContextService.class);
    private final List<ObjectNode> requests = new ArrayList<>();
    private final ToolManager tools = new ToolManager(List.of(new RequestUserInputTool()), om);
    private final ChatToolDispatcher dispatcher = spy(new ChatToolDispatcher(om, new ToolValidator(tools, om),
            null, null, null, null, null, null));
    private final String args = """
            {"question":"你希望先做哪部分？","options":[{"label":"界面","description":"先完成可交互界面"},{"label":"后端"}]}
            """;

    private ObjectNode questionCall(String json) {
        ObjectNode answer = om.createObjectNode();
        answer.putArray("tool_calls").addObject().put("id", "question-1").put("type", "function")
                .putObject("function").put("name", RequestUserInputTool.NAME).put("arguments", json);
        return answer;
    }
    private ChatTurnRunner runner() {
        var settings = mock(SettingsService.class);
        when(settings.resolveChatModel(any())).thenReturn(new ModelTarget("http://fixture", "key", "model"));
        var row = new HubSessionStore.HubRow("s", "会话", null, null, null, "builtin", null,
                false, false, 0, false, false, 1L, 1L);
        when(sessions.findById("s")).thenReturn(Optional.of(row));
        when(context.requestBody(eq(row), any(), any(), anyInt())).thenAnswer(inv -> {
            var body = om.createObjectNode(); body.putArray("messages").addObject().put("role", "user").put("content", "帮我做一个功能");
            body.set("tools", tools.toolsOf(ToolSurface.CHAT)); return body;
        });
        var recorder = mock(ToolExecutionRecorder.class);
        when(recorder.record(any(), any(), any(), any(), any())).thenAnswer(inv -> inv.<Supplier<String>>getArgument(4).get());
        return new ChatTurnRunner(new AgentRuntime(client), recorder, settings, sessions, messages, om, tools,
                mock(SubagentTool.class), mock(MemoryWriter.class), mock(ProjectStore.class), mock(ChatTaskRunner.class),
                mock(TurnStore.class), mock(TodoService.class), context, dispatcher, mock(ChatTurnCompletion.class));
    }
    private void responses(ObjectNode... responses) {
        when(client.chatRaw(any(), any(), any(), any())).thenAnswer(inv -> {
            requests.add(((ObjectNode) inv.getArgument(1)).deepCopy());
            assertTrue(requests.size() <= responses.length, "must not ask the model to continue while waiting for user input");
            return new OpenAiChatClient.RawResult(responses[requests.size() - 1], "raw");
        });
    }
    @Test void questionStopsModelLoopAndPersistsReplayableOptions() {
        responses(questionCall(args));
        List<String> streamedTools = new ArrayList<>();
        var reply = runner().chatStream("s", "帮我做一个功能", null, null, new ChatStream() {
            @Override public void tool(String name, String arguments) { streamedTools.add(name); }
        });
        assertEquals(RequestUserInputTool.content(RequestUserInputTool.read(om, args)), reply.message());
        assertEquals(1, requests.size());
        assertEquals(List.of(RequestUserInputTool.NAME), streamedTools);
        var trace = org.mockito.ArgumentCaptor.forClass(String.class);
        var rounds = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(messages).insert(eq("s"), eq("assistant"), eq(reply.message()), isNull(), trace.capture(), rounds.capture(), isNull(), isNull());
        assertTrue(trace.getValue().contains("request_user_input"));
        assertTrue(rounds.getValue().contains("waiting_for_user"));
        assertTrue(rounds.getValue().contains("question-1"));
    }
    @Test void narrationBeforeQuestionIsKeptInThePersistedReply() {
        var narrated = questionCall(args);
        narrated.put("content", "先说结论：我建议先做界面。");
        responses(narrated);
        var reply = runner().chat("s", "帮我做一个功能", null, null);
        assertEquals("先说结论：我建议先做界面。\n\n" + RequestUserInputTool.content(RequestUserInputTool.read(om, args)),
                reply.message());
        assertEquals(1, requests.size());
    }
    @Test void invalidQuestionReturnsToolErrorAndAllowsCorrection() {
        responses(questionCall("{\"question\":\"？\",\"options\":[]}"), questionCall(args));
        assertTrue(runner().chat("s", "帮我做一个功能", null, null).message().contains("界面"));
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).path("messages").toString().contains("询问参数无效"));
    }
    @Test void mixedBatchRunsNoToolsBeforeRetryingQuestionAlone() {
        var mixed = questionCall(args);
        ((com.fasterxml.jackson.databind.node.ArrayNode) mixed.path("tool_calls")).addObject()
                .put("id", "write-1").putObject("function").put("name", "write_todos").put("arguments", "{}");
        responses(mixed, questionCall(args));
        runner().chat("s", "帮我做一个功能", null, null);
        verify(dispatcher, times(1)).runTool(any(), any(), eq("s"));
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).path("messages").toString().contains("本批工具均未执行"));
    }
    @Test void parametersAreBoundedAndToolIsOnlyExposedToHomeChat() {
        assertEquals(1, tools.toolsOf(ToolSurface.CHAT).size());
        assertTrue(tools.toolsOf(ToolSurface.PEER).isEmpty());
        assertTrue(tools.toolsOf(ToolSurface.HEARTBEAT).isEmpty());
        assertFalse(new RequestUserInputTool().agentsMayCall());
        for (String invalid : List.of("null", "[]", "{", "{\"question\":4}",
                "{\"question\":\"？\",\"options\":[{\"label\":\"一样\"},{\"label\":\"一样\"}]}")) {
            assertThrows(IllegalArgumentException.class, () -> RequestUserInputTool.read(om, invalid));
        }
    }
}
