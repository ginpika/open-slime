package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ChatContextSourcesTest {

    private static final String AGENT = AgentDirectory.BUILTIN_ID;
    private static final String SESSION = "chat";

    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final ChatMessageStore messages = new ChatMessageStore(jdbc);
    private final TurnStore turns = new TurnStore(jdbc);
    private final ChatTaskRunner tasks = new ChatTaskRunner(new TaskStore(jdbc));
    private final ChatContextSources sources = new ChatContextSources(jdbc, om);

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        session(SESSION, "ai");
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void readsLegacyTextByCharacterAndFindsNativeToolEvidence() throws Exception {
        long user = messages.insert(SESSION, "user", "甲🎲乙", null, null, null, null, null);
        long reply = messages.insert(SESSION, "assistant", "旧结论", "hidden reasoning", null, """
                [
                  {"role":"assistant","tool_calls":[{"id":"a","function":{"name":"run_command"}}]},
                  {"role":"tool","tool_call_id":"a","content":"日志\\nroot cause"},
                  {"role":"assistant","tool_calls":[{"id":"b","function":{"name":"read_context_source"}}]},
                  {"role":"tool","tool_call_id":"b","content":"stale copy"}
                ]
                """, null, null);
        JsonNode first = run(SESSION, AGENT, null, """
                {"action":"read","source":"message:%d","offset":1,"length":1}
                """.formatted(user));
        assertEquals("🎲", first.path("content").asText());
        assertEquals(2, first.path("next_offset").asInt());
        assertEquals(3, first.path("total_characters").asInt());

        JsonNode related = run(SESSION, AGENT, null, """
                {"action":"list","source":"message:%d"}
                """.formatted(reply)).path("sources");
        assertEquals(1, related.size());
        String tool = "message:" + reply + ":tool:1";
        assertEquals(tool, related.get(0).path("source").asText());
        assertEquals("日志\nroot cause", read(SESSION, AGENT, null, tool).path("content").asText());
        assertEquals("unavailable", read(SESSION, AGENT, null, "message:" + reply + ":tool:3").path("status").asText());
    }

    @Test
    void archiveIsReadableDuringToolLoopAndRevokedWithItsTurn() throws Exception {
        long user = messages.insert(SESSION, "user", "continue", null, null, null, null, null);
        long task = tasks.begin(SESSION, AGENT, "test", "continue");
        turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, user, task);

        String ref = sources.archiveToolResult(SESSION, AGENT, null, task, "run_command", "line 1\nline 2");
        assertNotNull(ref);
        assertNull(sources.archiveToolResult(SESSION, AGENT, null, task, ChatContextSources.TOOL_NAME, "private copy"));
        assertEquals("line 1\nline 2", read(SESSION, AGENT, null, ref).path("content").asText());

        session("other", "ai");
        assertEquals("unavailable", read("other", AGENT, null, ref).path("status").asText());
        jdbc.update("UPDATE hub_session SET project_id = 'moved' WHERE id = ?", SESSION);
        assertEquals("unavailable", read(SESSION, AGENT, null, ref).path("status").asText());
        assertNull(sources.archiveToolResult(SESSION, AGENT, null, task, "run_command", "after rebind"));

        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)", "task:" + task);
        assertEquals("unavailable", read(SESSION, AGENT, "moved", ref).path("status").asText());
        assertEquals("unavailable", read(SESSION, AGENT, "moved", "message:" + user).path("status").asText());
        assertTrue(run(SESSION, AGENT, "moved", "{\"action\":\"list\"}").path("sources").isEmpty());
    }

    @Test
    void rechecksAgentBindingAndDeniesRoundtableAndDeletedMessages() throws Exception {
        long user = messages.insert(SESSION, "user", "prompt", null, null, null, null, null);
        long reply = messages.insert(SESSION, "assistant", "answer", null, null, null, null, null);
        long task = tasks.begin(SESSION, AGENT, "test", "prompt");
        turns.complete(turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, user, task), reply);
        String ref = "message:" + reply;

        jdbc.update("INSERT INTO agent(id, name, created_at, updated_at) VALUES('peer', 'Peer', 1, 1)");
        jdbc.update("UPDATE hub_session SET agent_id = 'peer' WHERE id = ?", SESSION);
        assertEquals("unavailable", read(SESSION, AGENT, null, ref).path("status").asText());
        assertEquals("unavailable", read(SESSION, "peer", null, ref).path("status").asText());

        session("table", "multi-agent");
        long tableMessage = messages.insert("table", "user", "roundtable", null, null, null, null, null);
        assertEquals("unavailable", read("table", AGENT, null, "message:" + tableMessage).path("status").asText());
        jdbc.update("UPDATE hub_session SET agent_id = NULL WHERE id = ?", SESSION);
        messages.deleteFrom(SESSION, user);
        assertEquals("unavailable", read(SESSION, AGENT, null, ref).path("status").asText());
    }

    @Test
    void scopedReportsAndDerivedBatchFollowLiveParentAndSourceReferences() throws Exception {
        long input = messages.insert(SESSION, "user", "delegated goal", null, null, null, null, null);
        long parent = tasks.begin(SESSION, AGENT, "parent", "delegated goal");
        long parentTurn = turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, input, parent);
        String payload = """
                {"_minis_context":{"parent_task_id":%d,"turn_id":%d,"project_id":null,"refs":["message:%d"]}}
                """.formatted(parent, parentTurn, input);
        long child = new TaskStore(jdbc).beginExec(ExecStore.KIND_AGENT, null, null, parent, SESSION,
                AGENT, "check source", payload, null);
        long report = messages.insert(SESSION, "assistant", "complete report", null, null, null,
                ChatMessageStore.KIND_SUBAGENT, """
                {"contextScoped":true,"execId":%d,"summary":"short result"}
                """.formatted(child));
        long batch = messages.insert(SESSION, "assistant", "combined", null, null, null,
                ChatMessageStore.KIND_SUBAGENT_BATCH, """
                {"execIds":[%d],"messageIds":[%d]}
                """.formatted(child, report));
        String artifact = sources.archiveToolResult(SESSION, AGENT, null, child, "search_document", "evidence");
        assertNotNull(artifact);
        assertEquals("evidence", read(SESSION, AGENT, null, artifact).path("content").asText());
        assertEquals("complete report", read(SESSION, AGENT, null, "message:" + report).path("content").asText());
        assertEquals("combined", read(SESSION, AGENT, null, "message:" + batch).path("content").asText());

        jdbc.update("UPDATE hub_session SET project_id='moved' WHERE id=?", SESSION);
        assertEquals("unavailable", read(SESSION, AGENT, "moved", "message:" + report).path("status").asText());
        assertEquals("unavailable", read(SESSION, AGENT, "moved", artifact).path("status").asText());
        jdbc.update("UPDATE hub_session SET project_id=NULL WHERE id=?", SESSION);
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)", "chat_message:" + report);
        assertEquals("unavailable", read(SESSION, AGENT, null, "message:" + batch).path("status").asText());
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)", "task:" + parent);
        assertEquals("unavailable", read(SESSION, AGENT, null, artifact).path("status").asText());
        assertTrue(messages.listBySession(SESSION).stream().anyMatch(row -> row.id() == report
                && "complete report".equals(row.content())));
    }

    private void session(String id, String mode) {
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, ?, 1)", id, mode);
    }

    private JsonNode read(String session, String agent, String project, String ref) throws Exception {
        return run(session, agent, project, """
                {"action":"read","source":"%s"}
                """.formatted(ref));
    }

    private JsonNode run(String session, String agent, String project, String args) throws Exception {
        var call = om.createObjectNode();
        call.putObject("function").put("arguments", args);
        return om.readTree(sources.run(session, agent, project, call));
    }
}
