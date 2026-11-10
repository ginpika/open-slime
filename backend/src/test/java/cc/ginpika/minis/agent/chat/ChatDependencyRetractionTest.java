package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.project.ProjectProgressStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * R3 · 派生内容遵守来源的撤回：主答复、子报告与批次归纳记录持久依赖，
 * 任一必需来源被撤回或退出授权后，派生内容退出模型请求与回读，但保留在用户转录中。
 */
class ChatDependencyRetractionTest {

    private static final String AGENT = AgentDirectory.BUILTIN_ID;
    private static final String SESSION = "chat";

    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final ChatMessageStore messages = new ChatMessageStore(jdbc);
    private final TurnStore turns = new TurnStore(jdbc);
    private final TaskStore taskStore = new TaskStore(jdbc);
    private final ChatTaskRunner chatTasks = new ChatTaskRunner(taskStore);
    private final ChatContextSources sources = new ChatContextSources(jdbc, om);
    private final ProjectProgressStore progress = new ProjectProgressStore(jdbc);

    private long user1;
    private long childExec;
    private long report;
    private long user2;
    private long reply2;

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', 1)", SESSION);
        // 轮 1：委派子代理
        user1 = messages.insert(SESSION, "user", "请排查日志", null, null, null, null, null);
        long parent = chatTasks.begin(SESSION, AGENT, "parent", "请排查日志");
        long parentTurn = turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, user1, parent);
        String payload = """
                {"_minis_context":{"parent_task_id":%d,"turn_id":%d,"project_id":null,"refs":["message:%d"]}}
                """.formatted(parent, parentTurn, user1);
        childExec = taskStore.beginExec(ExecStore.KIND_AGENT, null, null, parent, SESSION,
                AGENT, "排查", payload, null);
        report = messages.insert(SESSION, "assistant", "完整报告正文", null, null, null,
                ChatMessageStore.KIND_SUBAGENT, """
                {"contextScoped":true,"execId":%d,"summary":"短结论"}
                """.formatted(childExec));
        // 轮 2：主答复引用了子代理执行与报告
        user2 = messages.insert(SESSION, "user", "总结一下", null, null, null, null, null);
        long task2 = chatTasks.begin(SESSION, AGENT, "reply", "总结一下");
        long turn2 = turns.begin(SESSION, TurnTrigger.Source.USER_INPUT, user2, task2);
        reply2 = messages.insert(SESSION, "assistant", "根据子代理结论……", null, null, null, null, null);
        turns.complete(turn2, reply2);
        sources.recordDependencies(SESSION, AGENT, null, reply2,
                Set.of("subagent:" + childExec, "message:" + report));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void derivedReplyFollowsItsDependencies() {
        Set<Long> visible = sources.visibleMessageIds(SESSION, AGENT, null);
        assertTrue(visible.containsAll(List.of(user1, report, user2, reply2)));

        // 撤回子代理执行：报告失效（既有规则），主答复随依赖失效
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                "task:" + childExec);
        visible = sources.visibleMessageIds(SESSION, AGENT, null);
        assertFalse(visible.contains(report));
        assertFalse(visible.contains(reply2));
        assertTrue(visible.contains(user2));
        assertEquals(null, sources.readReference(SESSION, AGENT, null, "message:" + reply2, 100));
        // 用户转录不受影响
        assertTrue(messages.listBySession(SESSION).stream().anyMatch(r -> r.id() == reply2));
    }

    @Test
    void artifactDependencyRevokesDerivedReply() {
        String artifact = sources.archiveToolResult(SESSION, AGENT, null, childExec, "run_command", "根因证据");
        assertTrue(artifact != null);
        sources.recordDependencies(SESSION, AGENT, null, reply2, Set.of(artifact));
        assertTrue(sources.visibleMessageIds(SESSION, AGENT, null).contains(reply2));

        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                "task_artifact:" + artifact.substring("artifact:".length()));
        assertFalse(sources.visibleMessageIds(SESSION, AGENT, null).contains(reply2));
    }

    @Test
    void retractedSourceStaysOutOfProjectProgress() {
        // 项目续接是模型材料：被撤回的助手正文不得进入摘录
        jdbc.update("INSERT INTO memory_retracted_source(ref, retracted_at) VALUES(?, 1)",
                "chat_message:" + reply2);
        var read = progress.read(SESSION);
        assertTrue(read == null || read.messageIds().stream().noneMatch(id -> id == reply2));
    }

    @Test
    void branchCopiesCarryRewrittenDependencies() {
        // 分支复制：主答复的 message 依赖改指报告副本；报告副本因执行不在本会话作用域而不可读，
        // 于是主答复副本同样退出分支的模型范围。
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES('branch', 'ai', 1)");
        assertEquals(4, messages.copyUpTo(SESSION, "branch", reply2));
        var rows = messages.listBySession("branch");
        long branchReport = rows.stream().filter(r -> r.content().equals("完整报告正文"))
                .mapToLong(r -> r.id()).findFirst().orElse(-1);
        long branchReply = rows.stream().filter(r -> r.content().equals("根据子代理结论……"))
                .mapToLong(r -> r.id()).findFirst().orElse(-1);
        assertTrue(branchReport > 0 && branchReply > 0);
        List<String> deps = jdbc.queryForList(
                "SELECT ref FROM chat_message_dependency WHERE message_id = ? ORDER BY ref", String.class, branchReply);
        assertTrue(deps.contains("message:" + branchReport));
        assertFalse(sources.visibleMessageIds("branch", AGENT, null).contains(branchReply));
        // 用户转录保留完整副本
        assertEquals(4, rows.size());
    }
}
