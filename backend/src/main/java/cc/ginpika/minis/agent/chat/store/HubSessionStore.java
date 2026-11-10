package cc.ginpika.minis.agent.chat.store;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * hub_session（AI 会话）。M0 单用户中枢：不落 owner 列；多租户化时再按用户分表/加列。
 *
 * <p>model_id / prompt_id / show_cot / context_trace / max_rounds / show_rounds 为「…」菜单在该会话的选择
 * （会话级配置）：前两者 NULL=回退设置页默认项，show_cot 缺省显示思维链，context_trace 缺省不把思考/工具调用
 * 带入上下文，max_rounds 仅保留旧数据库兼容，不再控制执行，show_rounds 缺省不在思维链分段右侧标轮次。
 *
 * <p>unread 为首页「最近会话」的未读标记：潜意识子代理往会话投递消息时置位，用户打开会话即清零。
 */
@Repository
public class HubSessionStore {

    public record HubRow(String id, String title, String mode, String modelId, String promptId,
                         String agentId, String projectId, boolean showCot, boolean contextTrace,
                         int maxRounds, boolean showRounds, boolean unread, Long createdAt,
                         Long updatedAt) {
    }

    private static final String COLS =
            "id, title, mode, model_id, prompt_id, agent_id, project_id, show_cot, context_trace, "
                    + "max_rounds, show_rounds, unread, created_at, updated_at";

    private static final String APPROVALS_TO_RETIRE = """
            session_id = ? AND status = 'pending'
              AND (? = 1 OR scope_revision <> (SELECT COALESCE(context_scope_revision, 0)
                  FROM hub_session WHERE id = chat_todo_approval.session_id))
            """;

    private final JdbcTemplate jdbc;

