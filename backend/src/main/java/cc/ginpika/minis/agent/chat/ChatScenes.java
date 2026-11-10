package cc.ginpika.minis.agent.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务段（R1 · chat_scene）：同一会话内显式切换工作内容的机制。
 *
 * <p>三个动作——开始任务、结束回到普通聊天、恢复已有任务——只在安全点提交（生成中拒绝），
 * 每次切换显示当前任务名。开始时可填写名称与可选的临时角色或任务约束；临时角色写入任务段，
 * 切换不修改用户有意设置的全局 Agent 提示词。
 *
 * <p>段落规则：Turn 开始时捕获任务段（chat_turn.scene_id）；结束任务即开启新的普通工作段
 * （context_segment_message_id 边界），此前的普通段与刚结束的任务段都不再默认回放；
 * 恢复任务时只重新装配该任务段自己的 Turn 与仍有效的续接，退出期间的其他过程不自动进入。
 * 模型可以建议切换，话题分类和模型文字本身均不能改变活动任务——本类不提供任何模型工具入口。
 */
@Service
public class ChatScenes {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatScenes.class);

    /** 一条任务段 */
    public record SceneRow(long id, String sessionId, String name, String rolePrompt,
                           String constraints, String status, long createdAt, long updatedAt) {
    }

    /** 界面状态：当前活动段、可恢复段与普通段边界 */
    public record SceneState(SceneRow active, List<SceneRow> resumable) {
    }

    private static final String COLS = "id, session_id, name, role_prompt, constraints, status, "
            + "created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ChatLiveStore live;
    private final SessionEventBus events;

    public ChatScenes(JdbcTemplate jdbc, ChatLiveStore live, SessionEventBus events) {
        this.jdbc = jdbc;
        this.live = live;
        this.events = events;
    }

    public SceneState state(String hubId) {
        requireSession(hubId);
        List<SceneRow> rows = jdbc.query(
                "SELECT " + COLS + " FROM chat_scene WHERE session_id = ? ORDER BY id DESC LIMIT 100",
                ChatScenes::map, hubId);
        SceneRow active = rows.stream().filter(row -> "active".equals(row.status())).findFirst().orElse(null);
        List<SceneRow> resumable = rows.stream().filter(row -> "ended".equals(row.status())).toList();
        return new SceneState(active, resumable);
    }

    /** 开始一段任务：无活动任务段、且不在生成中才可开始。 */
    @Transactional
    public SceneRow start(String hubId, String name, String rolePrompt, String constraints) {
        requireSession(hubId);
        requireIdle(hubId);
        if (active(hubId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有进行中的任务段，请先结束再开始新任务");
        }
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO chat_scene(session_id, name, role_prompt, constraints, status, "
                        + "created_at, updated_at) VALUES(?,?,?,?, 'active', ?, ?)",
                hubId, clip(name, 60), clip(rolePrompt, 2000), clip(constraints, 2000), now, now);
        long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("UPDATE hub_session SET active_scene_id = ?, updated_at = ? WHERE id = ?", id, now, hubId);
        SceneRow row = find(hubId, id);
        publish(hubId);
        log.info("[ctx] scene.start session={} scene={} name={} role={} constraints={}", hubId, id,
                row == null ? "-" : row.name(), row != null && row.rolePrompt() != null,
                row != null && row.constraints() != null);
        return row;
    }

    /** 结束任务段：开启新的普通工作段，此前的普通段与刚结束的任务段不再默认回放。 */
    @Transactional
    public SceneRow end(String hubId, long sceneId) {
        requireSession(hubId);
        requireIdle(hubId);
        SceneRow row = requireActive(hubId, sceneId);
        long now = System.currentTimeMillis();
        /* 普通段边界 = 当前最大消息 id：只有之后的新消息属于当前普通段 */
        Long boundary = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE session_id = ?", Long.class, hubId);
        jdbc.update("UPDATE chat_scene SET status = 'ended', updated_at = ? WHERE id = ? AND status = 'active'",
                now, sceneId);
        jdbc.update("UPDATE hub_session SET active_scene_id = NULL, context_segment_message_id = ?, "
                + "updated_at = ? WHERE id = ? AND active_scene_id = ?", boundary, now, hubId, sceneId);
        publish(hubId);
        log.info("[ctx] scene.end session={} scene={} boundary={}", hubId, sceneId, boundary);
        return new SceneRow(row.id(), row.sessionId(), row.name(), row.rolePrompt(), row.constraints(),
                "ended", row.createdAt(), now);
    }

    /** 恢复已有任务段：仍有效的约束与续接重新装配，退出任务期间的其他过程不自动进入。 */
    @Transactional
    public SceneRow resume(String hubId, long sceneId) {
        requireSession(hubId);
        requireIdle(hubId);
        if (active(hubId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有进行中的任务段，请先结束再恢复其他任务");
        }
        SceneRow row = find(hubId, sceneId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务段不存在或不属于该会话");
        }
        if (!"ended".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务段不在可恢复状态");
        }
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE chat_scene SET status = 'active', updated_at = ? WHERE id = ?", now, sceneId);
        jdbc.update("UPDATE hub_session SET active_scene_id = ?, updated_at = ? WHERE id = ?", sceneId, now, hubId);
        publish(hubId);
        log.info("[ctx] scene.resume session={} scene={} name={}", hubId, sceneId, row.name());
        return new SceneRow(row.id(), row.sessionId(), row.name(), row.rolePrompt(), row.constraints(),
                "active", row.createdAt(), now);
    }

    /** 会话当前活动任务段；无则 null。 */
    public SceneRow active(String hubId) {
        List<SceneRow> rows = jdbc.query("SELECT " + COLS + " FROM chat_scene WHERE session_id = ? "
                + "AND status = 'active' ORDER BY id DESC LIMIT 1", ChatScenes::map, hubId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 普通段边界（message id 大于边界者属于当前普通段）；NULL 视为 0（旧会话全部入选）。 */
    public long segmentBoundary(String hubId) {
        List<Long> rows = jdbc.query(
                "SELECT COALESCE(context_segment_message_id, 0) FROM hub_session WHERE id = ?",
                (rs, n) -> rs.getLong(1), hubId);
        return rows.isEmpty() ? 0L : rows.getFirst();
    }

    /** 单聊 Turn 应归属的任务段 id（开始时捕获）；活动段不存在时为 null（普通段）。 */
    public Long activeSceneId(String hubId) {
        List<Long> rows = jdbc.query(
                "SELECT active_scene_id FROM hub_session WHERE id = ? AND active_scene_id IS NOT NULL "
                        + "AND EXISTS (SELECT 1 FROM chat_scene s WHERE s.id = hub_session.active_scene_id "
                        + "AND s.session_id = hub_session.id AND s.status = 'active')",
                (rs, n) -> rs.getLong(1), hubId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SceneRow find(String hubId, long sceneId) {
        List<SceneRow> rows = jdbc.query("SELECT " + COLS + " FROM chat_scene WHERE session_id = ? AND id = ?",
                ChatScenes::map, hubId, sceneId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private SceneRow requireActive(String hubId, long sceneId) {
        SceneRow row = find(hubId, sceneId);
        if (row == null || !"active".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该任务段不是当前活动任务段");
        }
        return row;
    }

    private void requireSession(String hubId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hub_session WHERE id = ? AND mode = 'ai'", Integer.class, hubId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "单聊会话不存在: " + hubId);
        }
    }

    /** 切换只在安全点提交：生成中先让用户结束或取消当前轮。 */
    private void requireIdle(String hubId) {
        if (live.isRunning(hubId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话正在生成中，请先结束或取消当前轮再切换任务");
        }
    }

    private void publish(String hubId) {
        try {
            events.publish(new SessionEventBus.Event(hubId, SessionEventBus.KIND_SCENE, false, null));
        } catch (RuntimeException error) {
            log.warn("任务段切换事件发送失败: session={}", hubId, error);
        }
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.codePointCount(0, text.length()) <= max ? text
                : text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static SceneRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SceneRow(rs.getLong("id"), rs.getString("session_id"), rs.getString("name"),
                rs.getString("role_prompt"), rs.getString("constraints"), rs.getString("status"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
