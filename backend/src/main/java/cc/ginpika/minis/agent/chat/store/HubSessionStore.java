package cc.ginpika.minis.agent.chat.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * hub_session（AI 会话）。M0 单用户中枢：不落 owner 列；多租户化时再按用户分表/加列。
 *
 * <p>model_id / prompt_id / show_cot / context_trace / max_rounds / show_rounds 为「…」菜单在该会话的选择
 * （会话级配置）：前两者 NULL=回退设置页默认项，show_cot 缺省显示思维链，context_trace 缺省不把思考/工具调用
 * 带入上下文，max_rounds 为单次提问的工具调用轮数上限（缺省 30），show_rounds 缺省不在思维链分段右侧标轮次。
 *
 * <p>unread 为首页「最近会话」的未读标记：潜意识子代理往会话投递消息时置位，用户打开会话即清零。
 */
@Repository
public class HubSessionStore {

    public record HubRow(String id, String title, String mode, String modelId, String promptId,
                         String agentId, boolean showCot, boolean contextTrace, int maxRounds,
                         boolean showRounds, boolean unread, Long createdAt, Long updatedAt) {
    }

    private static final String COLS =
            "id, title, mode, model_id, prompt_id, agent_id, show_cot, context_trace, max_rounds, "
                    + "show_rounds, unread, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public HubSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String hubId, String title) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO hub_session(id, title, mode, created_at, updated_at) VALUES(?,?, 'ai', ?, ?)",
                hubId, title, now, now);
    }

    /** 有新消息时刷新 updated_at，并按需回填标题（首条消息截断做标题） */
    public void touch(String hubId, String title) {
        jdbc.update(
                "UPDATE hub_session SET updated_at = ?, title = COALESCE(title, ?) WHERE id = ?",
                System.currentTimeMillis(), title, hubId);
    }

    /** 更新会话配置（「…」菜单选择）：仅更新非 null 项，未传的保持原值 */
    public void updateConfig(String hubId, String modelId, String promptId, String agentId, Boolean showCot,
                             Boolean contextTrace, Integer maxRounds, Boolean showRounds) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (modelId != null) {
            sets.add("model_id = ?");
            args.add(modelId);
        }
        if (promptId != null) {
            sets.add("prompt_id = ?");
            args.add(promptId);
        }
        if (agentId != null) {
            /* 空串 = 清除，回到内置 Agent（与 model_id/prompt_id 的「空=回退默认」同口径，落 NULL 更干净） */
            sets.add("agent_id = ?");
            args.add(agentId.isBlank() ? null : agentId);
        }
        if (showCot != null) {
            sets.add("show_cot = ?");
            args.add(showCot ? 1 : 0);
        }
        if (contextTrace != null) {
            sets.add("context_trace = ?");
            args.add(contextTrace ? 1 : 0);
        }
        if (maxRounds != null) {
            sets.add("max_rounds = ?");
            args.add(maxRounds);
        }
        if (showRounds != null) {
            sets.add("show_rounds = ?");
            args.add(showRounds ? 1 : 0);
        }
        if (sets.isEmpty()) {
            return;
        }
        args.add(hubId);
        jdbc.update("UPDATE hub_session SET " + String.join(", ", sets) + " WHERE id = ?",
                args.toArray());
    }

    public Optional<HubRow> findById(String hubId) {
        List<HubRow> rows = jdbc.query(
                "SELECT " + COLS + " FROM hub_session WHERE id = ?",
                HubSessionStore::map, hubId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 最近一个首页会话（按 COALESCE(updated_at, created_at) 倒序）；圆桌会话（mode='multi-agent'）不算 */
    public Optional<HubRow> latest() {
        List<HubRow> rows = jdbc.query(
                "SELECT " + COLS + " FROM hub_session WHERE mode = 'ai' "
                        + "ORDER BY COALESCE(updated_at, created_at) DESC LIMIT 1",
                HubSessionStore::map);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 首页「最近会话」列表：只含 mode='ai' 的会话（圆桌由圆桌页单独查，见 RoundtableSessionStore） */
    public List<HubRow> listAll() {
        return jdbc.query(
                "SELECT " + COLS + " FROM hub_session WHERE mode = 'ai' "
                        + "ORDER BY COALESCE(updated_at, created_at) DESC",
                HubSessionStore::map);
    }

    /** 删除会话行；返回受影响行数（0 = 不存在）。消息正本须由调用方先行清理 */
    public int delete(String hubId) {
        return jdbc.update("DELETE FROM hub_session WHERE id = ?", hubId);
    }

    /** 按标题查会话（同名可能有多条，取最近更新的一条） */
    public Optional<HubRow> findByTitle(String title) {
        List<HubRow> rows = jdbc.query(
                "SELECT " + COLS + " FROM hub_session WHERE title = ? "
                        + "ORDER BY COALESCE(updated_at, created_at) DESC LIMIT 1",
                HubSessionStore::map, title);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 同名复用、无则新建：返回会话 id（潜意识子代理按会话名投递时的落点） */
    public String openOrCreate(String title) {
        Optional<HubRow> existing = findByTitle(title);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        String hubId = UUID.randomUUID().toString();
        insert(hubId, title);
        return hubId;
    }

    /** 标记会话有未读投递（首页「最近会话」打点） */
    public void markUnread(String hubId) {
        jdbc.update("UPDATE hub_session SET unread = 1 WHERE id = ?", hubId);
    }

    /** 清零未读（用户打开会话查看时） */
    public void clearUnread(String hubId) {
        jdbc.update("UPDATE hub_session SET unread = 0 WHERE id = ?", hubId);
    }

    private static HubRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HubRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("mode"),
                rs.getString("model_id"),
                rs.getString("prompt_id"),
                rs.getString("agent_id"),
                rs.getInt("show_cot") != 0,
                rs.getInt("context_trace") != 0,
                rs.getInt("max_rounds"),
                rs.getInt("show_rounds") != 0,
                rs.getInt("unread") != 0,
                (Long) rs.getObject("created_at"),
                (Long) rs.getObject("updated_at"));
    }
}
