package cc.ginpika.minis.agent.roundtable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 圆桌会话的读写口——<b>底表是 {@code hub_session}</b>：会话已归一，圆桌只是 mode='multi-agent' 的会话。
 *
 * <p>会话不再分两张表。首页「最近会话」（{@link cc.ginpika.minis.agent.chat.store.HubSessionStore}）
 * 只取 page mode='ai' 的行，因此圆桌不会串到首页去；反过来本类的每个查询都钉住 mode='multi-agent'，
 * 两边各看各的。成员名册是圆桌独有的东西，存 {@code hub_session.member_ids}（首页会话为 NULL）。
 *
 * <p>类名保留了历史称呼（改名要动一圈调用点）；它现在是一张「按 mode 过滤的会话视图」。
 */
@Repository
public class RoundtableSessionStore {

    /** 会话模式标记：多智能体圆桌 */
    public static final String MODE = "multi-agent";

    public record SessionRow(String id, String title, String modelId, String memberIds, Long createdAt,
                             Long updatedAt, String projectId) {
    }

    private static final String COLS = "id, title, model_id, member_ids, created_at, updated_at, project_id";

    private final JdbcTemplate jdbc;

    public RoundtableSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新会话：标题留空，由首条发言截断回填。
     *
     * <p>{@code memberIds} 是这张圆桌的成员（{@code agent.id} 的 JSON 数组）；传 null 表示
     * 「全体名册」——首页「和所有智能体对话」直接开说、以及加这一列之前建的旧会话都是这种。
     *
     * <p>max_rounds 是历史兼容列，写 0，不再参与执行。context_trace / show_cot 与旧行为一致地置 1
     * （圆桌的请求体不走 History，这两列实际不影响它）。
     */
    public String insert(String memberIds) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO hub_session(id, title, mode, member_ids, max_rounds, context_trace, "
                        + "show_cot, created_at, updated_at) VALUES(?,?,?,?,?,1,1,?,?)",
                id, null, MODE, memberIds, 0, now, now);
        return id;
    }

    public Optional<SessionRow> findById(String id) {
        return jdbc.query("SELECT " + COLS + " FROM hub_session WHERE id = ? AND mode = ?",
                RoundtableSessionStore::map, id, MODE).stream().findFirst();
    }

    /** 最近一个圆桌会话（按 COALESCE(updated_at, created_at) 倒序） */
    public Optional<SessionRow> latest() {
        return jdbc.query("SELECT " + COLS + " FROM hub_session WHERE mode = ? "
                        + "ORDER BY COALESCE(updated_at, created_at) DESC LIMIT 1",
                RoundtableSessionStore::map, MODE).stream().findFirst();
    }

    public List<SessionRow> listAll() {
        return jdbc.query("SELECT " + COLS + " FROM hub_session WHERE mode = ? "
                + "ORDER BY COALESCE(updated_at, created_at) DESC", RoundtableSessionStore::map, MODE);
    }

    /** 有新发言时刷新时间戳，并按需回填标题（首条发言截断做标题） */
    public void touch(String id, String title) {
        jdbc.update("UPDATE hub_session SET updated_at = ?, title = COALESCE(title, ?) "
                + "WHERE id = ? AND mode = ?", System.currentTimeMillis(), title, id, MODE);
    }

    /** 兜底模型（成员自己没指定模型时用）；null 表示不设置 */
    public void updateModel(String id, String modelId) {
        if (modelId == null) {
            return;
        }
        jdbc.update("UPDATE hub_session SET model_id = ?, updated_at = ? WHERE id = ? AND mode = ?",
                modelId, System.currentTimeMillis(), id, MODE);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM hub_session WHERE id = ? AND mode = ?", id, MODE);
    }

    public boolean hasIssueRelations(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM collaboration_issue "
                + "WHERE parent_session_id=? OR session_id=?)", Boolean.class, id, id));
    }

    public boolean isResolvedIssue(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM collaboration_issue "
                + "WHERE session_id=? AND status='resolved')", Boolean.class, id));
    }

    public List<String> archivedIds() {
        return jdbc.queryForList("SELECT session_id FROM collaboration_task_archive",String.class);
    }

    public boolean archived(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM collaboration_task_archive a WHERE a.session_id=? "
                +"OR a.session_id=(SELECT parent_session_id FROM collaboration_issue WHERE session_id=?))",Boolean.class,id,id));
    }

    public void requireWritable(String id) {
        if(archived(id))throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"任务已归档，请先恢复后继续");
    }

    private static SessionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("model_id"),
                rs.getString("member_ids"), RoundtableRows.nullableLong(rs, "created_at"),
                RoundtableRows.nullableLong(rs, "updated_at"), rs.getString("project_id"));
    }
}
