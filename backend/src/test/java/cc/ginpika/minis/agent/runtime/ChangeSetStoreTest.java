package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 变更集的最小回归（内存 SQLite，不起 Spring 上下文）：
 * 一次执行一个变更集、多次写入追加成项、按 exec 找得到、没有写操作就不建。
 */
class ChangeSetStoreTest {

    private static final String TASK_DDL = """
            CREATE TABLE task(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              parent_id INTEGER REFERENCES task(id),
              kind TEXT NOT NULL,
              status TEXT NOT NULL,
              title TEXT,
              session_id TEXT,
              agent_id TEXT,
              model_id TEXT,
              workspace_id TEXT,
              budget TEXT,
              exec_id INTEGER,
              payload TEXT,
              run_id TEXT,
              plan_id TEXT,
              origin TEXT,
              context TEXT,
              reasoning TEXT,
              trace TEXT,
              tool_rounds TEXT,
              error TEXT,
              started_at INTEGER,
              finished_at INTEGER,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)""";

    private static final String CHANGESET_DDL = """
            CREATE TABLE changeset(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id INTEGER REFERENCES task(id),
              exec_id INTEGER,
              status TEXT NOT NULL DEFAULT 'applied',
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)""";

    private static final String ITEM_DDL = """
            CREATE TABLE changeset_item(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              changeset_id INTEGER NOT NULL REFERENCES changeset(id),
              tool TEXT NOT NULL,
              target TEXT,
              before TEXT,
              after TEXT,
              summary TEXT,
              created_at INTEGER NOT NULL)""";

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(TASK_DDL);
        jdbc.execute(CHANGESET_DDL);
        jdbc.execute(ITEM_DDL);
        return jdbc;
    }

    @Test
    void oneChangeSetPerExecCollectsItsWrites() {
        JdbcTemplate jdbc = db();
        long execId = new TaskStore(jdbc).beginExec(ExecStore.KIND_AGENT, "run-1", null, null,
                "hub-1", null, "子代理", "{}", "[]");
        ChangeSetStore changes = new ChangeSetStore(jdbc);

        changes.record(execId, "upsert_node", "{\"name\":\"A\"}", "已写入 A");
        changes.record(execId, "upsert_edge", "{\"subject\":\"A\"}", "已写入边");

        /* 同一执行的两次写入落进同一个变更集，按落库顺序可读 */
        long csId = changes.changesetIdOfExec(execId).orElseThrow();
        assertEquals(2, changes.items(csId).size());
        List<ChangeSetStore.ItemRow> items = changes.items(csId);
        assertEquals("upsert_node", items.get(0).tool());
        assertEquals("{\"name\":\"A\"}", items.get(0).after());
        assertEquals("已写入边", items.get(1).summary());
        /* 变更集就归属这次执行（执行就是任务行，所以 execId 即 taskId） */
        assertEquals(execId, changes.changeSet(csId).orElseThrow().taskId());
        assertEquals(ChangeSetStore.STATUS_APPLIED, changes.changeSet(csId).orElseThrow().status());
    }

    @Test
    void execsAreIsolatedAndReadOnlyExecsHaveNoChangeSet() {
        JdbcTemplate jdbc = db();
        ChangeSetStore changes = new ChangeSetStore(jdbc);
        changes.record(8L, "delete_graph_item", "{}", "已删除");
        changes.record(9L, "create_document", "{\"title\":\"X\"}", "已新建");

        assertNotEquals(changes.changesetIdOfExec(8L).orElseThrow(),
                changes.changesetIdOfExec(9L).orElseThrow());
        /* 没有发生写操作的执行（这次没调写工具）不该凭空多出一个变更集 */
        assertTrue(changes.changesetIdOfExec(10L).isEmpty());
    }
}
