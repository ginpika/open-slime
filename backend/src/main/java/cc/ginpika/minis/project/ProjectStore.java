package cc.ginpika.minis.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 项目（project）：跨 Session / Task / Agent 的业务工作域（memory-v5 §4.1）。
 *
 * <p>项目是「项目间记忆隔离」的锚点：记忆按 {@code owner=PROJECT + project_id} 归属，读侧 visible 谓词
 * 只放行当前项目。{@code narrative=1} 表示创作 / 虚构类项目——其域内记忆永不升级为用户画像。
 *
 * <p>放在顶层 {@code project} 包（非 agent、非 ai.memory）：会话编排层与记忆侧都要用它，且不属于任何
 * 一侧的分层约束范围（{@code LayeringTest} 只管 core/knowledge/memory/terminal/agent）。
 */
@Repository
public class ProjectStore {

    /** 一个项目 */
    public record ProjectRow(String id, String userId, String name, String kind, boolean narrative,
                             String status, String note, long createdAt, long updatedAt) {
    }

    private static final String COLS = "id, user_id, name, kind, narrative, status, note, "
            + "created_at, updated_at";

    private final JdbcTemplate jdbc;

    public ProjectStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部项目（未归档在前，新建在前） */
    public List<ProjectRow> list() {
        return jdbc.query("SELECT " + COLS + " FROM project "
                + "ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, id DESC",
                (rs, n) -> map(rs));
    }

    public Optional<ProjectRow> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<ProjectRow> rows = jdbc.query("SELECT " + COLS + " FROM project WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 是否创作 / 虚构类项目（决定域内记忆能否升画像）；项目不存在返回 false */
    public boolean isNarrative(String projectId) {
        return findById(projectId).map(ProjectRow::narrative).orElse(false);
    }

    public void insert(String id, String userId, String name, String kind, boolean narrative,
                       String status, String note) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO project(id, user_id, name, kind, narrative, status, note, "
                        + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, userId, name, kind, narrative ? 1 : 0, status, note, now, now);
    }

    public int update(String id, String name, String kind, Boolean narrative, String status, String note) {
        StringBuilder sets = new StringBuilder();
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (name != null) {
            sets.append("name = ?, ");
            args.add(name);
        }
        if (kind != null) {
            sets.append("kind = ?, ");
            args.add(kind);
        }
        if (narrative != null) {
            sets.append("narrative = ?, ");
            args.add(narrative ? 1 : 0);
        }
        if (status != null) {
            sets.append("status = ?, ");
            args.add(status);
        }
        if (note != null) {
            sets.append("note = ?, ");
            args.add(note);
        }
        if (sets.isEmpty()) {
            return 0;
        }
        args.add(System.currentTimeMillis());
        args.add(id);
        return jdbc.update("UPDATE project SET " + sets + "updated_at = ? WHERE id = ?", args.toArray());
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM project WHERE id = ?", id);
    }

    private static ProjectRow map(ResultSet rs) throws SQLException {
        return new ProjectRow(rs.getString("id"), rs.getString("user_id"), rs.getString("name"),
                rs.getString("kind"), rs.getInt("narrative") != 0, rs.getString("status"),
                rs.getString("note"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
