package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 任务地基（STEP 1 最小切片）的回归冒烟：内存 SQLite，不起 Spring 上下文。
 *
 * <p>覆盖三件事：任务状态机落库并与执行留痕（exec_id）绑定；启动恢复只动在途任务；
 * 恢复同时把残留的在途执行留痕收成 interrupted 并补上 finished_at。
 */
class TaskStoreTest {

    /** 与 schema.sql 的 task 同构（去注释） */
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

    /** 与 schema.sql 的 task_artifact 同构（去注释） */
    private static final String ARTIFACT_DDL = """
            CREATE TABLE task_artifact(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id INTEGER NOT NULL REFERENCES task(id),
              exec_id INTEGER,
              kind TEXT NOT NULL,
              name TEXT,
              content TEXT,
              ref TEXT,
              created_at INTEGER NOT NULL)""";

    /** heartbeat_run 的恢复用最小列（与 schema.sql 同构，仅有被读写的那些） */
    private static final String RUN_DDL = """
            CREATE TABLE heartbeat_run(
              id TEXT PRIMARY KEY,
              beat INTEGER NOT NULL,
              trigger TEXT NOT NULL,
              status TEXT NOT NULL,
              agent_count INTEGER NOT NULL DEFAULT 0,
              note TEXT,
              started_at INTEGER NOT NULL,
              finished_at INTEGER)""";

    private static JdbcTemplate db() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(TASK_DDL);
        jdbc.execute(ARTIFACT_DDL);
        jdbc.execute(RUN_DDL);
        return jdbc;
    }

    @Test
    void recordsTaskArtifacts() {
        TaskStore tasks = new TaskStore(db());
        long taskId = tasks.beginExec(ExecStore.KIND_AGENT, "run-1", null, null, "hub-1", "builtin",
                "子代理", "{}", "[]");
        tasks.addArtifact(taskId, taskId, TaskStore.ART_TEXT, "查一下 X", "结论正文", null);

        List<TaskStore.ArtifactRow> rows = tasks.artifactsOf(taskId);
        assertEquals(1, rows.size());
        assertEquals(TaskStore.ART_TEXT, rows.get(0).kind());
        assertEquals("结论正文", rows.get(0).content());
        assertEquals(taskId, rows.get(0).execId());
        /* 产物按任务隔离：别的任务读不到它 */
        assertTrue(tasks.artifactsOf(taskId + 1).isEmpty());
    }

    @Test
    void beginsARunningRowCarryingItsInput() {
        TaskStore tasks = new TaskStore(db());
        long id = tasks.beginExec(ExecStore.KIND_AGENT, "run-1", "plan-1", null, "hub-1", "builtin",
                "查一下 X", "{\"instruction\":\"查一下 X\"}", "[{\"role\":\"user\"}]");
        assertTrue(id > 0);

        /* 开一次执行就是一步到位：状态 running、归属会话与待重跑输入都在这一行上 */
        TaskStore.TaskRow row = tasks.task(id).orElseThrow();
        assertEquals(TaskStore.ST_RUNNING, row.status());
        assertEquals("hub-1", row.sessionId());
        assertEquals("builtin", row.agentId());
        assertEquals("{\"instruction\":\"查一下 X\"}", row.payload());
    }

    @Test
    void buildsParentChildChain() {
        TaskStore tasks = new TaskStore(db());
        /* 心跳主会话 → 计划 → 子代理：三段 parent_id 串成一条链（「谁派的活」） */
        long heartbeat = tasks.beginExec(ExecStore.KIND_HEARTBEAT, "run-1", null, null, null,
                "builtin", "心跳主会话", null, "[]");
        long plan = tasks.beginExec(ExecStore.KIND_PLAN, "run-1", "plan-1", heartbeat, null,
                "builtin", "记忆体检", null, "[]");
        long agent = tasks.beginExec(ExecStore.KIND_AGENT, "run-1", null, plan, "hub-1",
                "builtin", "查一下 X", "{}", "[]");

        assertNull(tasks.task(heartbeat).orElseThrow().parentId());
        assertEquals(heartbeat, tasks.task(plan).orElseThrow().parentId());
        assertEquals(plan, tasks.task(agent).orElseThrow().parentId());
        /* beginExec 一步到位：置 running */
        assertEquals(TaskStore.ST_RUNNING, tasks.task(agent).orElseThrow().status());

        tasks.finish(agent, true);
        assertEquals(TaskStore.ST_DONE, tasks.task(agent).orElseThrow().status());
        tasks.finish(plan, false);
        assertEquals(TaskStore.ST_FAILED, tasks.task(plan).orElseThrow().status());

        /* 最近任务倒序（id 降序）；kind 过滤只看一类 */
        assertEquals(List.of(agent, plan, heartbeat),
                tasks.recent(null, null, 10).stream().map(TaskStore.TaskRow::id).toList());
        assertEquals(List.of(agent),
                tasks.recent(null, TaskStore.KIND_SUBAGENT, 10).stream()
                        .map(TaskStore.TaskRow::id).toList());
        /* agent 过滤：三条都归 builtin，换个 id 就查不到 */
        assertEquals(3, tasks.recent("builtin", null, 10).size());
        assertTrue(tasks.recent("other", null, 10).isEmpty());
    }

    @Test
    void interruptRunningOnlyTouchesInFlightTasks() {
        TaskStore tasks = new TaskStore(db());
        long running = tasks.insert(TaskStore.KIND_SUBAGENT, null, "在跑", "hub-1", null, "{}");
        tasks.setStatus(running, TaskStore.ST_RUNNING);
        long pending = tasks.insert(TaskStore.KIND_SUBAGENT, null, "刚受理", "hub-1", null, "{}");
        long done = tasks.insert(TaskStore.KIND_SUBAGENT, null, "跑完了", "hub-1", null, "{}");
        tasks.setStatus(done, TaskStore.ST_DONE);

        /* 只有 pending / running 被收成 interrupted：已结束的任务不受启动恢复影响 */
        assertEquals(2, tasks.interruptRunning());
        assertEquals(TaskStore.ST_INTERRUPTED, tasks.task(running).orElseThrow().status());
        assertEquals(TaskStore.ST_INTERRUPTED, tasks.task(pending).orElseThrow().status());
        assertEquals(TaskStore.ST_DONE, tasks.task(done).orElseThrow().status());
    }

    @Test
    void recoveryClosesInFlightBatchesAndExecutions() {
        JdbcTemplate jdbc = db();
        TaskStore tasks = new TaskStore(jdbc);
        jdbc.update("INSERT INTO heartbeat_run(id, beat, trigger, status, started_at) "
                + "VALUES('r1', 1, 'chat', 'running', 100)");
        long exec = tasks.beginExec(ExecStore.KIND_AGENT, "r1", null, null, "hub-1", null, "在跑",
                null, "[]");

        /* 在途的批次由 ExecStore 收；在途的执行就是任务行，由 TaskStore 收 */
        assertEquals(1, new ExecStore(jdbc).interruptRunning());
        assertEquals(1, tasks.interruptRunning());

        assertEquals("interrupted",
                jdbc.queryForObject("SELECT status FROM heartbeat_run WHERE id='r1'", String.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT finished_at FROM heartbeat_run WHERE id='r1'", Long.class));
        assertEquals(TaskStore.ST_INTERRUPTED, tasks.task(exec).orElseThrow().status());
    }
}
