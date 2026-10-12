package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写入审计（memory_revision，memory-v6 §2.2 法四 / §6.1）：每次条目变更留一条，可回放、可追责、可 purge 溯源。
 *
 * <p>与 {@code memory_event} 的区别：事件是「说过/做过」的正本投影，本表是「记忆被谁改成了什么」。
 * 只追加、不改写；{@code before/after} 存紧凑摘要（不是完整行快照），够审阅与回放判断即可。
 */
@Repository
public class MemoryRevisionStore {

    /** 一条审计记录 */
    public record RevisionRow(long id, Long itemId, String op, String actorKind, String actorId,
                              String turnId, String beforeJson, String afterJson, String reason,
                              long createdAt) {
    }

    private static final String COLS = "id, item_id, op, actor_kind, actor_id, turn_id, before_json, "
            + "after_json, reason, created_at";

    private final JdbcTemplate jdbc;

    public MemoryRevisionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long add(Long itemId, String op, String actorKind, String actorId, String turnId,
                    String beforeJson, String afterJson, String reason) {
        jdbc.update("INSERT INTO memory_revision(item_id, op, actor_kind, actor_id, turn_id, "
                        + "before_json, after_json, reason, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                itemId, op, actorKind == null ? "SYSTEM" : actorKind, actorId, turnId, beforeJson,
                afterJson, reason, System.currentTimeMillis());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /** 某条目的变更历史，新的在前 */
    public List<RevisionRow> byItem(long itemId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_revision WHERE item_id = ? "
                + "ORDER BY id DESC LIMIT ?", (rs, n) -> map(rs), itemId, Math.max(1, limit));
    }

    /** 全部变更，新的在前（管理页审计视图） */
    public List<RevisionRow> recent(int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_revision ORDER BY id DESC LIMIT ?",
                (rs, n) -> map(rs), Math.max(1, limit));
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_revision", Integer.class);
        return n == null ? 0 : n;
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM memory_revision");
    }

    private static RevisionRow map(ResultSet rs) throws SQLException {
        long itemId = rs.getLong("item_id");
        Long item = rs.wasNull() ? null : itemId;
        return new RevisionRow(rs.getLong("id"), item, rs.getString("op"), rs.getString("actor_kind"),
                rs.getString("actor_id"), rs.getString("turn_id"), rs.getString("before_json"),
                rs.getString("after_json"), rs.getString("reason"), rs.getLong("created_at"));
    }
}
