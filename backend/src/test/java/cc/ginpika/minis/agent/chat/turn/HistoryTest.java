package cc.ginpika.minis.agent.chat.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.ChatLiveStore;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.config.ContextProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * History（Turn 序列）的回归：行 → Turn 的配对、回放形状、以及末尾的在途尾巴。
 *
 * <p>钉住三件事：① 用户行 + 紧随的 assistant 行配成一条 Turn；② 没有用户行在前的结论行（子代理投回）
 * 自成一条 Turn 并带出处头；③ 进行中的生成以 {@code inFlight} 尾巴挂在末尾——它出现在 {@link History#turns}
 * 里（供读侧看全量），但 {@link History#replay} 只回放它的问题、不回放半截回答。
 */
class HistoryTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 预算给足 ＝ 不降级，用来对照「不到临界一行不动」 */
    private static final int BIG_BUDGET = 1_000_000;

    /** 与 schema.sql 的 chat_message 同构（仅取回放用到的列） */
    private static final String CHAT_MESSAGE_DDL = """
            CREATE TABLE chat_message(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              role TEXT NOT NULL,
              content TEXT NOT NULL,
              reasoning TEXT,
              trace TEXT,
              tool_rounds TEXT,
              kind TEXT,
              meta TEXT,
              created_at INTEGER NOT NULL)""";

    /** 与 schema.sql 的 chat_turn 同构（Turn 正本：显式配对 + 执行信封挂点 + 任务段归属） */
    private static final String CHAT_TURN_DDL = """
            CREATE TABLE chat_turn(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              trigger_source TEXT NOT NULL,
              trigger_message_id INTEGER,
              reply_message_id INTEGER,
              task_id INTEGER,
              scene_id INTEGER,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)""";

    @Test
    void pairsUserRowWithFollowingReplyIntoOneTurn() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        long userId = store.insert("s1", "user", "帮我查一下 X", null, null, null, null, null);
        store.insert("s1", "assistant", "查到了", "想了一下", null, null, null, null);

        List<Turn> turns = history(store, jdbc).turns("s1");

        assertEquals(1, turns.size());
        Turn turn = turns.get(0);
        assertEquals(TurnTrigger.Source.USER_INPUT, turn.trigger().source());
        assertEquals("帮我查一下 X", turn.trigger().text());
        assertEquals(userId, turn.trigger().messageId());
        assertTrue(turn.reply() != null);
        assertFalse(turn.inFlight());
        assertEquals("查到了", turn.reply().content());
        assertEquals("想了一下", turn.reply().reasoning());
    }

    @Test
    void conclusionWithoutUserRowIsItsOwnTurnWithAttribution() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        store.insert("s1", "assistant", "〔子代理的结论正文〕", null, null, null,
                ChatMessageStore.KIND_SUBAGENT,
                "{\"title\":\"查 X\",\"summary\":\"ok\",\"status\":\"done\",\"execId\":7}");

        List<Turn> turns = history(store, jdbc).turns("s1");

        assertEquals(1, turns.size());
        assertEquals(TurnTrigger.Source.SUBAGENT_REPORT, turns.get(0).trigger().source());
        assertEquals("查 X", turns.get(0).trigger().text());
        /* 回放正文带上出处（标题 + 执行 id），归因跨轮不丢 */
        assertEquals("〔子代理「查 X」的回报 · 执行 id=7〕\n〔子代理的结论正文〕",
                turns.get(0).reply().content());
    }

    @Test
    void replayEmitsTurnsInOrderOnPlainProtocol() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        store.insert("s1", "user", "问题一", null, null, null, null, null);
        store.insert("s1", "assistant", "回答一", null, null, null, null, null);
        store.insert("s1", "user", "问题二", null, null, null, null, null);
        store.insert("s1", "assistant", "回答二", null, null, null, null, null);

        List<JsonNode> rows = history(store, jdbc)
                .messages(new ContextRequest("m", "sys", "s1", false, "", BIG_BUDGET, null));

        assertEquals(4, rows.size());
        assertEquals("user", rows.get(0).path("role").asText());
        assertEquals("问题一", rows.get(0).path("content").asText());
        assertEquals("assistant", rows.get(1).path("role").asText());
        assertEquals("回答一", rows.get(1).path("content").asText());
        assertEquals("问题二", rows.get(2).path("content").asText());
        assertEquals("回答二", rows.get(3).path("content").asText());
        /* 默认协议不注入思考与工具往返 */
        assertTrue(rows.get(1).get("reasoning_content") == null);
    }

    @Test
    void replayWithContextTraceExpandsToolRoundsAndReasoning() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        String rounds = "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
                + "[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"结果\"}]";
        store.insert("s1", "user", "查一下", null, null, null, null, null);
        store.insert("s1", "assistant", "最终答复", "想了一下", null, rounds, null, null);

        List<JsonNode> rows = history(store, jdbc)
                .messages(new ContextRequest("m", "sys", "s1", true, "", BIG_BUDGET, null));

        /* 工具往返原样重放（带 tool_calls 的 assistant 轮次补上 reasoning_content 空串），末尾补回答与思考 */
        assertEquals(4, rows.size());
        assertEquals("查一下", rows.get(0).path("content").asText());
        assertEquals("c1", rows.get(1).path("tool_calls").get(0).path("id").asText());
        assertEquals("", rows.get(1).path("reasoning_content").asText());
        assertEquals("结果", rows.get(2).path("content").asText());
        assertEquals("最终答复", rows.get(3).path("content").asText());
        assertEquals("想了一下", rows.get(3).path("reasoning_content").asText());
    }

    @Test
    void inFlightGenerationBecomesTailTurnAndReplayKeepsOnlyItsQuestion() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        long userId = store.insert("s1", "user", "在生成的问题", null, null, null, null, null);
        ChatLiveStore live = new ChatLiveStore();
        live.start("s1", userId, "在生成的问题");
        live.delta("s1", null, "半截");
        live.delta("s1", null, "回答");
        History history = new History(store, new TurnStore(jdbc), live, OM, new ContextProps());

        List<Turn> turns = history.turns("s1");
        assertEquals(1, turns.size());
        assertTrue(turns.get(0).inFlight());
        assertEquals("在生成的问题", turns.get(0).trigger().text());
        assertEquals("半截回答", turns.get(0).reply().content());
        assertTrue(turns.get(0).reply().messageId() == null);

        /* 回放只带上问题（模型正在回答它），不带半截回答 */
        List<JsonNode> rows = history
                .messages(new ContextRequest("m", "sys", "s1", false, "", BIG_BUDGET, null));
        assertEquals(1, rows.size());
        assertEquals("user", rows.get(0).path("role").asText());
        assertEquals("在生成的问题", rows.get(0).path("content").asText());
    }

    @Test
    void answeredTurnIsNotTouchedByLiveTail() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        store.insert("s1", "user", "问", null, null, null, null, null);
        store.insert("s1", "assistant", "答", null, null, null, null, null);
        History history = history(store, jdbc);

        List<Turn> turns = history.turns("s1");

        assertEquals(1, turns.size());
        assertFalse(turns.get(0).inFlight());
        assertEquals("答", turns.get(0).reply().content());
    }

    /** 无在途生成的历史读侧（裁剪只有 messages(req) 会做，窗口由各用例给） */
    private static History history(ChatMessageStore store, JdbcTemplate jdbc) {
        return new History(store, new TurnStore(jdbc), new ChatLiveStore(), OM, new ContextProps());
    }

    @Test
    void trimsOldestTurnsOnlyWhenTheBudgetIsExceeded() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        for (int i = 0; i < 6; i++) {
            store.insert("s1", "user", "问题" + i + "x".repeat(400), null, null, null, null, null);
            store.insert("s1", "assistant", "回答" + i + "y".repeat(400), null, null, null, null, null);
        }
        History history = history(store, jdbc);

        /* 预算够大：一行不动——普通会话与从前逐字一致 */
        List<JsonNode> roomy = history.messages(
                new ContextRequest("m", "sys", "s1", false, "", BIG_BUDGET, null));
        assertEquals(12, roomy.size());
        assertEquals("问题0" + "x".repeat(400), roomy.get(0).path("content").asText());

        /* 预算很小：从最早的轮次整条丢，至少保留最近 compressKeepTurns=2 轮；
           丢完仍从一条 user 开始——按轮丢，工具往返不会被拆散 */
        List<JsonNode> cramped = history.messages(
                new ContextRequest("m", "sys", "s1", false, "", 100, null));
        assertEquals(4, cramped.size());
        assertEquals("user", cramped.get(0).path("role").asText());
        assertTrue(cramped.get(0).path("content").asText().startsWith("问题4"));
        assertTrue(cramped.get(3).path("content").asText().startsWith("回答5"));
    }

    @Test
    void clipsOnlyAfterDroppingIsNotEnough() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        String longResult = "结" + "果".repeat(1500); // 超过 CONTEXT_TOOL_LIMIT(1200)
        String rounds = "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
                + "[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"" + longResult + "\"}]";
        store.insert("s1", "user", "查一下", null, null, null, null, null);
        store.insert("s1", "assistant", "答", null, null, rounds, null, null);
        History history = history(store, jdbc);

        /* 预算够大：原样回放，不做任何截断——「不到临界不降级」 */
        List<JsonNode> roomy = history.messages(
                new ContextRequest("m", "sys", "s1", true, "", BIG_BUDGET, null));
        assertTrue(roomy.get(2).path("content").asText().length() > 1200);

        /* 预算极小：只有一轮、轮次丢不动 → 才轮到截断（这是最后一级降级） */
        List<JsonNode> cramped = history.messages(
                new ContextRequest("m", "sys", "s1", true, "", 50, null));
        assertTrue(cramped.get(2).path("content").asText().length() <= 1201);
    }

    @Test
    void turnRowsWinUnclaimedRowsStillPairAndOrphansAreSkipped() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        long u1 = store.insert("s1", "user", "问一", null, null, null, null, null);
        long a1 = store.insert("s1", "assistant", "答一", null, null, null, null, null);
        store.insert("s1", "user", "问二", null, null, null, null, null);
        store.insert("s1", "assistant", "答二", null, null, null, null, null);
        TurnStore turnStore = new TurnStore(jdbc);
        /* 显式 Turn：问一 → 答一，并挂上会话任务（执行信封） */
        turnStore.complete(turnStore.begin("s1", TurnTrigger.Source.USER_INPUT, u1, 7L), a1);
        /* 问二 / 答二 没有 Turn 行（模拟某条写入者漏记）→ 读侧兜底配对，不重复计数 */
        /* 悬空 Turn：两端消息都不存在（回退过）→ 跳过，不该凭空多一条 */
        turnStore.begin("s1", TurnTrigger.Source.USER_INPUT, 999L, null);

        List<Turn> turns = new History(store, turnStore, new ChatLiveStore(), OM, new ContextProps())
                .turns("s1");

        assertEquals(2, turns.size());
        assertEquals("问一", turns.get(0).trigger().text());
        assertEquals(u1, turns.get(0).trigger().messageId());
        assertEquals("答一", turns.get(0).reply().content());
        assertEquals("问二", turns.get(1).trigger().text());
        assertEquals("答二", turns.get(1).reply().content());
    }

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(CHAT_MESSAGE_DDL);
        jdbc.execute(CHAT_TURN_DDL);
        return jdbc;
    }
}
