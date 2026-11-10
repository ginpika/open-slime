package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.config.ContextProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * R9 · 启用审批时的待办完成门禁：write_todos 不能把未完成项直接标为完成，
 * 也不能经整表替换、重排或删除再添加绕过；现有已完成项可原样保留，关闭试点后恢复普通写入。
 */
class ChatTodoApprovalGateTest {

    private static final String SESSION = "chat";
    private static final String OWNER = cc.ginpika.minis.agent.member.AgentDirectory.BUILTIN_ID;

    private final SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(ds);
    private final ObjectMapper om = new ObjectMapper();
    private final ContextProps props = new ContextProps();
    private final TodoStore todos = new TodoStore(jdbc);
    private final TodoService service = new TodoService(todos, new HubSessionStore(jdbc), om,
            mockEvents());

    private static SessionEventBus mockEvents() {
        try {
            return new SessionEventBus(org.mockito.Mockito.mock(SessionEventStore.class),
                    new ObjectMapper());
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @BeforeEach
    void setup() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc.update("INSERT INTO hub_session(id, mode, created_at) VALUES(?, 'ai', ?)",
                SESSION, System.currentTimeMillis());
        service.setContextProps(props);
        props.setEphemeralChat(true);
        props.setTodoApprovals(true);
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    @Test
    void markingPendingItemDoneViaWriteTodosIsRejectedUnderApproval() {
        runTool("""
                [{"content":"排查日志","status":"pending"},{"content":"修复配置","status":"pending"}]
                """);
        String result = runTool("""
                [{"content":"排查日志","status":"done"},{"content":"修复配置","status":"pending"}]
                """);
        assertTrue(result.contains("不能由 write_todos 直接完成"));
        assertTrue(result.contains("request_todo_completion"));
        assertEquals("pending", statusOf("排查日志"));
    }

    @Test
    void reorderingAndDeletingPendingItemsStayAllowed() {
        runTool("""
                [{"content":"第一步","status":"pending"},{"content":"第二步","status":"pending"}]
                """);
        // 重排 + 删除一项：不涉及完成转换，允许
        String result = runTool("""
                [{"content":"第二步","status":"pending"}]
                """);
        assertTrue(result.startsWith("清单已更新"));
        assertEquals(1, todos.list(SESSION, OWNER).size());
        // 直接新建已完成项同样被拒
        String created = runTool("""
                [{"content":"新步骤","status":"done"}]
                """);
        assertTrue(created.contains("不能由 write_todos 直接完成"));
    }

    @Test
    void existingDoneItemsRemainDoneAndOffSwitchRestoresPlainWrites() {
        props.setTodoApprovals(false);
        runTool("""
                [{"content":"旧步骤","status":"done"},{"content":"新步骤","status":"pending"}]
                """);
        // 关闭后再开：已有已完成项在新清单中原样保留 → 允许
        props.setTodoApprovals(true);
        String kept = runTool("""
                [{"content":"新步骤","status":"pending"},{"content":"旧步骤","status":"done"}]
                """);
        assertTrue(kept.startsWith("清单已更新"));
        assertEquals("done", statusOf("旧步骤"));

        // 彻底关闭试点：恢复普通清单写入
        props.setTodoApprovals(false);
        String plain = runTool("""
                [{"content":"新步骤","status":"done"}]
                """);
        assertTrue(plain.startsWith("清单已更新"));
        assertEquals("done", statusOf("新步骤"));
    }

    private String runTool(String todosJson) {
        var call = om.createObjectNode();
        call.putObject("function").put("name", "write_todos")
                .put("arguments", "{\"todos\":" + todosJson.strip().replace("\n", "") + "}");
        return service.runTool(call, SESSION, OWNER);
    }

    private String statusOf(String content) {
        return todos.list(SESSION, OWNER).stream()
                .filter(item -> item.content().equals(content))
                .map(TodoStore.TodoItem::status)
                .findFirst().orElse(null);
    }
}
