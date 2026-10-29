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
    @Test void concurrentConversationDoesNotSettleWorkersPersonalTodos() {
        ChatExecution execution = mock(ChatExecution.class);
        when(execution.resolveOrCreate("s")).thenReturn(row);
        when(execution.requestBody(eq(row), any(), any(), anyInt())).thenAnswer(inv -> {
            var body = om.createObjectNode(); body.putArray("messages"); return body;
        });
        when(execution.settleTodosOnFinish()).thenReturn(false);
        when(runtime.run(any())).thenReturn(new AgentRuntime.Result("工作仍在进行", "raw", AgentRuntime.End.ANSWERED, 0));
        runner.chatStream(execution, "s", "进度如何？", null, null, new ChatStream() {});
        verify(todos, never()).settle(anyString(), any());
    }
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

    @Test void workCancellationWithPartialTextDoesNotRecordASuccessfulReply() {
        var execution = spy(runner);
        doReturn(false).when(execution).keepPartialReplyOnCancel();
        when(runtime.run(any())).thenAnswer(inv -> {
            AgentRuntime.Spec spec = inv.getArgument(0);
            spec.listener().round(0);
            var delta = spec.listener().deltaListener(0);
            delta.onDelta(null, "尚未完成的工作");
            runner.requestStop("s");
            delta.onDelta(null, "取消后的内容");
            throw new AssertionError("stream must stop");
        });
        assertThrows(ChatStream.ChatCancelledException.class,
                () -> runner.chatStream(execution, "s", "work", null, null, new ChatStream() {}));
        verify(execution, never()).recordReply(any(), any(), any(), any(), any());
        verify(tasks).cancelled(7L, "尚未完成的工作");
        verifyNoInteractions(completion);
    }

    @Test void generatedImagesAreAppendedToStreamAndPersistedWhenModelOmitsThem() {
        String url = "/api/comfyui/images/11111111-1111-1111-1111-111111111111";
        when(runtime.run(any())).thenAnswer(inv -> {
            AgentRuntime.Spec spec = inv.getArgument(0);
            spec.listener().round(1);
            spec.listener().deltaListener(1).onDelta(null, "已生成");
            var assistant = om.createObjectNode().put("role", "assistant");
            assistant.putArray("tool_calls").addObject().put("id", "image-call").putObject("function").put("name", "generate_image");
            var tool = om.createObjectNode().put("role", "tool").put("tool_call_id", "image-call");
            tool.put("content", "{\"images\":[{\"url\":\"" + url + "\"}]}");
            spec.runState().toolRounds().add(assistant);
            spec.runState().toolRounds().add(tool);
            return new AgentRuntime.Result("已生成", "raw", AgentRuntime.End.ANSWERED, 1);
        });
        StringBuilder streamed = new StringBuilder();
        var reply = runner.chatStream("s", "画一只狐狸", null, null, new ChatStream() {
            @Override public void delta(String reasoning, String content) { if (content != null) streamed.append(content); }
        });
        String expected = "已生成\n\n![生成图片](" + url + ")";
        assertEquals(expected,reply.message());
        assertEquals(expected,streamed.toString());
        verify(messages).insert(eq("s"),eq("assistant"),eq(expected),isNull(),any(),any(),isNull(),isNull());
        verify(tasks).done(7L,expected);
    }

    @Test void cancellationPersistsToolSpeechEvenWhenContentDeltasAreEmpty() {
        var execution = spy(runner);
        doReturn("请确认这次交付的范围").when(execution).replyOnCancel("");
        when(runtime.run(any())).thenAnswer(inv -> {
            AgentRuntime.Spec spec = inv.getArgument(0);
            spec.listener().round(0);
            runner.requestStop("s");
            spec.listener().round(1);
            throw new AssertionError("must stop");
        });
        assertEquals("请确认这次交付的范围", runner.chatStream(execution, "s", "question", null, null, new ChatStream() {}).message());
        verify(messages).insert(eq("s"), eq("assistant"), eq("请确认这次交付的范围"), any(), any(), any(), isNull(), isNull());
        verifyNoInteractions(completion);
    }

    @Test void askingForInputCanStopOtherExecutionsWithoutCancellingTheQuestion() throws Exception {
        var execution = spy(runner); doReturn(false).when(execution).singleFlight();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(runtime.run(any())).thenAnswer(inv -> {
            AgentRuntime.Spec spec = inv.getArgument(0);
            spec.listener().round(0);
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); assertTrue(resume.await(3, java.util.concurrent.TimeUnit.SECONDS));
                spec.listener().round(1);
                throw new AssertionError("other execution must stop");
            }
            runner.requestStopOthers("s");
            spec.listener().deltaListener(0).onDelta(null, "请你确认范围");
            resume.countDown();
            return new AgentRuntime.Result("请你确认范围", "raw", AgentRuntime.End.ANSWERED, 0);
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var other = executor.submit(() -> assertThrows(ChatStream.ChatCancelledException.class,
                    () -> runner.chatStream(execution, "s", "work", null, null, new ChatStream() {})));
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("请你确认范围", runner.chatStream(execution, "s", "question", null, null, new ChatStream() {}).message());
            other.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertFalse(runner.requestStop("s"));
    }
}
