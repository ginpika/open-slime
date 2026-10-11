package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 待办清单（STEP 4 任务分解）的最小回归：内存 SQLite，不起 Spring 上下文。
 * 钉住三件事：覆盖式写入只留当前一份、按会话隔离、按 owner（成员）隔离；状态越界被收敛、渲染成可读文本。
 */
class TodoStoreTest {

    /** 与 schema.sql 的 chat_todo 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE chat_todo(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              owner TEXT,
              seq INTEGER NOT NULL,
              content TEXT NOT NULL,
              status TEXT NOT NULL,
              note TEXT,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)""";

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return jdbc;
    }

    @Test
    void replacesWholeListPerSessionAndOwner() {
        TodoStore todos = new TodoStore(db());
        todos.replace("hub-1", "peer-a", List.of(
                new TodoStore.TodoItem(0, "查 X", TodoStore.ST_PENDING, null),
                new TodoStore.TodoItem(1, "写 Y", TodoStore.ST_PENDING, null)));
        assertEquals(2, todos.list("hub-1", "peer-a").size());

        /* 覆盖式：第二次写入就是当前完整清单，旧的不会残留 */
        todos.replace("hub-1", "peer-a", List.of(new TodoStore.TodoItem(0, "写 Y", TodoStore.ST_DONE, "已完成")));
        List<TodoStore.TodoItem> after = todos.list("hub-1", "peer-a");
        assertEquals(1, after.size());
        assertEquals("写 Y", after.get(0).content());
        assertEquals(TodoStore.ST_DONE, after.get(0).status());

        /* 按会话隔离：别的会话读不到它 */
        assertTrue(todos.list("hub-2", "peer-a").isEmpty());
        /* 按 owner 隔离：同会话里别的成员读不到它（个人计划互不串写） */
        assertTrue(todos.list("hub-1", "peer-b").isEmpty());
    }

    @Test
    void normalizesStatusAndRendersReadableText() {
        TodoStore todos = new TodoStore(db());
        todos.replace("hub-1", "peer-a", List.of(
                new TodoStore.TodoItem(0, "查 X", TodoStore.ST_IN_PROGRESS, null),
                new TodoStore.TodoItem(1, "写 Y", TodoStore.ST_DONE, "已完成"),
                new TodoStore.TodoItem(2, "跑测试", "不存在的状态", null)));

        String text = TodoStore.render(todos.list("hub-1", "peer-a"));
        assertTrue(text.contains("[进行中] 查 X"), text);
        assertTrue(text.contains("[已完成] 写 Y　— 已完成"), text);
        /* 越界状态收敛为未开始（不因模型造词而丢这一步） */
        assertTrue(text.contains("[未开始] 跑测试"), text);
    }
}
