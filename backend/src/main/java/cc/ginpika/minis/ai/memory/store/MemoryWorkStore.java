package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 工作状态（memory_work，memory-v6 §13）：会话 / 任务级的**临时工作材料**——进度、临时结论、
 * 工具结果、在途决策、待办快照。与长期记忆（{@code graph_claim}）**物理分离**，这是「三概念分离」的落点。
 *
 * <p>生命周期：{@code open → closed | distilled | archived}；带 {@code expires_at}（TTL 到期不再注入）。
 * 会话/任务收尾时由 {@link cc.ginpika.minis.ai.memory.WorkingSetService} 蒸馏进长期记忆后归档。
 */
@Repository
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class MemoryWorkStore {

    /** 一条工作状态 */
    public record WorkRow(long id, String sessionId, Long taskId, String agentId, String projectId,
                          String kind, String title, String body, String refType, Long refId,
                          String status, Long expiresAt, long createdAt, long updatedAt) {
    }

    private static final String COLS = "id, session_id, task_id, agent_id, project_id, kind, title, "
            + "body, ref_type, ref_id, status, expires_at, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public MemoryWorkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @jakarta.annotation.PostConstruct
    public void recoverDistillation() {
        jdbc.update("UPDATE memory_work SET status='open', updated_at=? WHERE status='distilling'",
                System.currentTimeMillis());
    }

    public long add(String sessionId, Long taskId, String agentId, String projectId, String kind,
                    String title, String body, String refType, Long refId, Long expiresAt) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO memory_work(session_id, task_id, agent_id, project_id, kind, title, "
                        + "body, ref_type, ref_id, status, expires_at, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?, 'open', ?, ?, ?)",
                sessionId, taskId, agentId, projectId, kind, title, body, refType, refId, expiresAt,
                now, now);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<WorkRow> findById(long id) {
        List<WorkRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_work WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 某会话仍有效（open 且未过期）的工作项，新的在前 */
    public List<WorkRow> openBySession(String sessionId, long now, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_work WHERE session_id = ? AND status = 'open' "
                        + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), sessionId, now, Math.max(1, limit));
    }

    /** 某任务仍有效的工作项，新的在前 */
    public List<WorkRow> openByTask(Long taskId, long now, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_work WHERE task_id = ? AND status = 'open' "
                        + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), taskId, now, Math.max(1, limit));
    }

    /** 全部仍有效的工作项（管理页 / 人工作用域），新的在前 */
    public List<WorkRow> openAll(long now, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_work WHERE status = 'open' "
                        + "AND (expires_at IS NULL OR expires_at > ?) ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), now, Math.max(1, limit));
    }

    /** 某会话全部工作项（含已关闭 / 已蒸馏；管理页按会话查看），新的在前 */
    public List<WorkRow> bySession(String sessionId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_work WHERE session_id = ? "
                + "ORDER BY id DESC LIMIT ?", (rs, n) -> map(rs), sessionId, Math.max(1, limit));
    }

    public int countOpen(long now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_work WHERE status = 'open' "
                + "AND (expires_at IS NULL OR expires_at > ?)", Integer.class, now);
        return n == null ? 0 : n;
    }

    /** 某任务下是否已有指定标题的工作项（恢复的幂等判据：重启不应重复还原同一份检查点） */
    public boolean existsByTaskAndTitle(Long taskId, String title) {
        if (taskId == null || title == null) {
            return false;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_work WHERE task_id = ? "
                + "AND title = ?", Integer.class, taskId, title);
        return n != null && n > 0;
    }

    public int setStatus(long id, String status) {
        return jdbc.update("UPDATE memory_work SET status = ?, updated_at = ? WHERE id = ?",
                status, System.currentTimeMillis(), id);
    }

    public int transition(long id, String from, String to) {
        return jdbc.update("UPDATE memory_work SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                to, System.currentTimeMillis(), id, from);
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM memory_work");
    }

    public List<WorkRow> recentObserved(int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_work ORDER BY updated_at DESC, id DESC LIMIT ?",
                (rs,n)->map(rs), Math.max(1,Math.min(limit,200)));
    }

    private static WorkRow map(ResultSet rs) throws SQLException {
        long taskId = rs.getLong("task_id");
        Long task = rs.wasNull() ? null : taskId;
        long refId = rs.getLong("ref_id");
        Long ref = rs.wasNull() ? null : refId;
        long exp = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : exp;
        return new WorkRow(rs.getLong("id"), rs.getString("session_id"), task, rs.getString("agent_id"),
                rs.getString("project_id"), rs.getString("kind"), rs.getString("title"),
                rs.getString("body"), rs.getString("ref_type"), ref, rs.getString("status"),
                expiresAt, rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
