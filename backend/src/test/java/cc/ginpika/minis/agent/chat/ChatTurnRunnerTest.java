package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verify the orchestration boundary without making external model requests. */
class ChatTurnRunnerTest {
    private final ObjectMapper om = new ObjectMapper();
    private final AgentRuntime runtime = mock(AgentRuntime.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final HubSessionStore sessions = mock(HubSessionStore.class);
    private final ChatMessageStore messages = mock(ChatMessageStore.class);
    private final ChatTaskRunner tasks = mock(ChatTaskRunner.class);
    private final TurnStore turns = mock(TurnStore.class);
    private final TodoService todos = mock(TodoService.class);
    private final ChatContextService context = mock(ChatContextService.class);
    private final ChatTurnCompletion completion = mock(ChatTurnCompletion.class);
    private final HubRow row = new HubRow("s", "title", "ai", null, null, "agent", "project",
            true, false, 30, false, false, 1L, 1L);
    private final ChatTurnRunner runner = new ChatTurnRunner(runtime, mock(ToolExecutionRecorder.class),
            settings, sessions, messages, om, mock(ToolManager.class), mock(SubagentTool.class),
            mock(MemoryWriter.class), mock(ProjectStore.class), tasks, turns, todos, context,
            mock(ChatToolDispatcher.class), completion);

    ChatTurnRunnerTest() {
        when(settings.resolveChatModel(null)).thenReturn(new ModelTarget("http://model", "key", "model"));
        when(sessions.findById("s")).thenReturn(Optional.of(row));
        when(tasks.begin(anyString(), anyString(), anyString(), anyString())).thenReturn(7L);
        when(context.requestBody(eq(row), any(), isNull(), anyInt())).thenAnswer(inv -> {
            var body = om.createObjectNode();
            body.putArray("messages");
            return body;
        });
    }

    @Test void defaultChatModelFollowsAgentConfigWithoutMakingItASessionOverride(){
        when(context.modelIdFor("agent",null)).thenReturn("agent-model");
        ModelTarget target=new ModelTarget("http://fixture","key","agent-model");
        when(settings.resolveChatModel("agent-model")).thenReturn(target);
        when(runtime.run(any())).thenReturn(new AgentRuntime.Result("answer","raw",AgentRuntime.End.ANSWERED,0));
        assertEquals("answer",runner.chat("s","question",null,null).message());
        verify(context).requestBody(eq(row),eq(target),isNull(),anyInt());
        verify(sessions).updateConfig(eq("s"),isNull(),isNull(),isNull(),isNull(),isNull(),isNull(),isNull());
    }
    @Test void explicitChatModelOverridesAgentDefault(){
        when(settings.resolveChatModel("explicit")).thenReturn(new ModelTarget("http://fixture","key","explicit"));
        when(runtime.run(any())).thenReturn(new AgentRuntime.Result("answer","raw",AgentRuntime.End.ANSWERED,0));
        assertEquals("answer",runner.chat("s","question","explicit",null).message());
        verify(context,never()).modelIdFor(any(),any());
    }
    @Test void successKeepsTaskScopeAndRunsCompletionAfterReply() {
        when(runtime.run(any())).thenAnswer(inv -> {
            assertEquals("agent", AgentScope.get());
            assertEquals("project", MemoryScopeCtx.get().projectId());
            assertEquals(7L, MemoryScopeCtx.get().taskId());
            return new AgentRuntime.Result("answer", "raw", AgentRuntime.End.ANSWERED, 0);
        });
        assertEquals("answer", runner.chat("s", "question", null, null).message());
        var order = inOrder(tasks, completion);
        order.verify(tasks).done(7L, "answer");
        order.verify(completion).complete(eq(runner), eq(row), any(), eq(7L), anyLong(),
                eq("question"), eq("answer"));
        assertFalse(runner.requestStop("s"));
        assertNull(MemoryScopeCtx.get());
        assertNull(AgentScope.get());
    }

    @Test void stopPersistsPartialReplyAndSkipsCompletion() {
        when(runtime.run(any())).thenAnswer(inv -> {
            AgentRuntime.Spec spec = inv.getArgument(0);
            spec.listener().round(0);
            var delta = spec.listener().deltaListener(0);
            delta.onDelta("thinking", "partial");
            assertTrue(runner.requestStop("s"));
            delta.onDelta(null, "unwanted");
            throw new AssertionError("stop should interrupt the stream");
        });
        assertEquals("partial", runner.chatStream("s", "question", null, null, new ChatStream() {}).message());
        verify(tasks).cancelled(7L, "partial");
        verify(messages).insert(eq("s"), eq("assistant"), eq("partial"), eq("thinking"),
                any(), any(), isNull(), isNull());
        verifyNoInteractions(completion);
        assertFalse(runner.requestStop("s"));
    }

    @Test void failureReleasesSingleFlightAndClosesTask() {
        when(runtime.run(any())).thenThrow(new IllegalStateException("model failed"));
        assertThrows(IllegalStateException.class,
                () -> runner.chatStream("s", "question", null, null, new ChatStream() {}));
        verify(tasks).failed(7L);
        assertFalse(runner.requestStop("s"));
        doReturn(new AgentRuntime.Result("retry", "raw", AgentRuntime.End.ANSWERED, 0))
                .when(runtime).run(any());
        assertEquals("retry", runner.chat("s", "question", null, null).message());
    }
}
