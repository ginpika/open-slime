package cc.ginpika.minis.agent.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 工具执行记录（{@code tool_execution}）：设计一 §五-2 的落点——每一次工具调用都有一条独立的
 * 生命周期记录。
 *
 * <p>「取消生成，不等于取消工具副作用」：模型停止生成可以尽力中断，但它一旦发起过有副作用的调用，
 * 仅靠取消模型请求保证不了操作停止。所以每次调用都先落 {@code running}，结束时落终态
 * （{@code done} / {@code failed} / {@code cancelled} / {@code uncertain}）。{@code uncertain}
 * 专给「结果不确定」的情形——这类调用必须先查状态，不能盲目重试。
 *
 * <p>幂等键（{@code idem_key}）把「同一次调用的重复投递」标出来，供重试判重使用。
 */
@Repository
public class ToolExecutionStore {

    /** 状态：正在执行 */
    public static final String STATUS_RUNNING = "running";
    /** 状态：正常结束 */
    public static final String STATUS_DONE = "done";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "failed";
    /** 状态：被取消 */
    public static final String STATUS_CANCELLED = "cancelled";
    /** 状态：结果不确定（须先查询状态，不能盲目重试） */
    public static final String STATUS_UNCERTAIN = "uncertain";

    public record ToolExecRow(long id, String sessionId, String taskId, String tool, String args,
                              String idemKey, String status, boolean cancellable, String result,
                              String error, long startedAt, Long finishedAt) {
    }

    private final JdbcTemplate jdbc;

    public ToolExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 落一条「开始执行」，返回记录 id */
    public long start(String sessionId, String taskId, String tool, String args, String idemKey,
                      boolean cancellable) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO tool_execution(session_id, task_id, tool, args, idem_key, status, "
                            + "cancellable, started_at) VALUES(?,?,?,?,?,?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, taskId);
            ps.setString(3, tool);
            ps.setString(4, args);
            ps.setString(5, idemKey);
            ps.setString(6, STATUS_RUNNING);
            ps.setInt(7, cancellable ? 1 : 0);
            ps.setLong(8, System.currentTimeMillis());
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 落一条终态（结果 / 错误各自可空） */
    public void finish(long id, String status, String result, String error) {
        if (id <= 0) {
            return;
        }
        jdbc.update("UPDATE tool_execution SET status = ?, result = ?, error = ?, finished_at = ? "
                + "WHERE id = ?", status, result, error, System.currentTimeMillis(), id);
    }

    /** 某会话最近的工具执行（倒序） */
    public List<ToolExecRow> recent(String sessionId, int limit) {
        return jdbc.query("SELECT * FROM tool_execution WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                ToolExecutionStore::map, sessionId, limit);
    }

    /** 按幂等键找既有执行（重试前先查：结果不确定的不能盲目重试） */
    public Optional<ToolExecRow> byIdemKey(String idemKey) {
        if (idemKey == null || idemKey.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM tool_execution WHERE idem_key = ? ORDER BY id DESC LIMIT 1",
                ToolExecutionStore::map, idemKey).stream().findFirst();
    }

    /** 删除某会话的执行记录（删会话时清理） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM tool_execution WHERE session_id = ?", sessionId);
    }

    private static ToolExecRow map(ResultSet rs, int rowNum) throws SQLException {
        /* wasNull 必须紧跟对应的 getLong：先算 finishedAt，再读其余列，否则会被后续读取覆盖 */
        long finished = rs.getLong("finished_at");
        Long finishedAt = rs.wasNull() ? null : finished;
        return new ToolExecRow(rs.getLong("id"), rs.getString("session_id"), rs.getString("task_id"),
                rs.getString("tool"), rs.getString("args"), rs.getString("idem_key"),
                rs.getString("status"), rs.getInt("cancellable") == 1, rs.getString("result"),
                rs.getString("error"), rs.getLong("started_at"), finishedAt);
    }
}
