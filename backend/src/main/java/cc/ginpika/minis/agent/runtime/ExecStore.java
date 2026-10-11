package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 「一次执行」的读侧：按<b>执行来源</b>过滤与聚合 {@code task} 表里的那些行。
 *
 * <p>一次执行就是一行 task（{@code origin} 非空：heartbeat / plan / agent 三种来源），
 * 过程列（context / reasoning / trace / tool_rounds）与身份同处一行；前台会话的任务没有
 * {@code origin}，不算执行，各查询用 {@code origin IS NOT NULL} 把它们排除在外。
 *
 * <p>剩下的两种执行共用这一层，靠 {@link #KIND_HEARTBEAT} 等来源区分：
 * <ul>
 *   <li>{@link #KIND_HEARTBEAT} —— 心跳主会话登场这一拍</li>
 *   <li>{@link #KIND_PLAN} —— 某个任务计划被派出去跑了一次</li>
 *   <li>{@link #KIND_AGENT} —— 一个匿名子代理被当场交代跑了一次</li>
 * </ul>
 * 它们的共同点是「一次独立上下文的 LLM 执行」，所以留痕同构、同表；
 * 「编制」（心跳主会话 / 任务计划）在 {@link PlanStore}，子代理没有编制。
 *
 * <p>批次（{@code heartbeat_run}）与写库清单（{@code changeset_item}）仍是各自的表。
 *
 * <p>页面的资源使用视图（{@link #usage} / {@link #planUsage}）同样从这张表聚合出来的读，
 * 大字段（context / reasoning / tool_rounds）不读进内存，一律用 SQL 数长度。
 */
@Repository
public class ExecStore {

    /** 执行种类：心跳主会话 */
    public static final String KIND_HEARTBEAT = "heartbeat";
    /** 执行种类：任务计划（被派出） */
    public static final String KIND_PLAN = "plan";
    /** 执行种类：子代理（匿名 worker） */
    public static final String KIND_AGENT = "agent";

    public record RunRow(String id, String agentId, long beat, String trigger, String status,
                         int agentCount, String note, long startedAt, Long finishedAt) {
    }

    /** 一次执行：context 为组装出的完整 LLM 上下文（messages JSON） */
    public record ExecRow(long id, String runId, String kind, String planId, String title,
                          String status, String context, String output, String reasoning,
                          String trace, String toolRounds, String error, long startedAt,
                          Long finishedAt) {
    }

    public record EditRow(long id, long execId, String tool, String args, String summary,
                          long createdAt) {
    }

    /** 窗口内的用量：执行次数、成败、单次耗时（毫秒）的均值与峰值、工具往返次数、上下文字符量 */
    public record UsageRow(long runs, long done, long failed, long avgMs, long maxMs,
                           long toolRounds, long chars) {
    }

    /** 窗口内某个任务计划被派出的用量（planId 关联 {@link PlanStore.PlanRow#id()}） */
    public record PlanUsageRow(String planId, long runs, long done, long failed, long avgMs,
                               long maxMs, long toolRounds, long chars, Long lastAt) {
    }

    private final JdbcTemplate jdbc;

    public ExecStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 一批执行（heartbeat_run） ----------

    public void insertRun(String id, String agentId, long beat, String trigger) {
        jdbc.update(
                "INSERT INTO heartbeat_run(id, agent_id, beat, trigger, status, agent_count, started_at) "
                        + "VALUES(?,?,?,?,'running',0,?)",
                id, agentId, beat, trigger, System.currentTimeMillis());
    }

    public int finishRun(String id, String status, int agentCount, String note) {
        return jdbc.update(
                "UPDATE heartbeat_run SET status = ?, agent_count = ?, note = ?, finished_at = ? "
                        + "WHERE id = ?",
                status, agentCount, note, System.currentTimeMillis(), id);
    }

    /**
     * 最近若干批：按发生时间倒序。不能按 beat 排——beat 是进程内自增序号，每次重启都从 1 重来，
     * 而旧进程留下的行 beat 更大，按它排会把上一批的旧记录当成「最近一次」。
     *
     * @param agentId 只看某个 Agent 的批次；null 表示全部（全局观测台）
     */
    public List<RunRow> runs(String agentId, int limit) {
        if (agentId == null || agentId.isBlank()) {
            return jdbc.query(
                    "SELECT id, agent_id, beat, trigger, status, agent_count, note, started_at, finished_at "
                            + "FROM heartbeat_run ORDER BY started_at DESC, rowid DESC LIMIT ?",
                    ExecStore::mapRun, limit);
        }
        return jdbc.query(
                "SELECT id, agent_id, beat, trigger, status, agent_count, note, started_at, finished_at "
                        + "FROM heartbeat_run WHERE agent_id = ? "
                        + "ORDER BY started_at DESC, rowid DESC LIMIT ?",
                ExecStore::mapRun, agentId, limit);
    }

    public Optional<RunRow> run(String id) {
        return jdbc.query(
                        "SELECT id, agent_id, beat, trigger, status, agent_count, note, started_at, finished_at "
                                + "FROM heartbeat_run WHERE id = ?",
                        ExecStore::mapRun, id)
                .stream().findFirst();
    }

    // ---------- 一次执行（task 表里 origin 非空的行） ----------

    /**
     * 列清单（各查询共用）：顺序与 {@link #mapExec} 对应。
     *
     * <p>{@code output} 不单独存列——它就是那次执行的结论文本产物（{@code task_artifact.kind='text'}，
     * 由 {@link AgentRunner} 在收尾时落）。{@code kind} 取 {@code origin}（执行来源），
     * 因为 task.kind 是「任务种类」，把心跳与计划归成了同一类。
     */
    private static final String EXEC_COLUMNS =
            "id, run_id, origin AS kind, plan_id, title, status, context, "
                    + "(SELECT content FROM task_artifact a WHERE a.task_id = task.id "
                    + "AND a.kind = 'text' ORDER BY a.id DESC LIMIT 1) AS output, "
                    + "reasoning, trace, tool_rounds, error, started_at, finished_at";

    /** 「真跑过的一次执行」：有来源的行；前台会话的任务没有 origin，不算执行 */
    private static final String EXEC_ONLY = "origin IS NOT NULL";

    /**
     * 增量落进度：一次执行跑到中途，把当前累计的思维链、过程分段与工具往返写回来。
     *
     * <p>不碰 status / error——这条执行还没跑完，这里只是让它「中途也读得到」：
     * 页面能看着它一轮轮推进，请求方也知道它跑到哪一步了。
     */
    public int updateProgress(long id, String reasoning, String trace, String toolRounds) {
        return jdbc.update(
                "UPDATE task SET reasoning = ?, trace = ?, tool_rounds = ?, updated_at = ? WHERE id = ?",
                reasoning, trace, toolRounds, System.currentTimeMillis(), id);
    }

    /** 收尾：落状态、过程与结束时间（结论正文由 {@link AgentRunner} 落成产物） */
    public int finishExec(long id, String status, String reasoning, String trace, String toolRounds,
                          String error) {
        long now = System.currentTimeMillis();
        return jdbc.update(
                "UPDATE task SET status = ?, reasoning = ?, trace = ?, tool_rounds = ?, error = ?, "
                        + "finished_at = ?, updated_at = ? WHERE id = ?",
                status, reasoning, trace, toolRounds, error, now, now, id);
    }

    /**
     * 启动恢复：把上次进程遗留的在途<b>批次</b>（{@code heartbeat_run} 的 running）置为 interrupted，
     * 并补上 finished_at——否则页面上会留下一批永远「进行中」的幻影。
     *
     * <p>在途的执行本身由 {@link TaskStore#interruptRunning()} 一起收尾（执行就是任务行）。
     *
     * @return 改动的行数
     */
    public int interruptRunning() {
        return jdbc.update(
                "UPDATE heartbeat_run SET status = 'interrupted', finished_at = ? WHERE status = 'running'",
                System.currentTimeMillis());
    }

    public List<ExecRow> execsByRun(String runId) {
        return jdbc.query(
                "SELECT " + EXEC_COLUMNS + " FROM task WHERE run_id = ? ORDER BY id",
                ExecStore::mapExec, runId);
    }

    public Optional<ExecRow> exec(long id) {
        return jdbc.query("SELECT " + EXEC_COLUMNS + " FROM task WHERE id = ? AND " + EXEC_ONLY,
                        ExecStore::mapExec, id)
                .stream().findFirst();
    }

    /** 某计划最近若干次被执行（前端在计划卡片上回放历史） */
    public List<ExecRow> execsByPlan(String planId, int limit) {
        return jdbc.query(
                "SELECT " + EXEC_COLUMNS + " FROM task WHERE plan_id = ? "
                        + "ORDER BY id DESC LIMIT ?",
                ExecStore::mapExec, planId, limit);
    }

    /**
     * 最近若干次执行；kind 给 null 表示全部（子代理列表用 {@link #KIND_AGENT}）；
     * agentId 给 null 表示全部 Agent（全局观测台），否则只看该 Agent 产生的执行。
     */
    public List<ExecRow> latestExecs(String kind, int limit, String agentId) {
        StringBuilder sql = new StringBuilder("SELECT " + EXEC_COLUMNS + " FROM task WHERE " + EXEC_ONLY);
        boolean byKind = kind != null && !kind.isBlank();
        boolean byAgent = agentId != null && !agentId.isBlank();
        if (byKind) {
            sql.append(" AND origin = ?");
        }
        if (byAgent) {
            sql.append(" AND agent_id = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        if (byKind && byAgent) {
            return jdbc.query(sql.toString(), ExecStore::mapExec, kind, agentId, limit);
        }
        if (byKind) {
            return jdbc.query(sql.toString(), ExecStore::mapExec, kind, limit);
        }
        if (byAgent) {
            return jdbc.query(sql.toString(), ExecStore::mapExec, agentId, limit);
        }
        return jdbc.query(sql.toString(), ExecStore::mapExec, limit);
    }

    // ---------- 写库清单（读变更集；旧表 sub_exec_edit 已退役） ----------

    /**
     * 某次执行的写库清单：读 {@code changeset_item}——一次执行一个 changeset（见
     * {@link ChangeSetStore}），每条写操作是它的一个 item。
     *
     * <p>旧表 {@code sub_exec_edit} 是同一份信息的旧结构（与变更集同时写），随着执行并入
     * {@code task}，它的 {@code exec_id} 外键不再成立，故读侧统一走变更集。
     */
    public List<EditRow> editsByExec(long execId) {
        return jdbc.query(
                "SELECT i.id, i.tool, i.after AS args, i.summary, i.created_at "
                        + "FROM changeset_item i JOIN changeset c ON c.id = i.changeset_id "
                        + "WHERE c.exec_id = ? ORDER BY i.id",
                (rs, n) -> new EditRow(rs.getLong("id"), execId, rs.getString("tool"),
                        rs.getString("args"), rs.getString("summary"), rs.getLong("created_at")),
                execId);
    }

    // ---------- 资源使用聚合（页面总览） ----------

    /**
     * 窗口内的用量；kind 给 null 表示全部，否则只算这一类（计划 / 子代理 / 心跳）；
     * agentId 给 null 表示全部 Agent（全局观测台），否则只看该 Agent 产生的执行。
     *
     * <p>耗时按「已结束的用实际耗时，未结束的按到此刻为止」计算，于是进行中的一次也能被算进去。
     * 工具往返次数与字符量都由 SQL 从留痕列里数出来：{@code tool_rounds} 里每轮原生消息带一个
     * {@code "tool_call_id"}（14 字符），用「原长 − 抹掉它之后的长」除以 14 即得轮数。
     */
    public UsageRow usage(long since, String kind, String agentId) {
        StringBuilder where = new StringBuilder("WHERE " + EXEC_ONLY + " AND started_at >= ?");
        boolean byKind = kind != null && !kind.isBlank();
        boolean byAgent = agentId != null && !agentId.isBlank();
        if (byKind) {
            where.append(" AND origin = ?");
        }
        if (byAgent) {
            where.append(" AND agent_id = ?");
        }
        if (byKind && byAgent) {
            return jdbc.queryForObject("SELECT " + USAGE_METRICS + " FROM task " + where,
                    ExecStore::mapUsage, since, kind, agentId);
        }
        if (byKind) {
            return jdbc.queryForObject("SELECT " + USAGE_METRICS + " FROM task " + where,
                    ExecStore::mapUsage, since, kind);
        }
        if (byAgent) {
            return jdbc.queryForObject("SELECT " + USAGE_METRICS + " FROM task " + where,
                    ExecStore::mapUsage, since, agentId);
        }
        return jdbc.queryForObject("SELECT " + USAGE_METRICS + " FROM task " + where,
                ExecStore::mapUsage, since);
    }

    /** 窗口内逐个任务计划被派出的用量，按执行次数降序；agentId 给 null 表示全部 Agent */
    public List<PlanUsageRow> planUsage(long since, String agentId) {
        String base = "SELECT plan_id, MAX(started_at) last_at, " + USAGE_METRICS
                + " FROM task WHERE " + EXEC_ONLY + " AND started_at >= ? AND origin = ?";
        if (agentId == null || agentId.isBlank()) {
            return jdbc.query(
                    base + " GROUP BY plan_id ORDER BY runs DESC",
                    (rs, n) -> new PlanUsageRow(rs.getString("plan_id"), rs.getLong("runs"),
                            rs.getLong("done"), rs.getLong("failed"), rs.getLong("avg_ms"),
                            rs.getLong("max_ms"), rs.getLong("tool_rounds"), rs.getLong("chars"),
                            (Long) rs.getObject("last_at")),
                    since, KIND_PLAN);
        }
        return jdbc.query(
                base + " AND agent_id = ? GROUP BY plan_id ORDER BY runs DESC",
                (rs, n) -> new PlanUsageRow(rs.getString("plan_id"), rs.getLong("runs"),
                        rs.getLong("done"), rs.getLong("failed"), rs.getLong("avg_ms"),
                        rs.getLong("max_ms"), rs.getLong("tool_rounds"), rs.getLong("chars"),
                        (Long) rs.getObject("last_at")),
                since, KIND_PLAN, agentId);
    }

    /** 结论文本的长度（产物表里那条 text 产物；没有则 0） */
    private static final String OUTPUT_LENGTH =
            "(SELECT LENGTH(COALESCE(content, '')) FROM task_artifact a WHERE a.task_id = task.id "
                    + "AND a.kind = 'text' ORDER BY a.id DESC LIMIT 1)";

    /** 用量查询的公共度量子句（不含 FROM / WHERE / GROUP BY） */
    private static final String USAGE_METRICS =
            "COUNT(*) runs, "
                    + "COALESCE(SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END), 0) done, "
                    + "COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0) failed, "
                    + "COALESCE(CAST(AVG(COALESCE(finished_at, started_at) - started_at) AS INTEGER), 0) avg_ms, "
                    + "COALESCE(MAX(COALESCE(finished_at, started_at) - started_at), 0) max_ms, "
                    + "COALESCE(SUM((LENGTH(COALESCE(tool_rounds, '')) "
                    + "- LENGTH(REPLACE(COALESCE(tool_rounds, ''), '\"tool_call_id\"', ''))) / 14), 0) tool_rounds, "
                    + "COALESCE(SUM(LENGTH(COALESCE(context, '')) + COALESCE(" + OUTPUT_LENGTH + ", 0) "
                    + "+ LENGTH(COALESCE(reasoning, ''))), 0) chars";

    // ---------- 映射 ----------

    private static RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(rs.getString("id"), rs.getString("agent_id"), rs.getLong("beat"),
                rs.getString("trigger"), rs.getString("status"), rs.getInt("agent_count"),
                rs.getString("note"), rs.getLong("started_at"), (Long) rs.getObject("finished_at"));
    }

    private static ExecRow mapExec(ResultSet rs, int rowNum) throws SQLException {
        return new ExecRow(rs.getLong("id"), rs.getString("run_id"), rs.getString("kind"),
                rs.getString("plan_id"), rs.getString("title"), rs.getString("status"),
                rs.getString("context"), rs.getString("output"), rs.getString("reasoning"),
                rs.getString("trace"), rs.getString("tool_rounds"), rs.getString("error"),
                rs.getLong("started_at"), (Long) rs.getObject("finished_at"));
    }

    private static UsageRow mapUsage(ResultSet rs, int rowNum) throws SQLException {
        return new UsageRow(rs.getLong("runs"), rs.getLong("done"), rs.getLong("failed"),
                rs.getLong("avg_ms"), rs.getLong("max_ms"), rs.getLong("tool_rounds"),
                rs.getLong("chars"));
    }
}
