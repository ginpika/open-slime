package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 双时态知识边（memory_edge，memory-v5 §7.1 · P4）：记忆的「关系轴」，借鉴 Zep/Graphiti。
 *
 * <p>每条边带四个时间戳（{@code valid_at / invalid_at / learned_at / expired_at}）。核心语义：
 * <b>新事实与旧事实矛盾时，关闭旧边有效期而非删除</b>（{@link #add} 内自动失效同域同主谓的旧边），
 * 于是历史始终可点查「某时刻系统知道什么」。
 *
 * <p>作用域与条目一致（owner × project × agent）：跨域绝不互相失效。
 */
@Repository
public class MemoryEdgeStore {

    /** 一条知识边 */
    public record EdgeRow(String id, String userId, String owner, String projectId, String agentId,
                          String subject, String predicate, String object, double confidence,
                          Long validAt, Long invalidAt, long learnedAt, Long expiredAt,
                          Long sourceItem, long createdAt) {
    }

    private static final String COLS = "id, user_id, owner, project_id, agent_id, subject, predicate, "
            + "object, confidence, valid_at, invalid_at, learned_at, expired_at, source_item, created_at";

    private final JdbcTemplate jdbc;

    public MemoryEdgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 加一条边并做双时态矛盾处理：同域同 (subject, predicate) 的仍有效旧边，若 object 不同（矛盾），
     * 关闭其有效期（{@code invalid_at = 本边 valid_at}，{@code expired_at = now}）。
     *
     * @param validAt 事实在世界中成立的时间（可空 = 未知，取现在）
     * @return 新边 id
     */
    public String add(String userId, String owner, String projectId, String agentId, String subject,
                      String predicate, String object, double confidence, Long validAt,
                      Long sourceItem) {
        long now = System.currentTimeMillis();
        long effectiveValidAt = validAt == null ? now : validAt;
        /* 双时态失效：同域同主谓、仍有效、且 object 不同 → 关闭其有效期（不删） */
        jdbc.update("UPDATE memory_edge SET invalid_at = ?, expired_at = ? "
                        + "WHERE owner = ? AND " + nzEq("project_id") + " AND " + nzEq("agent_id")
                        + " AND subject = ? AND predicate = ? AND invalid_at IS NULL AND object <> ? "
                        + "AND (valid_at IS NULL OR valid_at <= ?)",
                effectiveValidAt, now, owner, projectId, agentId, subject, predicate, object,
                effectiveValidAt);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO memory_edge(id, user_id, owner, project_id, agent_id, subject, "
                        + "predicate, object, confidence, valid_at, invalid_at, learned_at, expired_at, "
                        + "source_item, created_at) VALUES(?,?,?,?,?,?,?,?,?,?, NULL, ?, NULL, ?, ?)",
                id, userId, owner, projectId, agentId, subject, predicate, object, confidence,
                effectiveValidAt, now, sourceItem, now);
        return id;
    }

    /** 某主谓在给定作用域下仍有效的边（invalid_at 为空），可点查「此刻成立的事实」 */
    public List<EdgeRow> activeBySubject(String owner, String projectId, String agentId, String subject) {
        return jdbc.query("SELECT " + COLS + " FROM memory_edge WHERE owner = ? AND "
                        + nzEq("project_id") + " AND " + nzEq("agent_id")
                        + " AND subject = ? AND invalid_at IS NULL ORDER BY learned_at DESC",
                (rs, n) -> map(rs), owner, projectId, agentId, subject);
    }

    /** 全部仍有效的边（管理页 / 导出），新学到在前 */
    public List<EdgeRow> activeAll(int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_edge WHERE invalid_at IS NULL "
                + "ORDER BY learned_at DESC LIMIT ?", (rs, n) -> map(rs), limit);
    }

    /**
     * 点查「{@code at} 时刻仍有效」的边（双时态，memory-v6 §12.4）：事实世界里成立的区间
     * {@code [valid_at, invalid_at)} 覆盖该时刻即算有效——矛盾时只关闭有效期，历史照查。
     */
    public List<EdgeRow> asOf(long at, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM memory_edge "
                        + "WHERE COALESCE(valid_at, learned_at) <= ? AND (invalid_at IS NULL OR invalid_at > ?) "
                        + "ORDER BY learned_at DESC LIMIT ?",
                (rs, n) -> map(rs), at, at, Math.max(1, limit));
    }

    /** 某条目的支撑边全部失效（当支撑条目被取代 / 撤销时调用），保留历史 */
    public int invalidateBySourceItem(long itemId) {
        long now = System.currentTimeMillis();
        return jdbc.update("UPDATE memory_edge SET invalid_at = COALESCE(invalid_at, ?), expired_at = ? "
                + "WHERE source_item = ? AND invalid_at IS NULL", now, now, itemId);
    }

    /** 清空全部知识边（设置页「重置知识库」连带清除记忆时用） */
    public int clearAll() {
        return jdbc.update("DELETE FROM memory_edge");
    }

    /**
     * 用于「NULL 安全的列等值比较」：两列都为 NULL 视为相等（跨域失效保护）。
     *
     * <p>注意：这里**不能用 NUL（{@code \u0000}）当哨兵**——JDBC/SQLite 解析含 NUL 字节的 SQL 会直接
     * 报 {@code unrecognized token}（曾因此让「加知识边」整条路走不通）。本项目里 project_id / agent_id
     * 不会写成空串，故用空串做哨兵即可。
     */
    private static String nzEq(String col) {
        return "COALESCE(" + col + ", '') = COALESCE(?, '')";
    }

    private static EdgeRow map(ResultSet rs) throws SQLException {
        return new EdgeRow(rs.getString("id"), rs.getString("user_id"), rs.getString("owner"),
                rs.getString("project_id"), rs.getString("agent_id"), rs.getString("subject"),
                rs.getString("predicate"), rs.getString("object"), rs.getDouble("confidence"),
                nullableLong(rs, "valid_at"), nullableLong(rs, "invalid_at"), rs.getLong("learned_at"),
                nullableLong(rs, "expired_at"), nullableLong(rs, "source_item"),
                rs.getLong("created_at"));
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
