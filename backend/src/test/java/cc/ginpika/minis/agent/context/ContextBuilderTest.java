package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.ChatLiveStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.turn.History;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.config.ContextProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 上下文平面（STEP 2 等效抽取）的回归：组装骨架与历史回放的输出形状。
 *
 * <p>抽取的硬指标是「行为零变化」——这里把 {@link ContextBuilder} 的拼装契约（system 基底 + 各源尾段
 * 以固定分隔线相连、各源消息按序插入、tools 原样挂上）与 {@link History} 的回放形状
 * （普通行、子代理归因、context_trace 原生协议回放）都钉住，避免日后插新 provider 时改动拼装规则。
 */
class ContextBuilderTest {

    private static final ObjectMapper OM = new ObjectMapper();

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

    /** 与 schema.sql 的 chat_turn 同构（历史读侧会查它：显式配对 + 任务段归属） */
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

    /** 最小 provider：只声明 system 尾段 */
    private static ContextProvider suffix(String text) {
        return new ContextProvider() {
            @Override
            public String systemSuffix(ContextRequest req) {
                return text;
            }
        };
    }

    /** 最小 provider：只声明消息 */
    private static ContextProvider messages(JsonNode... rows) {
        return new ContextProvider() {
            @Override
            public List<JsonNode> messages(ContextRequest req) {
                return List.of(rows);
            }
        };
    }

    @Test
    void assemblesSystemSuffixMessagesAndToolsInOrder() {
        ObjectNode msg = OM.createObjectNode().put("role", "user").put("content", "你好");
        ArrayNode tools = OM.createArrayNode().add(OM.createObjectNode().put("name", "search"));
        ContextBuilder builder = new ContextBuilder(OM,
                List.of(suffix("在跑的活"), messages(msg)));

        ObjectNode body = builder.build(new ContextRequest(
                "deepseek-v4", "身份 + 增强", "s1", false, null, 0, tools));

        assertEquals("deepseek-v4", body.path("model").asText());
        ArrayNode out = (ArrayNode) body.get("messages");
        /* system = 基底 + 分隔线 + 尾段；各源消息紧随其后 */
        assertEquals("身份 + 增强\n\n---\n\n在跑的活", out.get(0).path("content").asText());
        assertEquals("system", out.get(0).path("role").asText());
        assertEquals("你好", out.get(1).path("content").asText());
        assertEquals("search", body.path("tools").get(0).path("name").asText());
    }

    @Test
    void blankSuffixKeepsSystemBaseUntouched() {
        ContextBuilder builder = new ContextBuilder(OM, List.of(suffix("")));

        ObjectNode body = builder.build(new ContextRequest("m", "身份", "s1", false, null, 0, null));

        ArrayNode out = (ArrayNode) body.get("messages");
        assertEquals("身份", out.get(0).path("content").asText());
        /* tools 为空时不挂该字段（与抽取前一致） */
        assertTrue(body.get("tools") == null);
    }

    @Test
    void historyReplaysRowsAndAttributesSubagentConclusion() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        store.insert("s1", "user", "帮我查一下 X", null, null, null, null, null);
        store.insert("s1", "assistant", "〔子代理的结论正文〕", null, null, null,
                ChatMessageStore.KIND_SUBAGENT,
                "{\"title\":\"查 X\",\"summary\":\"ok\",\"status\":\"done\",\"execId\":7}");
        History provider = new History(store, new TurnStore(jdbc), new ChatLiveStore(), OM,
                new ContextProps());

        List<JsonNode> rows = provider.messages(
                new ContextRequest("m", "sys", "s1", false, null, 0, null));

        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).path("role").asText());
        assertEquals("帮我查一下 X", rows.get(0).path("content").asText());
        /* 子代理结论带上出处（标题 + 执行 id），归因跨轮不丢 */
        assertEquals("〔子代理「查 X」的回报 · 执行 id=7〕\n〔子代理的结论正文〕",
                rows.get(1).path("content").asText());
    }

    @Test
    void contextTraceReplaysToolRoundsOnNativeProtocol() {
        JdbcTemplate jdbc = db();
        ChatMessageStore store = new ChatMessageStore(jdbc);
        String rounds = "[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":"
                + "[{\"id\":\"c1\",\"type\":\"function\",\"function\":"
                + "{\"name\":\"search\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"结果\"}]";
        store.insert("s1", "assistant", "最终答复", "想了一下", null, rounds, null, null);
        History provider = new History(store, new TurnStore(jdbc), new ChatLiveStore(), OM,
                new ContextProps());

        List<JsonNode> rows = provider.messages(
                new ContextRequest("m", "sys", "s1", true, null, 0, null));

        /* 工具往返原样重放（带 tool_calls 的 assistant 轮次补上 reasoning_content 空串），
           末尾补上最终回答及其思考 */
        assertEquals(3, rows.size());
        assertEquals("c1", rows.get(0).path("tool_calls").get(0).path("id").asText());
        assertEquals("", rows.get(0).path("reasoning_content").asText());
        assertEquals("结果", rows.get(1).path("content").asText());
        assertEquals("最终答复", rows.get(2).path("content").asText());
        assertEquals("想了一下", rows.get(2).path("reasoning_content").asText());
    }

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(CHAT_MESSAGE_DDL);
        jdbc.execute(CHAT_TURN_DDL);
        return jdbc;
    }
}
