package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.History;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.agent.runtime.ToolExecutionStore;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.ai.computer.terminal.TerminalService;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P1 scenarios through the real single-chat runner and SQLite stores with a scripted model. */
class ChatEphemeralContextTest {
    private static final String SESSION = "chat";
    private static final String ACTOR = "alice";
    private static final int WINDOW = 4096;

    private final ObjectMapper om = new ObjectMapper();
    private final ContextProps props = new ContextProps();
    private final OpenAiChatClient client = mock(OpenAiChatClient.class);
    private final TerminalService terminal = mock(TerminalService.class);
    private final ChatTurnCompletion completion = mock(ChatTurnCompletion.class);
    private final SingleConnectionDataSource data = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(data);
    private ChatMessageStore messages;
    private ChatContextManifests manifests;
    private ChatTurnRunner runner;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(data);
        var sessions = new HubSessionStore(jdbc);
        sessions.insert(SESSION, "P1 fixture");
        sessions.setMemoryOff(SESSION, true);
        messages = new ChatMessageStore(jdbc);
        var turns = new TurnStore(jdbc);
        var settings = mock(SettingsService.class);
        when(settings.resolveChatModel(null)).thenReturn(new ModelTarget("http://fixture", "key", "model"));
        when(settings.chatContextWindow(null)).thenReturn(WINDOW);
        var prompts = mock(AgentPromptService.class);
        when(prompts.builtinSystem(null)).thenReturn("保留对话已确认的约定。");
        var projects = mock(ProjectStore.class);
        var tools = new ToolManager(List.of(new RunCommandTool()), om);
        props.setEphemeralChat(true);
        props.setReserveOutputTokens(512);
        props.setCompressHistory(false); // The off-switch comparison uses unfiltered legacy playback.
        var history = new History(messages, turns, new ChatLiveStore(), om, props);
        var sources = new ChatContextSources(jdbc, om);
        var context = new ChatContextService(sessions, messages, om, settings, prompts, tools, projects,
                new ContextBuilder(om, List.of(history)), props, history, sources);
        var dispatcher = new ChatToolDispatcher(om, new ToolValidator(tools, om),
                null, null, null, terminal, null, null);
        var recorder = new ToolExecutionRecorder(new ToolExecutionStore(jdbc), mock(RuntimeEventBus.class));
        runner = new ChatTurnRunner(new AgentRuntime(client), recorder, settings, sessions, messages, om, tools,
                mock(SubagentTool.class), mock(MemoryWriter.class), projects, new ChatTaskRunner(new TaskStore(jdbc)),
                turns, mock(TodoService.class), context, dispatcher, completion);
        manifests = new ChatContextManifests(sessions, turns);
        runner.setContextPilot(sources, manifests);
    }

    @AfterEach
    void close() {
        data.destroy();
    }

    @Test
    void keepsRecentNarrativeAgreementAndRestoresLegacyReplayWhenDisabled() {
        String oldText = "旧过程\n" + "镇上的守卫一遍遍巡过石桥。\n".repeat(1000);
        append("user", "继续旅程");
        long oldReplyId = append("assistant", oldText);
        append("user", "已撤回的支线");
        long withdrawn = append("assistant", "不可自动回放的旧结论");
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                "chat_message:" + withdrawn);
        append("user", "约定后续只用代号青灯。");
        append("assistant", "确认使用代号青灯。");
        when(client.chatRaw(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            assertFalse(replay.contains("旧过程"));
            assertFalse(replay.contains("已撤回的支线"));
            assertFalse(replay.contains("不可自动回放的旧结论"));
            assertTrue(replay.contains("确认使用代号青灯"));
            assertTrue(replay.contains("发出约定的信号"));
            return answer("青灯");
        });
        assertEquals("青灯", chat("发出约定的信号"));
        var outbound = manifests.recent(SESSION, ACTOR, null, 10);
        assertEquals(1, outbound.size());
        var selection = outbound.getFirst().selection();
        assertTrue(selection.omitted().stream().flatMap(entry -> entry.refs().stream())
                .anyMatch(ref -> ref.value().equals("message:" + oldReplyId)));
        assertEquals(oldText, messages.listBySession(SESSION).get(1).content());

        props.setEphemeralChat(false);
        when(client.chatRaw(any(), any(), any(), any())).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            assertTrue(body.path("messages").toString().contains("旧过程"));
            assertFalse(body.path("tools").toString().contains(ChatContextSources.TOOL_NAME));
            return answer("沿用完整回放");
        });
        assertEquals("沿用完整回放", chat("切换回原有选取"));
        assertEquals(outbound, manifests.recent(SESSION, ACTOR, null, 10));
    }

    @Test
    void archivesOversizeLiveToolResultAndReadsExactEvidenceInTheSameTurn() {
        String log = "INFO health probe ok\n".repeat(2000);
        String evidence = "ERROR root cause 🧩 missing tls certificate";
        when(terminal.run(any(JsonNode.class), eq(TerminalService.SOURCE_CHAT)))
                .thenReturn(new ToolResult(RunCommandTool.NAME, "{}", log + evidence, false));
        when(client.chatRaw(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            assertTrue(body.path("tools").toString().contains(ChatContextSources.TOOL_NAME));
            return toolCall("logs", RunCommandTool.NAME,
                    om.createObjectNode().put("target", "fixture").put("command", "cat service.log"));
        }).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String preview = toolResult(body, "logs");
            assertFalse(preview.contains(evidence));
            assertTrue(preview.length() < log.length());
            var ref = Pattern.compile("artifact:[1-9][0-9]*").matcher(preview);
            assertTrue(ref.find(), "the reduced live tool result must include its readable artifact reference");
            return toolCall("read", ChatContextSources.TOOL_NAME, om.createObjectNode().put("action", "read")
                    .put("source", ref.group()).put("offset", log.length()).put("length", 200));
        }).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String exact = om.readTree(toolResult(body, "read")).path("content").asText();
            assertEquals(evidence, exact);
            return answer(exact);
        });

        assertEquals(evidence, chat("根据服务日志找出启动失败的根因"));
        assertEquals(log + evidence, jdbc.queryForObject(
                "SELECT content FROM task_artifact WHERE kind = 'tool_result'", String.class));
        var outbound = manifests.recent(SESSION, ACTOR, null, 10);
        assertEquals(List.of(2, 1, 0), outbound.stream().map(ChatContextManifests.Manifest::round).toList());
        assertEquals(1, outbound.stream().map(ChatContextManifests.Manifest::executionId).distinct().count());
        assertTrue(outbound.get(1).selection().selected().stream().flatMap(entry -> entry.refs().stream())
                .anyMatch(ref -> ref.value().startsWith("artifact:")));
        verify(terminal).run(any(JsonNode.class), eq(TerminalService.SOURCE_CHAT));
    }

    @Test
    void fitsDelegatedSummaryAndPagesArchivedTailWithoutPromotingReplyToMemory() {
        String hiddenTail = "保存在报告原文末尾的根因";
        String prefix = "逐步检查日志\n".repeat(1000);
        String reportText = prefix + hiddenTail;
        long input = append("user", "调查故障");
        var taskStore = new TaskStore(jdbc);
        long parent = new ChatTaskRunner(taskStore).begin(SESSION, AgentDirectory.BUILTIN_ID, "parent", "调查故障");
        var turns = new TurnStore(jdbc);
        long parentTurn = turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, input, parent);
        long child = taskStore.beginExec(ExecStore.KIND_AGENT, null, null, parent, SESSION,
                AgentDirectory.BUILTIN_ID, "日志分析", """
                {"_minis_context":{"parent_task_id":%d,"turn_id":%d,"project_id":null,"refs":[]}}
                """.formatted(parent, parentTurn), null);
        long report = messages.insert(SESSION, "assistant", reportText, null, null, null,
                ChatMessageStore.KIND_SUBAGENT, """
                {"title":"日志分析","summary":"检查完成","status":"done","execId":%d,"contextScoped":true}
                """.formatted(child));
        turns.record(SESSION, TurnTrigger.Source.SUBAGENT_REPORT, report);
        when(client.chatRaw(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            assertTrue(replay.contains("检查完成"));
            assertTrue(replay.contains("message:" + report));
            assertFalse(replay.contains("逐步检查日志"));
            assertFalse(replay.contains(hiddenTail));
            return toolCall("report", ChatContextSources.TOOL_NAME,
                    om.createObjectNode().put("action", "read").put("source", "message:" + report)
                            .put("offset", prefix.length()));
        }).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            assertEquals(hiddenTail, om.readTree(toolResult(body, "report")).path("content").asText());
            return answer("已定位故障");
        }).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            assertFalse(replay.contains("检查完成"));
            assertFalse(replay.contains(hiddenTail));
            return answer("继续");
        });

        assertEquals("已定位故障", chat("核对调查报告尾部"));
        assertEquals(reportText, messages.listBySession(SESSION).stream()
                .filter(row -> row.id() == report).findFirst().orElseThrow().content());
        assertFalse(messages.listBySession(SESSION).getLast().toolRounds().contains(hiddenTail));
        verify(completion).complete(any(), any(), any(), anyLong(), anyLong(), eq("核对调查报告尾部"),
                eq("已定位故障"), eq(false));
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)", "task:" + child);
        assertEquals("继续", chat("继续检查"));
    }

    @Test
    void alternateExecutionStillUsesItsOwnRequestBodyWithPilotEnabled() {
        var execution = mock(ChatExecution.class);
        var row = new HubRow("table", null, "multi-agent", null, null, "peer", null,
                true, false, 0, false, false, 0L, 0L);
        when(execution.resolveOrCreate("table")).thenReturn(row);
        var assembled = om.createObjectNode().put("model", "model");
        assembled.putArray("messages").addObject().put("role", "user").put("content", "圆桌议题");
        when(execution.requestBody(eq(row), any(), isNull(), eq(WINDOW))).thenReturn(assembled);
        when(client.chatRaw(any(), same(assembled), any(), any())).thenReturn(answer("圆桌答复"));

        assertEquals("圆桌答复", runner.chatStream(execution, "table", "议题", null, null, new ChatStream() {}).message());
        verify(client, never()).chatRaw(any(), any(), any(), any(), any(), any());
    }

    @Test
    void supplementCancelsTheInFlightRequestAndBothInputReferencesAppearInTheNextManifest() {
        String initial = "先复核发布计划";
        String supplement = "补充检查回退步骤";
        var stream = new ChatStream() {
            long initialId;
            long supplementId;
            final List<Integer> resets = new ArrayList<>();

            @Override public boolean steerable() { return true; }
            @Override public String actorId() { return ACTOR; }
            @Override public void meta(String sessionId, long messageId) { initialId = messageId; }
            @Override public void steer(long messageId, String text) { supplementId = messageId; }
            @Override public void reset(int round) { resets.add(round); }
        };
        when(client.chatRaw(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            recordOutbound(inv.getArgument(1), inv.getArgument(5));
            OpenAiChatClient.CallControl control = inv.getArgument(4);
            assertEquals(runner.steer(SESSION, stream.initialId, supplement, ACTOR), stream.supplementId);
            assertTrue(control.cancelled());
            throw new OpenAiChatClient.ChatFailure(0, "fixture cancelled after send", null);
        }).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            assertEquals(2, replay.split(initial, -1).length);
            assertEquals(2, replay.split(supplement, -1).length);
            return answer("已复核发布和回退步骤");
        });

        assertEquals("已复核发布和回退步骤", runner.chatStream(SESSION, initial, null, null, stream).message());
        assertEquals(List.of(0), stream.resets);
        var outbound = manifests.recent(SESSION, ACTOR, null, 10);
        assertEquals(List.of(1, 0), outbound.stream().map(ChatContextManifests.Manifest::round).toList());
        List<String> selectedRefs = outbound.getFirst().selection().selected().stream()
                .flatMap(entry -> entry.refs().stream()).map(ref -> ref.value()).toList();
        assertTrue(selectedRefs.containsAll(List.of("message:" + stream.initialId, "message:" + stream.supplementId)));
    }

    private long append(String role, String text) {
        return messages.insert(SESSION, role, text, null, null, null, null, null);
    }

    private String chat(String input) {
        return runner.chat(SESSION, input, null, null, null, ACTOR).message();
    }

    private static void recordOutbound(ObjectNode body, OpenAiChatClient.SubmissionListener listener) {
        assertNotNull(listener);
        assertTrue(ContextPlan.estimateRequest(body) + 512 + 1024 <= WINDOW);
        listener.submitted(body, body.toString().getBytes(StandardCharsets.UTF_8).length);
    }

    private OpenAiChatClient.RawResult answer(String content) {
        return new OpenAiChatClient.RawResult(om.createObjectNode().put("content", content), "fixture", "stop");
    }

    private OpenAiChatClient.RawResult toolCall(String id, String name, ObjectNode args) {
        var message = om.createObjectNode();
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                .putObject("function").put("name", name).put("arguments", args.toString());
        return new OpenAiChatClient.RawResult(message, "fixture", "tool_calls");
    }

    private static String toolResult(ObjectNode body, String id) {
        return StreamSupport.stream(body.path("messages").spliterator(), false)
                .filter(message -> id.equals(message.path("tool_call_id").asText()))
                .findFirst().orElseThrow().path("content").asText();
    }
}
