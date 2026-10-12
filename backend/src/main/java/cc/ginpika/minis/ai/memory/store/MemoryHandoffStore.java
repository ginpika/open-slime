package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交接状态（memory_handoff，memory-v6 §9.2）：L4 的「交到谁手上、做到哪一步」。
 *
 * <p>状态机 {@code offered → accepted → done}（可 {@code rejected}）；{@code offered} 的交接属受保护
 * 条目（I10：不因预算与巩固被动消失），接收方在收件箱里按需发现（不推送）。
 */
@Repository
public class MemoryHandoffStore {

    /** 一条交接记录 */
    public record HandoffRow(long id, long itemId, String toKind, String toRef, String state,
                             String note, long createdAt, long updatedAt) {
    }

    private static final String COLS = "id, item_id, to_kind, to_ref, state, note, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public MemoryHandoffStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long offer(long itemId, String toKind, String toRef, String note) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO memory_handoff(item_id, to_kind, to_ref, state, note, created_at, "
                        + "updated_at) VALUES(?,?,?, 'offered', ?, ?, ?)",
                itemId, toKind, toRef, note, now, now);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public int transition(long id, String from, String to) {
        return jdbc.update("UPDATE memory_handoff SET state=?, updated_at=? WHERE id=? AND state=?",
                to, System.currentTimeMillis(), id, from);
    }

    public int setState(long id, String state) {
        return jdbc.update("UPDATE memory_handoff SET state = ?, updated_at = ? WHERE id = ?",
                state, System.currentTimeMillis(), id);
    }

    public Optional<HandoffRow> findById(long id) {
        List<HandoffRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_handoff WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 发给某接收方、指定状态的交接（收件箱），新的在前 */
    public List<HandoffRow> byTarget(String toKind, String toRef, String state, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_handoff WHERE to_kind = ? AND to_ref IS ? "
                        + "AND state = ? ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), toKind, toRef, state, Math.max(1, limit));
    }

    /** 某条目的交接记录，新的在前 */
    public List<HandoffRow> byItem(long itemId) {
        return jdbc.query("SELECT " + COLS + " FROM memory_handoff WHERE item_id = ? ORDER BY id DESC",
                (rs, n) -> map(rs), itemId);
    }

    /** 全部指定状态的交接（管理页「收件箱」用：人工作用域看全量），新的在前 */
    public List<HandoffRow> listByState(String state, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_handoff WHERE state = ? "
                + "ORDER BY id DESC LIMIT ?", (rs, n) -> map(rs), state, Math.max(1, limit));
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM memory_handoff");
    }

    private static HandoffRow map(ResultSet rs) throws SQLException {
        return new HandoffRow(rs.getLong("id"), rs.getLong("item_id"), rs.getString("to_kind"),
                rs.getString("to_ref"), rs.getString("state"), rs.getString("note"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
