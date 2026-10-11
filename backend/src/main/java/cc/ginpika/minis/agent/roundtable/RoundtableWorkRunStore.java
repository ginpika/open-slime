package cc.ginpika.minis.agent.roundtable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 执行态留痕（{@code roundtable_work_run}）：每做一条 TODO 就是一次执行，留一条它的过程。
 *
 * <p>与 {@code task} 的分工：{@code task} 记「这次执行的身份与成败」（状态机、可恢复、可重跑）；
 * 本表记「这次执行<b>想了什么、做了什么</b>」——思考链、过程分段、工具往返、当次请求体。
 * 执行态页面点开某条任务时用它回放「Agent 到底在干嘛」，尤其是排查死循环。
 *
 * <p>一条执行走 {@code start}（running）→ {@code finish}（终态）。
 */
@Repository
public class RoundtableWorkRunStore {

    /** 正在跑 */
    public static final String STATUS_RUNNING = "running";
    /** 正常做完 */
    public static final String STATUS_DONE = "done";
    /** 执行失败（抛异常） */
    public static final String STATUS_FAILED = "failed";
    /** 受阻（等讨论） */
    public static final String STATUS_BLOCKED = "blocked";

    public record RunRow(long id, String todoId, String sessionId, String peerId, String peerName,
                         String status, String output, String reasoning, String trace,
                         String toolRounds, String context, String error, long startedAt,
                         Long finishedAt) {
    }

    private static final String COLS =
            "id, todo_id, session_id, peer_id, peer_name, status, output, reasoning, trace, "
                    + "tool_rounds, context, error, started_at, finished_at";

    private final JdbcTemplate jdbc;

    public RoundtableWorkRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 开一条执行留痕（running），返回它的 id */
    public long start(String sessionId, String todoId, String peerId, String peerName) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO roundtable_work_run(session_id, todo_id, peer_id, peer_name, status, "
                            + "started_at) VALUES(?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, todoId);
            ps.setString(3, peerId);
            ps.setString(4, peerName);
            ps.setString(5, STATUS_RUNNING);
            ps.setLong(6, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 收尾：写终态与过程留痕 */
    public void finish(long id, String status, String output, String reasoning, String trace,
                       String toolRounds, String context, String error) {
        if (id <= 0) {
            return;
        }
        jdbc.update("UPDATE roundtable_work_run SET status = ?, output = COALESCE(?, output), "
                        + "reasoning = COALESCE(?, reasoning), trace = COALESCE(?, trace), "
                        + "tool_rounds = COALESCE(?, tool_rounds), context = COALESCE(?, context), "
                        + "error = ?, finished_at = ? WHERE id = ?",
                status, output, reasoning, trace, toolRounds, context, error,
                System.currentTimeMillis(), id);
    }

    /** 把某条待办最新一次执行收成终态（执行线程在异常 / 受阻时用；不改写已有留痕） */
    public void finishLatest(String todoId, String status, String error) {
        Long id = jdbc.query("SELECT MAX(id) FROM roundtable_work_run WHERE todo_id = ?",
                (rs, n) -> rs.getLong(1), todoId).stream().findFirst().orElse(null);
        if (id == null) {
            return;
        }
        jdbc.update("UPDATE roundtable_work_run SET status = ?, error = COALESCE(?, error), "
                + "finished_at = COALESCE(finished_at, ?) WHERE id = ?",
                status, error, System.currentTimeMillis(), id);
    }

    /** 某条待办的全部执行（新→旧）：页面点开这条任务看它的执行历史 */
    public List<RunRow> byTodo(String todoId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_work_run WHERE todo_id = ? "
                + "ORDER BY id DESC LIMIT ?", RoundtableWorkRunStore::map, todoId, limit);
    }

    /** 某条待办最新一次执行（看它此刻在不在跑） */
    public Optional<RunRow> latest(String todoId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_work_run WHERE todo_id = ? "
                + "ORDER BY id DESC LIMIT 1", RoundtableWorkRunStore::map, todoId).stream().findFirst();
    }

    /** 删除某会话的执行留痕（删会话时清理） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM roundtable_work_run WHERE session_id = ?", sessionId);
    }

    private static RunRow map(ResultSet rs, int rowNum) throws SQLException {
        long finished = rs.getLong("finished_at");
        Long finishedAt = rs.wasNull() ? null : finished;
        return new RunRow(rs.getLong("id"), rs.getString("todo_id"), rs.getString("session_id"),
                rs.getString("peer_id"), rs.getString("peer_name"), rs.getString("status"),
                rs.getString("output"), rs.getString("reasoning"), rs.getString("trace"),
                rs.getString("tool_rounds"), rs.getString("context"), rs.getString("error"),
                rs.getLong("started_at"), finishedAt);
    }
}