    public HubSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String hubId, String title) {
        insert(hubId, title, null);
    }

    /** 新建会话（可绑定项目）：projectId 为空 = 无项目（记忆归属退化为 Agent 私有） */
    public void insert(String hubId, String title, String projectId) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO hub_session(id, title, mode, project_id, created_at, updated_at) "
                        + "VALUES(?,?, 'ai', ?, ?, ?)",
                hubId, title, projectId, now, now);
    }

    /** 绑定 / 解绑会话所属项目 */
    @Transactional
    public int setProject(String hubId, String projectId) {
        int updated = jdbc.update("""
                UPDATE hub_session SET project_id = ?, updated_at = ?,
                    context_scope_revision = CASE WHEN NULLIF(project_id, '') IS NOT NULLIF(?, '')
                        THEN COALESCE(context_scope_revision, 0) + 1 ELSE context_scope_revision END,
                    context_stage = CASE WHEN NULLIF(project_id, '') IS NULLIF(?, '') THEN context_stage END
                WHERE id = ?
                """, projectId, System.currentTimeMillis(), projectId, projectId, hubId);
        if (updated > 0) {
            retireTodoApprovals(hubId, false);
        }
        return updated;
    }

    /** 回退后的执行不能继续使用回退前的续接作用域；各任务段自己的续接一并失效（R1）。 */
    @Transactional
    public void invalidateContextStage(String hubId) {
        int updated = jdbc.update("""
                UPDATE hub_session SET context_scope_revision = COALESCE(context_scope_revision, 0) + 1,
                    context_stage = NULL WHERE id = ? AND mode = 'ai'
                """, hubId);
        jdbc.update("UPDATE chat_scene SET stage = NULL WHERE session_id = ?", hubId);
        if (updated > 0) {
            retireTodoApprovals(hubId, false);
        }
    }

    /** 会话当前的续接授权代号（R7 Manifest 对照）；不存在按 0。 */
    public long contextScopeRevision(String hubId) {
        List<Long> rows = jdbc.query(
                "SELECT COALESCE(context_scope_revision, 0) FROM hub_session WHERE id = ?",
                (rs, n) -> rs.getLong(1), hubId);
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    /** 有新消息时刷新 updated_at，并按需回填标题（首条消息截断做标题） */
    public void touch(String hubId, String title) {
        jdbc.update(
                "UPDATE hub_session SET updated_at = ?, title = COALESCE(title, ?) WHERE id = ?",
                System.currentTimeMillis(), title, hubId);
    }

    /** 更新会话配置（「…」菜单选择）：仅更新非 null 项，未传的保持原值 */
    @Transactional
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
            String newAgent = agentId.isBlank() ? null : agentId;
            sets.add("agent_id = ?");
            args.add(newAgent);
            String owner = BuiltinAgents.CORE.equals(newAgent) ? null : newAgent;
            sets.add("""
                    context_scope_revision = CASE WHEN NULLIF(NULLIF(agent_id, ''), ?) IS NOT ?
                        THEN COALESCE(context_scope_revision, 0) + 1 ELSE context_scope_revision END
                    """);
            args.add(BuiltinAgents.CORE);
            args.add(owner);
            sets.add("context_stage = CASE WHEN NULLIF(NULLIF(agent_id, ''), ?) IS ? THEN context_stage END");
            args.add(BuiltinAgents.CORE);
            args.add(owner);
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
        int updated = jdbc.update("UPDATE hub_session SET " + String.join(", ", sets) + " WHERE id = ?",
                args.toArray());
        if (agentId != null && updated > 0) {
            retireTodoApprovals(hubId, false);
        }
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
    @Transactional
    public int delete(String hubId) {
        retireTodoApprovals(hubId, true);
        return jdbc.update("DELETE FROM hub_session WHERE id = ?", hubId);
    }

    /** Keep approvals from an earlier scope terminal even if the session is later rebound to the same owner. */
    private void retireTodoApprovals(String hubId, boolean deleting) {
        int all = deleting ? 1 : 0;
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE task SET status = 'cancelled', updated_at = ?
                WHERE session_id = ? AND kind = 'session' AND status = 'suspended'
                  AND id IN (SELECT operation_id FROM chat_todo_approval WHERE
                """ + APPROVALS_TO_RETIRE + ")", now, hubId, hubId, all);
        if (deleting) {
            jdbc.update("DELETE FROM chat_todo_approval WHERE session_id = ?", hubId);
        } else {
            jdbc.update("UPDATE chat_todo_approval SET status = 'stale', decision = NULL, decided_at = ? WHERE "
                    + APPROVALS_TO_RETIRE, now, hubId, all);
        }
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

    /** 临时会话（不进记忆）：1=本次不写 memory_event、不提炼。老库该列为 NULL 时视作 0 */
    public boolean memoryOff(String hubId) {
        Integer v = jdbc.query("SELECT memory_off FROM hub_session WHERE id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, hubId);
        return v != null && v != 0;
    }

    /** 运行时切换「本次不记入记忆」（会话「…」菜单） */
    public void setMemoryOff(String hubId, boolean off) {
        jdbc.update("UPDATE hub_session SET memory_off = ? WHERE id = ?", off ? 1 : 0, hubId);
    }

    /**
     * 仅在标题仍为空时写标题：自动生成的标题不覆盖用户手动命名 / 已生成的标题。
     *
     * <p>与 {@link #touch} 的 {@code COALESCE} 同一语义，但显式收窄到「空串也算空」——
     * 因为自动命名可能晚于首条消息到达，届时不能把已有的占位标题顶掉。
     */
    public void setTitleIfAbsent(String hubId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        jdbc.update("UPDATE hub_session SET title = ?, updated_at = ? "
                        + "WHERE id = ? AND (title IS NULL OR title = '')",
                title.trim(), System.currentTimeMillis(), hubId);
    }

    private static HubRow map(ResultSet rs, int rowNum) throws SQLException {
        return new HubRow(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("mode"),
                rs.getString("model_id"),
                rs.getString("prompt_id"),
                rs.getString("agent_id"),
                rs.getString("project_id"),
                rs.getInt("show_cot") != 0,
                rs.getInt("context_trace") != 0,
                0,
                rs.getInt("show_rounds") != 0,
                rs.getInt("unread") != 0,
                (Long) rs.getObject("created_at"),
                (Long) rs.getObject("updated_at"));
    }
}
