package cc.ginpika.minis.ai.memory.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 画像候选（memory_candidate）：用户画像的**唯一权威入口**（memory-v5 §5.4）。
 *
 * <p>普通 Agent 不得直接写 {@code graph_claim} 的 USER_PROFILE 域——只能生成候选，经证据/审批闸门，
 * 由 {@code UserProfileService} 落为正式画像。本类只做候选的存取，不做闸门判定。
 *
 * <p>幂等：同一 fingerprint 的 pending 候选只累加 {@code evidence_count}（追证），不重复建行。
 */
@Repository
public class MemoryCandidateStore {

    /** 一条候选 */
    public record CandidateRow(long id, String fingerprint, String userId, String owner, String scope,
                               String title, String body, String sourceKind, String evidenceLevel,
                               int evidenceCount, String evidenceRefs, String status, long createdAt,
                               long updatedAt, Long validUntil) {
    }

    private static final String COLS = "id, fingerprint, user_id, owner, scope, title, body, "
            + "source_kind, evidence_level, evidence_count, evidence_refs, status, created_at, updated_at, valid_until";

    private final JdbcTemplate jdbc;

    public MemoryCandidateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 提交一条候选：同 fingerprint 已有 pending → 仅 {@code evidence_count++} 并提升证据等级，
     * 否则新建 pending。返回该候选 id。
     */
    public long upsertPending(String fingerprint, String userId, String scope, String title, String body,
                              String sourceKind, String evidenceLevel, String evidenceRefs) {
        return upsertPending(fingerprint, userId, scope, title, body, sourceKind, evidenceLevel, evidenceRefs, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public long upsertPending(String fingerprint, String userId, String scope, String title, String body,
                              String sourceKind, String evidenceLevel, String evidenceRefs, Long validUntil) {
        Optional<CandidateRow> existing = findPendingByFingerprint(fingerprint);
        long now = System.currentTimeMillis();
        if (existing.isPresent()) {
            String merged = cc.ginpika.minis.ai.memory.MemoryEvidence.merge(existing.get().evidenceRefs(), evidenceRefs);
            int count = cc.ginpika.minis.ai.memory.MemoryEvidence.count(merged);
            String level = stronger(existing.get().evidenceLevel(), evidenceLevel);
            if (count >= 2 && "real".equals(sourceKind) && !"explicit".equals(level)) { level = "repeated"; }
            jdbc.update("UPDATE memory_candidate SET evidence_count = ?, evidence_refs = ?, "
                            + "evidence_level = ?, valid_until = ?, updated_at = ? WHERE id = ?",
                    count, merged, level, validUntil, now, existing.get().id());
            return existing.get().id();
        }
        jdbc.update("INSERT INTO memory_candidate(fingerprint, user_id, owner, scope, title, body, "
                        + "source_kind, evidence_level, evidence_count, evidence_refs, status, "
                        + "created_at, updated_at, valid_until) VALUES(?,?,?,?,?,?,?,?,?,?, 'pending', ?, ?, ?)",
                fingerprint, userId, "USER", scope, title, body, sourceKind, evidenceLevel,
                cc.ginpika.minis.ai.memory.MemoryEvidence.count(evidenceRefs), evidenceRefs, now, now, validUntil);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<CandidateRow> findPendingByFingerprint(String fingerprint) {
        List<CandidateRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_candidate WHERE fingerprint = ? "
                        + "AND status = 'pending' ORDER BY id DESC LIMIT 1",
                (rs, n) -> map(rs), fingerprint);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CandidateRow> findById(long id) {
        List<CandidateRow> rows = jdbc.query("SELECT " + COLS + " FROM memory_candidate WHERE id = ?",
                (rs, n) -> map(rs), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按状态列出候选（pending / approved / rejected；空 = 全部），新建在前 */
    public List<CandidateRow> listByStatus(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT " + COLS + " FROM memory_candidate ORDER BY id DESC",
                    (rs, n) -> map(rs));
        }
        return jdbc.query("SELECT " + COLS + " FROM memory_candidate WHERE status = ? ORDER BY id DESC",
                (rs, n) -> map(rs), status);
    }

    public int setStatus(long id, String status) {
        return jdbc.update("UPDATE memory_candidate SET status = ?, updated_at = ? WHERE id = ?",
                status, System.currentTimeMillis(), id);
    }

    /** 按来源类型计数（memory-v6 §15.4 体检用：来自创作类项目的候选是「污染被拦下」的痕迹） */
    public int countBySourceKind(String sourceKind) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_candidate WHERE source_kind = ?",
                Integer.class, sourceKind);
        return n == null ? 0 : n;
    }

    /** 按状态计数（体检用：rejected = 用户/流程已拒绝的候选） */
    public int countByStatus(String status) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_candidate WHERE status = ?",
                Integer.class, status);
        return n == null ? 0 : n;
    }

    /** 清空全部候选（设置页「重置知识库」连带清除记忆时用） */
    public int clearAll() {
        return jdbc.update("DELETE FROM memory_candidate");
    }

    /** 证据等级取强者：explicit > repeated > inferred */
    private static String stronger(String a, String b) {
        return rank(a) >= rank(b) ? a : b;
    }

    private static int rank(String level) {
        return switch (level == null ? "" : level) {
            case "explicit" -> 2;
            case "repeated" -> 1;
            default -> 0;
        };
    }

    private static CandidateRow map(ResultSet rs) throws SQLException {
        return new CandidateRow(rs.getLong("id"), rs.getString("fingerprint"), rs.getString("user_id"),
                rs.getString("owner"), rs.getString("scope"), rs.getString("title"), rs.getString("body"),
                rs.getString("source_kind"), rs.getString("evidence_level"), rs.getInt("evidence_count"),
                rs.getString("evidence_refs"), rs.getString("status"), rs.getLong("created_at"),
                rs.getLong("updated_at"), rs.getObject("valid_until") == null ? null : rs.getLong("valid_until"));
    }
}
