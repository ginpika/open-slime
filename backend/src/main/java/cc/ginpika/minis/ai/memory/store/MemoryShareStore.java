package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 共享授权（memory_share）：L4 交接 / 知识的发布落点（memory-v5 §7.3）。
 *
 * <p>Agent 主动把条目发布到指定作用域（user / project / agent / session）；读侧 {@code visible} 谓词的
 * R5 经本表联合查询发现（不推送）。撤销即把 {@code status} 置 revoked，读侧天然不可见。
 */
@Repository
public class MemoryShareStore {

    /** 一条共享记录 */
    public record ShareRow(long id, long itemId, String fromOwner, String fromAgent, String fromProject,
                           String toKind, String toRef, String status, String note, long createdAt,
                           Long revokedAt) {
    }

    private static final String COLS = "id, item_id, from_owner, from_agent, from_project, to_kind, "
            + "to_ref, status, note, created_at, revoked_at";

    private final JdbcTemplate jdbc;

    public MemoryShareStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 发布：把某条目授权给 toKind / toRef 的接收方；返回共享行 id */
    public long publish(long itemId, String fromOwner, String fromAgent, String fromProject,
                        String toKind, String toRef, String note) {
        jdbc.update("INSERT INTO memory_share(item_id, from_owner, from_agent, from_project, to_kind, "
                        + "to_ref, status, note, created_at) VALUES(?,?,?,?,?,?, 'published', ?, ?)",
                itemId, fromOwner, fromAgent, fromProject, toKind, toRef, note,
                System.currentTimeMillis());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** 撤销一条共享 */
    public int revoke(long id) {
        return jdbc.update("UPDATE memory_share SET status = 'revoked', revoked_at = ? WHERE id = ?",
                System.currentTimeMillis(), id);
    }

    /** 撤销某条目的全部共享 */
    public int revokeByItem(long itemId) {
        return jdbc.update("UPDATE memory_share SET status = 'revoked', revoked_at = ? "
                + "WHERE item_id = ? AND status = 'published'", System.currentTimeMillis(), itemId);
    }

    /** 某条目当前的共享记录，新建在前 */
    public java.util.Optional<ShareRow> findById(long id) {
        return jdbc.query("SELECT " + COLS + " FROM memory_share WHERE id=?", (rs,n)->map(rs), id).stream().findFirst();
    }

    /** 某条目当前的共享记录，新建在前 */
    public List<ShareRow> byItem(long itemId) {
        return jdbc.query("SELECT " + COLS + " FROM memory_share WHERE item_id = ? ORDER BY id DESC",
                (rs, n) -> map(rs), itemId);
    }

    /** 全部已发布共享（管理页「已发布」视图），新建在前 */
    public List<ShareRow> listPublished() {
        return jdbc.query("SELECT " + COLS + " FROM memory_share WHERE status = 'published' "
                + "ORDER BY id DESC", (rs, n) -> map(rs));
    }

    /** 清空全部共享记录（设置页「重置知识库」连带清除记忆时用） */
    public int clearAll() {
        return jdbc.update("DELETE FROM memory_share");
    }

    private static ShareRow map(ResultSet rs) throws SQLException {
        long revoked = rs.getLong("revoked_at");
        Long revokedAt = rs.wasNull() ? null : revoked;
        return new ShareRow(rs.getLong("id"), rs.getLong("item_id"), rs.getString("from_owner"),
                rs.getString("from_agent"), rs.getString("from_project"), rs.getString("to_kind"),
                rs.getString("to_ref"), rs.getString("status"), rs.getString("note"),
                rs.getLong("created_at"), revokedAt);
    }
}
