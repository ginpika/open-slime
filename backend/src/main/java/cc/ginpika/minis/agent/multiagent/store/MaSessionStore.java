package cc.ginpika.minis.agent.multiagent.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ma_session（多智能体会话）。
 *
 * <p>与 {@code hub_session} 完全隔离的一套会话：多智能体对话页专用，不参与首页「最近会话」，
 * 也不受会话级「…」菜单（身份版本 / CoT / 轮数上限）影响——那些旋钮属于人与单人模型的对话，
 * 圆桌上谁用什么身份、什么模型，由名册逐成员决定。
 *
 * <p>本类只读写自己的行；把行「翻译」成 {@code HubSessionStore.HubRow} 的适配放在
 * {@link cc.ginpika.minis.agent.multiagent.MaChatService}——那里才是两套会话相遇的地方。
 */
@Repository
public class MaSessionStore {

    public record SessionRow(String id, String title, String modelId, String memberIds, Long createdAt,
                             Long updatedAt) {
    }

    private static final String COLS =
            "id, title, model_id, member_ids, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public MaSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 新会话：标题留空，由首条发言截断回填。
     *
     * <p>{@code memberIds} 是这张圆桌的成员（{@code ma_peer.id} 的 JSON 数组）；传 null 表示
     * 「全体名册」——首页「和所有智能体对话」直接开说、以及加这一列之前建的旧会话都是这种。
     */
    public String insert(String memberIds) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ma_session(id, title, model_id, member_ids, created_at, updated_at) "
                + "VALUES(?,?,?,?,?,?)", id, null, null, memberIds, now, now);
        return id;
    }

    public Optional<SessionRow> findById(String id) {
        return jdbc.query("SELECT " + COLS + " FROM ma_session WHERE id = ?", MaSessionStore::map, id)
                .stream().findFirst();
    }

    /** 最近一个会话（按 COALESCE(updated_at, created_at) 倒序） */
    public Optional<SessionRow> latest() {
        return jdbc.query("SELECT " + COLS + " FROM ma_session "
                        + "ORDER BY COALESCE(updated_at, created_at) DESC LIMIT 1",
                MaSessionStore::map).stream().findFirst();
    }

    public List<SessionRow> listAll() {
        return jdbc.query("SELECT " + COLS + " FROM ma_session "
                + "ORDER BY COALESCE(updated_at, created_at) DESC", MaSessionStore::map);
    }

    /** 有新发言时刷新时间戳，并按需回填标题（首条发言截断做标题） */
    public void touch(String id, String title) {
        jdbc.update("UPDATE ma_session SET updated_at = ?, title = COALESCE(title, ?) WHERE id = ?",
                System.currentTimeMillis(), title, id);
    }

    /** 兜底模型（成员自己没指定模型时用）；null 表示不设置 */
    public void updateModel(String id, String modelId) {
        if (modelId == null) {
            return;
        }
        jdbc.update("UPDATE ma_session SET model_id = ?, updated_at = ? WHERE id = ?",
                modelId, System.currentTimeMillis(), id);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM ma_session WHERE id = ?", id);
    }

    private static SessionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("model_id"),
                rs.getString("member_ids"), MaRows.nullableLong(rs, "created_at"),
                MaRows.nullableLong(rs, "updated_at"));
    }
}
