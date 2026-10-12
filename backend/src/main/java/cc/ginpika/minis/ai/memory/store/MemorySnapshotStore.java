package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.memory.MemorySnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 快照回放（memory_snapshot，memory-v6 §4.3 / §12.4）：保存条目引用及完整版本正文、工作集与执行作用域。
 *
 * <p>回答「这一轮模型到底看见了什么、为什么是这些」——排障记忆污染 / 漂移时的第一手证据；
 * 也是「本轮写入不回改本轮注入」的取证依据（水位可对比写入前后的条目 id）。
 */
@Repository
public class MemorySnapshotStore {

    /** 一条回放记录 */
    public record SnapshotRow(String turnId, String sessionId, String agentId, String projectId,
                              long watermark, List<Long> itemIds, int tokens, long builtAt, String contentJson) {
    }

    private final JdbcTemplate jdbc;

    public MemorySnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前变更水位：memory_revision 的 max(id)（空库 = 0） */
    public long watermark() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM memory_revision", Long.class);
        return max == null ? 0L : max;
    }

    /** 落一条回放记录（同 turnId 覆盖；条目列表序列化为逗号分隔的 id） */
    public void save(MemorySnapshot snapshot, int tokens) {
        if (snapshot == null || snapshot.turnId() == null || snapshot.turnId().isBlank()) {
            return;
        }
        jdbc.update("INSERT OR REPLACE INTO memory_snapshot(turn_id, session_id, agent_id, "
                        + "project_id, watermark, item_ids, tokens, built_at, content_json) VALUES(?,?,?,?,?,?,?,?,?)",
                snapshot.turnId(), nz(snapshot.exec() == null ? null : snapshot.exec().sessionId()),
                nz(snapshot.exec() == null ? null : snapshot.exec().agentId()),
                nz(snapshot.exec() == null ? null : snapshot.exec().projectId()),
                snapshot.watermark(), join(snapshot.itemIds()), tokens, snapshot.builtAt(), serialize(snapshot));
    }

    public Optional<SnapshotRow> find(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return Optional.empty();
        }
        List<SnapshotRow> rows = jdbc.query("SELECT turn_id, session_id, agent_id, project_id, "
                        + "watermark, item_ids, tokens, built_at, content_json FROM memory_snapshot WHERE turn_id = ?",
                (rs, n) -> map(rs), turnId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 某会话最近若干轮的回放记录（管理页「快照回放」用），新的在前 */
    public List<SnapshotRow> recentBySession(String sessionId, int limit) {
        return jdbc.query("SELECT turn_id, session_id, agent_id, project_id, watermark, item_ids, "
                        + "tokens, built_at, content_json FROM memory_snapshot WHERE session_id = ? "
                        + "ORDER BY built_at DESC LIMIT ?",
                (rs, n) -> map(rs), nz(sessionId), Math.max(1, limit));
    }

    public int clearAll() {
        return jdbc.update("DELETE FROM memory_snapshot");
    }

    public List<SnapshotRow> recent(String agentId, String projectId, String sessionId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT turn_id, session_id, agent_id, project_id, watermark, "
                + "item_ids, tokens, built_at, content_json FROM memory_snapshot WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND COALESCE(NULLIF(agent_id,''),'builtin')=?"); args.add(agentId);
        }
        if (projectId != null && !projectId.isBlank()) { sql.append(" AND project_id=?"); args.add(projectId); }
        if (sessionId != null && !sessionId.isBlank()) { sql.append(" AND session_id=?"); args.add(sessionId); }
        sql.append(" ORDER BY built_at DESC, turn_id DESC LIMIT ?"); args.add(Math.max(1,Math.min(limit,100)));
        return jdbc.query(sql.toString(), (rs,n)->map(rs), args.toArray());
    }

    private static String join(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static List<Long> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (String part : Arrays.asList(text.split(","))) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                /* 坏值跳过：回放是审计视图，不该因一条脏数据整页失败 */
            }
        }
        return out;
    }

    private static SnapshotRow map(ResultSet rs) throws SQLException {
        return new SnapshotRow(rs.getString("turn_id"), rs.getString("session_id"),
                rs.getString("agent_id"), rs.getString("project_id"), rs.getLong("watermark"),
                split(rs.getString("item_ids")), rs.getInt("tokens"), rs.getLong("built_at"), rs.getString("content_json"));
    }

    private static String serialize(MemorySnapshot snapshot) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(snapshot); }
        catch (Exception e) { throw new IllegalStateException("快照无法序列化", e); }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
