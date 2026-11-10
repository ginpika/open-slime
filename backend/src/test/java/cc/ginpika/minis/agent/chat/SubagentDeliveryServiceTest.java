package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.context.ChatProtocol;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.subagent.SubagentScope;
import cc.ginpika.minis.agent.subagent.SubagentSynthesizer;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class SubagentDeliveryServiceTest {
    private static final String SESSION = "chat";
    private static final long EXEC = 42L;
    private static final ModelTarget TARGET = new ModelTarget("http://local", "test", "model");
    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final HubSessionStore sessions = new HubSessionStore(jdbc);
    private final ChatMessageStore messages = new ChatMessageStore(jdbc);
    private final TurnStore turns = new TurnStore(jdbc);
    private final OpenAiChatClient client = mock(OpenAiChatClient.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final PromptManager prompts = mock(PromptManager.class);
    private final SessionEventBus events = mock(SessionEventBus.class);
    private final SubagentScope scope = mock(SubagentScope.class);
    private final SubagentSynthesis synthesizer = spy(new SubagentSynthesizer(client, new ExecStore(jdbc), om));
    private final SubagentDeliveryService delivery = new SubagentDeliveryService(settings, client, sessions,
            messages, turns, om, prompts, events, synthesizer,
            scope, new ContextProps(), new TransactionTemplate(new DataSourceTransactionManager(ds)));

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        sessions.insert(SESSION, "scope");
        when(settings.resolveChatModel(null)).thenReturn(TARGET);
        when(settings.chatContextWindow(null)).thenReturn(32768);
        when(prompts.chatSystem(null)).thenReturn("main instructions");
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void keepsFullReportInTranscriptAndSynthesizesOnlyFromThisBatch() throws Exception {
        when(scope.visible(EXEC, SESSION)).thenReturn(true);
        messages.insert(SESSION, "user", "previous conversation", null, null, null, null, null);
        String full = "根因已核验。" + "报告细节 ".repeat(500) + "ARCHIVE_TAIL";
        report(full);

        var saved = messages.listBySession(SESSION).getLast();
        assertEquals(full, saved.content());
        assertTrue(om.readTree(saved.meta()).path("contextScoped").asBoolean());
        assertTrue(ChatProtocol.readSubagentBrief(om, saved.kind(), saved.meta()).summary().length() <= 141);
        assertEquals(1, turns.turns(SESSION).size());
        verifyNoInteractions(client);

        when(client.chatRaw(eq(TARGET), any(ObjectNode.class), isNull(), eq(TokenUsageService.SOURCE_CHAT)))
                .thenAnswer(call -> {
                    ObjectNode outbound = call.getArgument(1);
                    assertEquals(2, outbound.path("messages").size());
                    String input = outbound.path("messages").path(1).path("content").asText();
                    assertTrue(input.contains("根因已核验"));
                    assertTrue(input.contains("message:" + saved.id()));
                    assertFalse(input.contains("previous conversation"));
                    assertFalse(input.contains("ARCHIVE_TAIL"));
                    return new OpenAiChatClient.RawResult(om.createObjectNode().put("content", "综合结论"), "{}");
                });
        batch();

        var summary = messages.listBySession(SESSION).getLast();
        assertEquals(ChatMessageStore.KIND_SUBAGENT_BATCH, summary.kind());
        assertEquals(EXEC, om.readTree(summary.meta()).path("execIds").path(0).asLong());
        assertEquals(saved.id(), om.readTree(summary.meta()).path("messageIds").path(0).asLong());
        assertEquals("综合结论", summary.content());
        assertTrue(sessions.findById(SESSION).orElseThrow().unread());
    }

    @Test
    void rejectsLateReportAndDoesNotPublishSynthesisAfterRevocation() {
        report("late");
        assertTrue(messages.listBySession(SESSION).isEmpty());
        verifyNoInteractions(events, client);

        when(scope.visible(EXEC, SESSION)).thenReturn(true);
        report("visible for now");
        long reportId = messages.listBySession(SESSION).getFirst().id();
        when(client.chatRaw(eq(TARGET), any(ObjectNode.class), isNull(), eq(TokenUsageService.SOURCE_CHAT)))
                .thenAnswer(call -> {
                    jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                            "chat_message:" + reportId);
                    return new OpenAiChatClient.RawResult(om.createObjectNode().put("content", "stale"), "{}");
                });
        batch();

        assertEquals(1, messages.listBySession(SESSION).size());
        assertFalse(sessions.findById(SESSION).orElseThrow().unread());
    }

    @Test
    void excludesArchivedScopedRowsWhenLegacyBatchSynthesisRunsLater() {
        when(scope.visible(EXEC, SESSION)).thenReturn(true);
        report("archived scoped report");
        messages.insert(SESSION, "assistant", "archived scoped summary", null, null, null,
                ChatMessageStore.KIND_SUBAGENT_BATCH, "{}");
        messages.insert(SESSION, "assistant", "legacy report", null, null, null,
                ChatMessageStore.KIND_SUBAGENT, null);
        doAnswer(call -> {
            List<OpenAiChatClient.ChatMessage> prior = call.getArgument(1);
            assertEquals(List.of("main instructions", "legacy report"),
                    prior.stream().map(OpenAiChatClient.ChatMessage::content).toList());
            return "legacy summary";
        }).when(synthesizer).synthesize(eq(TARGET), anyList(), eq("legacy"));

        delivery.onSubagentBatchDone(new SubagentBatchDoneEvent(SESSION, "legacy", List.of(23L)));

        assertEquals("legacy summary", messages.listBySession(SESSION).getLast().content());
    }

    /**
     * R8：同步完成与后台回报共用同一张幂等卡片（同一执行 id 只落一条），且两条终态结果
     * 都参与该批归纳。R3：卡片记录其授权来源依赖，批次归纳记录对成员卡片的依赖。
     */
    @Test
    void synchronousAndBackgroundResultsShareOneCardAndJoinTheSameBatch() throws Exception {
        when(scope.visible(EXEC, SESSION)).thenReturn(true);
        long user = messages.insert(SESSION, "user", "排查目标", null, null, null, null, null);
        String source = "message:" + user;

        // 同步完成（min 等待内返回）与后台回报走同一个事件：重复投递只留一张卡
        delivery.onSubagentReport(new SubagentReportEvent(SESSION, "同步件", EXEC, "done", "同步结论",
                null, true, List.of(source)));
        delivery.onSubagentReport(new SubagentReportEvent(SESSION, "同步件", EXEC, "done", "同步结论",
                null, true, List.of(source)));
        assertEquals(1, messages.listBySession(SESSION).stream()
                .filter(row -> ChatMessageStore.KIND_SUBAGENT.equals(row.kind())).count());
        long card = messages.listBySession(SESSION).stream()
                .filter(row -> ChatMessageStore.KIND_SUBAGENT.equals(row.kind()))
                .findFirst().orElseThrow().id();
        assertEquals(List.of(source), jdbc.queryForList(
                "SELECT ref FROM chat_message_dependency WHERE message_id = ? ORDER BY ref",
                String.class, card));

        // 另一条后台结果进同一批：归纳成员包含两者
        when(scope.visible(43L, SESSION)).thenReturn(true);
        delivery.onSubagentReport(new SubagentReportEvent(SESSION, "后台件", 43L, "done", "后台结论",
                null, true, List.of()));
        when(client.chatRaw(eq(TARGET), any(ObjectNode.class), isNull(), eq(TokenUsageService.SOURCE_CHAT)))
                .thenReturn(new OpenAiChatClient.RawResult(om.createObjectNode().put("content", "综合结论"), "{}"));
        delivery.onSubagentBatchDone(new SubagentBatchDoneEvent(SESSION, "batch", List.of(EXEC, 43L), true));

        var summary = messages.listBySession(SESSION).getLast();
        assertEquals(ChatMessageStore.KIND_SUBAGENT_BATCH, summary.kind());
        assertEquals(2, om.readTree(summary.meta()).path("execIds").size());
        long backgroundCard = messages.listBySession(SESSION).stream()
                .filter(row -> ChatMessageStore.KIND_SUBAGENT.equals(row.kind()) && row.id() != card)
                .findFirst().orElseThrow().id();
        assertTrue(jdbc.queryForList("SELECT ref FROM chat_message_dependency WHERE message_id = ?",
                String.class, summary.id()).containsAll(List.of("message:" + card, "message:" + backgroundCard)));
    }

    private void report(String content) {
        delivery.onSubagentReport(new SubagentReportEvent(SESSION, "排障", EXEC, "done", content, null, true));
    }

    private void batch() {
        delivery.onSubagentBatchDone(new SubagentBatchDoneEvent(SESSION, "batch", List.of(EXEC), true));
    }
}
