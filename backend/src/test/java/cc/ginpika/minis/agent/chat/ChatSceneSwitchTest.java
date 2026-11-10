package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.History;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.agent.runtime.ToolExecutionStore;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.ai.computer.terminal.TerminalService;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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

/**
 * R1 · 在原会话内切换任务：跑团 → 普通代码问答 → 恢复跑团。
 * 出站请求只含对应任务的角色与续接，代码问题保留 Agent 的稳定规则，恢复后能找回原约定。
 */
class ChatSceneSwitchTest {
    private static final String SESSION = "chat";
    private static final String ACTOR = "alice";
    private static final int WINDOW = 20000;

    private final ObjectMapper om = new ObjectMapper();
    private final ContextProps props = new ContextProps();
    private final OpenAiChatClient client = mock(OpenAiChatClient.class);
    private final TerminalService terminal = mock(TerminalService.class);
    private final ChatTurnCompletion completion = mock(ChatTurnCompletion.class);
    private final SingleConnectionDataSource data = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(data);
    private ChatTurnRunner runner;
    private ChatScenes scenes;
    private ChatMessageStore messages;
    private ChatContextManifests manifests;
    private ChatLiveStore live;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(data);
        var sessions = new HubSessionStore(jdbc);
        sessions.insert(SESSION, "场景切换");
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
        var history = new History(messages, turns, live = new ChatLiveStore(), om, props);
        var sources = new ChatContextSources(jdbc, om);
        scenes = new ChatScenes(jdbc, live, mock(SessionEventBus.class));
        var context = new ChatContextService(sessions, messages, om, settings, prompts, tools, projects,
                new ContextBuilder(om, List.of(history)), props, history, sources, turns, scenes);
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
    void tabletopThenCodeQuestionThenResumeKeepsSegmentsIsolated() {
        var roleScene = scenes.start(SESSION, "跑团", "你是地下城主持人，用叙事口吻回应。", "队伍只使用代号青灯。");
        when(client.chatRaw(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            assertTrue(replay.contains("地下城主持人"));
            assertTrue(replay.contains("代号青灯"));
            return answer("队伍在薄雾中前行。");
        });
        assertEquals("队伍在薄雾中前行。", chat("继续跑团"));

        scenes.end(SESSION, roleScene.id());
        doAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            // 代码问题保留 Agent 的稳定规则，不带入跑团角色与旧剧情
            assertFalse(replay.contains("地下城主持人"));
            assertFalse(replay.contains("队伍在薄雾中前行"));
            assertFalse(replay.contains("继续跑团"));
            assertTrue(replay.contains("保留对话已确认的约定"));
            return answer("这段代码的问题是空指针。");
        }).when(client).chatRaw(any(), any(), any(), any(), any(), any());
        assertEquals("这段代码的问题是空指针。", chat("帮我看看这段代码"));

        scenes.resume(SESSION, roleScene.id());
        doAnswer(inv -> {
            ObjectNode body = inv.getArgument(1);
            recordOutbound(body, inv.getArgument(5));
            String replay = body.path("messages").toString();
            // 恢复后：角色与原约定回归，退出期间的代码问答不进入
            assertTrue(replay.contains("地下城主持人"));
            assertTrue(replay.contains("队伍在薄雾中前行"));
            assertTrue(replay.contains("继续跑团"));
            assertFalse(replay.contains("这段代码的问题是空指针"));
            assertFalse(replay.contains("帮我看看这段代码"));
            return answer("队伍回到了薄雾中的石桥。");
        }).when(client).chatRaw(any(), any(), any(), any(), any(), any());
        assertEquals("队伍回到了薄雾中的石桥。", chat("回到跑团"));

        // 转录完整保留三段对话；Manifest 能对照任务段
        assertEquals(6, messages.listBySession(SESSION).size());
        var outbound = manifests.recent(SESSION, ACTOR, null, 10);
        assertEquals(3, outbound.size());
        assertEquals(roleScene.id(), outbound.get(2).selection().sceneId());
        assertNull(outbound.get(1).selection().sceneId());
    }

    private static void recordOutbound(ObjectNode body, OpenAiChatClient.SubmissionListener listener) {
        if (listener != null) {
            listener.submitted(body, body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }

    @Test
    void sceneLifecycleRejectsUnsafeTransitionsAndDuplicateActiveScenes() {
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', 1)", SESSION + "-x");
        var scene = scenes.start(SESSION, "任务A", null, null);
        assertEquals("active", scene.status());
        assertNotNull(scenes.active(SESSION));

        // 已有活动任务段时不能再开始 / 恢复
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> scenes.start(SESSION, "任务B", null, null));
        var other = scenes.start(SESSION + "-x", "other", null, null); // 另一会话不受影响
        scenes.end(SESSION + "-x", other.id());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> scenes.resume(SESSION, other.id()));

        // 生成中拒绝切换（安全点）
        var run = live.start(SESSION, 1L, "in-flight");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> scenes.end(SESSION, scene.id()));
        live.finish(run, "done");

        // 结束后可恢复；恢复后再结束仍可再恢复
        scenes.end(SESSION, scene.id());
        assertNull(scenes.active(SESSION));
        assertEquals("ended", scenes.state(SESSION).resumable().getFirst().status());
        assertEquals("active", scenes.resume(SESSION, scene.id()).status());
    }

    @Test
    void segmentSelectionKeepsOrdinaryHistoryBeforeFirstExplicitScene() {
        // 旧数据（无任务段）沿用有界窗口前的全量选取：首次显式切换前不受影响
        long user = messages.insert(SESSION, "user", "旧普通提问", null, null, null, null, null);
        long reply = messages.insert(SESSION, "assistant", "旧普通回答", null, null, null, null, null);
        var visible = new ChatContextSources(jdbc, om)
                .segmentMessageIds(SESSION, cc.ginpika.minis.agent.member.AgentDirectory.BUILTIN_ID, null, null, 0);
        assertTrue(visible.contains(user));
        assertTrue(visible.contains(reply));
    }

    private String chat(String input) {
        return runner.chat(SESSION, input, null, null, null, ACTOR).message();
    }

    private OpenAiChatClient.RawResult answer(String content) {
        return new OpenAiChatClient.RawResult(om.createObjectNode().put("content", content), "fixture", "stop");
    }
}
