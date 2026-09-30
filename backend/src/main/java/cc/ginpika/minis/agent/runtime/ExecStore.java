package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.subconscious.store.PlanStore;
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
 * 「一次执行」的留痕与聚合：sub_run（一批）/ sub_exec（一次）/ sub_exec_edit（写库清单）。
 *
 * <p>三种执行共用这一层，靠 {@code kind} 区分：
 * <ul>
 *   <li>{@link #KIND_HEARTBEAT} —— 心跳主会话登场这一拍</li>
 *   <li>{@link #KIND_PLAN} —— 某个任务计划被派出去跑了一次</li>
 *   <li>{@link #KIND_AGENT} —— 一个匿名子代理被当场交代跑了一次</li>
 * </ul>
 * 它们的共同点是「一次独立上下文的 LLM 执行」，所以留痕同构、同表；
 * 「编制」（心跳主会话 / 任务计划）在 {@link PlanStore}，子代理没有编制。
 *
 * <p>页面的资源使用视图（{@link #usage} / {@link #planUsage}）同样是从这张表聚合出来的读，
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

    public record RunRow(String id, long beat, String trigger, String status, int agentCount,
                         String note, long startedAt, Long finishedAt) {
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

    // ---------- 一批执行（sub_run） ----------

    public void insertRun(String id, long beat, String trigger) {
        jdbc.update(
                "INSERT INTO sub_run(id, beat, trigger, status, agent_count, started_at) "
                        + "VALUES(?,?,?,'running',0,?)",
                id, beat, trigger, System.currentTimeMillis());
    }

    public int finishRun(String id, String status, int agentCount, String note) {
        return jdbc.update(
                "UPDATE sub_run SET status = ?, agent_count = ?, note = ?, finished_at = ? "
                        + "WHERE id = ?",
                status, agentCount, note, System.currentTimeMillis(), id);
    }

    /**
     * 最近若干批：按发生时间倒序。不能按 beat 排——beat 是进程内自增序号，
     * 每次重启都从 1 重来，而旧进程留下的行 beat 更大，按它排会把上一批的旧记录当成「最近一次」。
     */
    public List<RunRow> runs(int limit) {
        return jdbc.query(
                "SELECT id, beat, trigger, status, agent_count, note, started_at, finished_at "
                        + "FROM sub_run ORDER BY started_at DESC, rowid DESC LIMIT ?",
                ExecStore::mapRun, limit);
    }

    public Optional<RunRow> run(String id) {
        return jdbc.query(
                        "SELECT id, beat, trigger, status, agent_count, note, started_at, finished_at "
                                + "FROM sub_run WHERE id = ?",
                        ExecStore::mapRun, id)
                .stream().findFirst();
    }

    // ---------- 一次执行（sub_exec） ----------

    /** 列清单（各查询共用）：顺序与 {@link #mapExec} 对应 */
    private static final String EXEC_COLUMNS =
            "id, run_id, kind, plan_id, title, status, context, output, reasoning, trace, "
                    + "tool_rounds, error, started_at, finished_at";

    public long insertExec(String runId, String kind, String planId, String title, String context) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO sub_exec(run_id, kind, plan_id, title, status, context, started_at) "
                            + "VALUES(?,?,?,?,'running',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, runId);
            ps.setString(2, kind);
            ps.setString(3, planId);
            ps.setString(4, title);
            ps.setString(5, context);
            ps.setLong(6, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /**
     * 增量落进度：一次执行跑到中途，把当前累计的思维链、过程分段与工具往返写回来。
     *
     * <p>不碰 status / output / error——这条执行还没跑完，这里只是让它「中途也读得到」：
     * 页面能看着它一轮轮推进，请求方也知道它跑到哪一步了。
     */
    public int updateProgress(long id, String reasoning, String trace, String toolRounds) {
        return jdbc.update(
                "UPDATE sub_exec SET reasoning = ?, trace = ?, tool_rounds = ? WHERE id = ?",
                reasoning, trace, toolRounds, id);
    }

    public int finishExec(long id, String status, String output, String reasoning, String trace,
                          String toolRounds, String error) {
        return jdbc.update(
                "UPDATE sub_exec SET status = ?, output = ?, reasoning = ?, trace = ?, "
                        + "tool_rounds = ?, error = ?, finished_at = ? WHERE id = ?",
                status, output, reasoning, trace, toolRounds, error,
                System.currentTimeMillis(), id);
    }

    public List<ExecRow> execsByRun(String runId) {
        return jdbc.query(
                "SELECT " + EXEC_COLUMNS + " FROM sub_exec WHERE run_id = ? ORDER BY id",
                ExecStore::mapExec, runId);
    }

    public Optional<ExecRow> exec(long id) {
        return jdbc.query("SELECT " + EXEC_COLUMNS + " FROM sub_exec WHERE id = ?",
                        ExecStore::mapExec, id)
                .stream().findFirst();
    }

    /** 某计划最近若干次被执行（前端在计划卡片上回放历史） */
    public List<ExecRow> execsByPlan(String planId, int limit) {
        return jdbc.query(
                "SELECT " + EXEC_COLUMNS + " FROM sub_exec WHERE plan_id = ? "
                        + "ORDER BY id DESC LIMIT ?",
                ExecStore::mapExec, planId, limit);
    }

    /** 最近若干次执行；kind 给 null 表示不过滤（子代理列表用 {@link #KIND_AGENT}） */
    public List<ExecRow> latestExecs(String kind, int limit) {
        if (kind == null) {
            return jdbc.query("SELECT " + EXEC_COLUMNS + " FROM sub_exec ORDER BY id DESC LIMIT ?",
                    ExecStore::mapExec, limit);
        }
        return jdbc.query(
                "SELECT " + EXEC_COLUMNS + " FROM sub_exec WHERE kind = ? ORDER BY id DESC LIMIT ?",
                ExecStore::mapExec, kind, limit);
    }

    // ---------- 写库清单（sub_exec_edit） ----------

    public void insertEdit(long execId, String tool, String args, String summary) {
        jdbc.update(
                "INSERT INTO sub_exec_edit(exec_id, tool, args, summary, created_at) VALUES(?,?,?,?,?)",
                execId, tool, args, summary, System.currentTimeMillis());
    }

    public List<EditRow> editsByExec(long execId) {
        return jdbc.query(
                "SELECT id, exec_id, tool, args, summary, created_at FROM sub_exec_edit "
                        + "WHERE exec_id = ? ORDER BY id",
                (rs, n) -> new EditRow(rs.getLong("id"), rs.getLong("exec_id"), rs.getString("tool"),
                        rs.getString("args"), rs.getString("summary"), rs.getLong("created_at")),
                execId);
    }

    // ---------- 资源使用聚合（页面总览） ----------

    /**
     * 窗口内的用量；kind 给 null 表示全部，否则只算这一类（计划 / 子代理 / 心跳）。
     *
     * <p>耗时按「已结束的用实际耗时，未结束的按到此刻为止」计算，于是进行中的一次也能被算进去。
     * 工具往返次数与字符量都由 SQL 从留痕列里数出来：{@code tool_rounds} 里每轮原生消息带一个
     * {@code "tool_call_id"}（14 字符），用「原长 − 抹掉它之后的长」除以 14 即得轮数。
     */
    public UsageRow usage(long since, String kind) {
        String where = "WHERE started_at >= ?" + (kind == null ? "" : " AND kind = ?");
        Object[] args = kind == null ? new Object[]{since} : new Object[]{since, kind};
        return jdbc.queryForObject(
                "SELECT " + USAGE_METRICS + " FROM sub_exec " + where, ExecStore::mapUsage, args);
    }

    /** 窗口内逐个任务计划被派出的用量，按执行次数降序 */
    public List<PlanUsageRow> planUsage(long since) {
        return jdbc.query(
                "SELECT plan_id, MAX(started_at) last_at, " + USAGE_METRICS
                        + " FROM sub_exec WHERE started_at >= ? AND kind = ? "
                        + "GROUP BY plan_id ORDER BY runs DESC",
                (rs, n) -> new PlanUsageRow(rs.getString("plan_id"), rs.getLong("runs"),
                        rs.getLong("done"), rs.getLong("failed"), rs.getLong("avg_ms"),
                        rs.getLong("max_ms"), rs.getLong("tool_rounds"), rs.getLong("chars"),
                        (Long) rs.getObject("last_at")),
                since, KIND_PLAN);
    }

    /** 用量查询的公共度量子句（不含 FROM / WHERE / GROUP BY） */
    private static final String USAGE_METRICS =
            "COUNT(*) runs, "
                    + "COALESCE(SUM(CASE WHEN status = 'done' THEN 1 ELSE 0 END), 0) done, "
                    + "COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0) failed, "
                    + "COALESCE(CAST(AVG(COALESCE(finished_at, started_at) - started_at) AS INTEGER), 0) avg_ms, "
                    + "COALESCE(MAX(COALESCE(finished_at, started_at) - started_at), 0) max_ms, "
                    + "COALESCE(SUM((LENGTH(COALESCE(tool_rounds, '')) "
                    + "- LENGTH(REPLACE(COALESCE(tool_rounds, ''), '\"tool_call_id\"', ''))) / 14), 0) tool_rounds, "
                    + "COALESCE(SUM(LENGTH(COALESCE(context, '')) + LENGTH(COALESCE(output, '')) "
                    + "+ LENGTH(COALESCE(reasoning, ''))), 0) chars";

    // ---------- 映射 ----------

    private static RunRow mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunRow(rs.getString("id"), rs.getLong("beat"), rs.getString("trigger"),
                rs.getString("status"), rs.getInt("agent_count"), rs.getString("note"),
                rs.getLong("started_at"), (Long) rs.getObject("finished_at"));
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
