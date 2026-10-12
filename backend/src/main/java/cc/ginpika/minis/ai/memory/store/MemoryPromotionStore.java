package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 提升记录（memory_promotion，memory-v6 §9.3）：L2→L4 / L4→L1 的**来源凭据**。
 *
 * <p>不变量 I6 的一半：{@code layer=L4} 的条目要么有 {@code memory_share}（发布授权），
 * 要么有本表的 {@code applied} 记录（提升而来）。没有凭据的 L4 条目在迁移校验里会被点名。
 */
@Repository
public class MemoryPromotionStore {

    /** 一条提升记录 */
    public record PromotionRow(long id, long itemId, String fromLayer, String toLayer, String policy,
                               int evidenceCount, String state, String note, long createdAt,
                               Long appliedAt) {
    }

    private static final String COLS = "id, item_id, from_layer, to_layer, policy, evidence_count, "
            + "state, note, created_at, applied_at";

    private final JdbcTemplate jdbc;

    public MemoryPromotionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记一条提升候选（同一 item + 目标层已有未决候选时不重复建） */
    public long add(long itemId, String fromLayer, String toLayer, String policy,
                    int evidenceCount, String note) {
        Optional<PromotionRow> existing = listByItem(itemId).stream()
                .filter(p -> "candidate".equals(p.state()) && toLayer.equals(p.toLayer()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().id();
        }
        jdbc.update("INSERT INTO memory_promotion(item_id, from_layer, to_layer, policy, "
                        + "evidence_count, state, note, created_at) VALUES(?,?,?,?,?, 'candidate', ?, ?)",
                itemId, fromLayer, toLayer, policy, evidenceCount, note, System.currentTimeMillis());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public int setState(long id, String state, boolean applied) {
        return jdbc.update("UPDATE memory_promotion SET state = ?, applied_at = ? WHERE id = ?",
                state, applied ? System.currentTimeMillis() : null, id);
    }

    public Optional<PromotionRow> findById(long id) {
        List<PromotionRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_promotion WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 某条目的全部提升记录，新的在前 */
    public List<PromotionRow> listByItem(long itemId) {
        return jdbc.query("SELECT " + COLS + " FROM memory_promotion WHERE item_id = ? ORDER BY id DESC",
                (rs, n) -> map(rs), itemId);
    }

    /** 按状态列出（管理页「提升候选」视图），新的在前 */
    public List<PromotionRow> listByState(String state, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_promotion WHERE state = ? "
                + "ORDER BY id DESC LIMIT ?", (rs, n) -> map(rs), state, Math.max(1, limit));
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM memory_promotion");
    }

    private static PromotionRow map(ResultSet rs) throws SQLException {
        long applied = rs.getLong("applied_at");
        Long appliedAt = rs.wasNull() ? null : applied;
        return new PromotionRow(rs.getLong("id"), rs.getLong("item_id"), rs.getString("from_layer"),
                rs.getString("to_layer"), rs.getString("policy"), rs.getInt("evidence_count"),
                rs.getString("state"), rs.getString("note"), rs.getLong("created_at"), appliedAt);
    }
}
