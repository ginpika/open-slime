package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * R2 · 复制转录时保留来源归属：分支副本携带 origin 映射，原会话改绑、回退或撤回源消息后，
 * 副本退出模型可读范围，但保留在用户转录中。
 */
class ChatBranchOriginTest {

    private static final String AGENT = AgentDirectory.BUILTIN_ID;
    private static final String SOURCE = "source-session";
    private static final String BRANCH = "branch-session";

    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final ChatMessageStore messages = new ChatMessageStore(jdbc);
    private final TurnStore turns = new TurnStore(jdbc);
    private final TaskStore tasks = new TaskStore(jdbc);
    private final ChatTaskRunner chatTasks = new ChatTaskRunner(tasks);
    private final HubSessionStore sessions = new HubSessionStore(jdbc);
    private final ChatContextSources sources = new ChatContextSources(jdbc, om);

    private long sourceUser;
    private long sourceReply;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', 1)", SOURCE);
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', 1)", BRANCH);
        sourceUser = messages.insert(SOURCE, "user", "跑团提问", null, null, null, null, null);
        long task = chatTasks.begin(SOURCE, AGENT, "test", "跑团提问");
        long turn = turns.begin(SOURCE, TurnTrigger.Source.USER_INPUT, sourceUser, task);
        sourceReply = messages.insert(SOURCE, "assistant", "跑团答复", null, null, null, null, null);
        turns.complete(turn, sourceReply);
        assertEquals(2, messages.copyUpTo(SOURCE, BRANCH, sourceReply));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void branchCopiesStayReadableWhileOriginalBindingHolds() {
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).size() >= 2);
        long branchUser = copiedId("user");
        assertEquals("跑团提问", sources.readReference(BRANCH, AGENT, null, "message:" + branchUser, 100));
    }

    @Test
    void originalRebindInvalidatesCopiesEvenWhenReboundBack() {
        jdbc.update("INSERT INTO agent(id, name, created_at, updated_at) VALUES('peer', 'Peer', 1, 1)");
        // 改绑 A → B：原会话代号推进，副本退出模型范围
        sessions.updateConfig(SOURCE, null, null, "peer", null, null, null, null);
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).isEmpty());
        assertTranscriptIntact();

        // 改回 B → A：代号再次推进，旧授权不恢复
        sessions.updateConfig(SOURCE, null, null, "", null, null, null, null);
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).isEmpty());

        long branchUser = copiedId("user");
        assertEquals(null, sources.readReference(BRANCH, AGENT, null, "message:" + branchUser, 100));
    }

    @Test
    void originalRewindAndRevokeRemoveCopiesFromModelView() {
        // 原会话回退：正本删除且代号推进
        messages.deleteFrom(SOURCE, sourceUser);
        sessions.invalidateContextStage(SOURCE);
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).isEmpty());
        assertTranscriptIntact();
    }

    @Test
    void retractingOriginalMessageAloneRevokesTheCopy() {
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                "chat_message:" + sourceUser);
        assertFalse(sources.visibleMessageIds(BRANCH, AGENT, null).contains(copiedId("user")));
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).contains(copiedId("assistant")));
    }

    @Test
    void branchOwnRewindOnlyAffectsTheBranch() {
        // 分支自身回退：删掉分支里的提问之后，剩余副本仍可读
        long branchUser = copiedId("user");
        jdbc.update("DELETE FROM chat_message WHERE session_id = ? AND id = ?", BRANCH, branchUser);
        assertTrue(sources.visibleMessageIds(BRANCH, AGENT, null).contains(copiedId("assistant")));
        // 原会话仍完整可读
        assertTrue(sources.visibleMessageIds(SOURCE, AGENT, null).contains(sourceUser));
    }

    private long copiedId(String role) {
        return jdbc.queryForObject("SELECT id FROM chat_message WHERE session_id = ? AND role = ? ORDER BY id LIMIT 1",
                Long.class, BRANCH, role);
    }

    private void assertTranscriptIntact() {
        var rows = messages.listBySession(BRANCH);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "跑团提问".equals(r.content())));
        assertTrue(rows.stream().anyMatch(r -> "跑团答复".equals(r.content())));
    }
}
