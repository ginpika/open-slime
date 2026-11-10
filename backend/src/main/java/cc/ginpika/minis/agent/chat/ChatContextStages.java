package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.core.BuiltinAgents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists same-session continuation references and the optional history cutoff. */
@Repository
public class ChatContextStages {
    private static final String SCOPED_TURNS = """
            WITH session AS (
                SELECT id, mode, COALESCE(context_scope_revision, 0) revision, context_stage,
                       COALESCE(NULLIF(agent_id, ''), ?) agent_id
                FROM hub_session WHERE id = ?
            ), visible_triggers AS NOT MATERIALIZED (
                SELECT u.id
                FROM session s JOIN chat_message u ON u.session_id = s.id
                LEFT JOIN chat_turn ct ON ct.trigger_message_id = u.id AND ct.session_id = s.id
                LEFT JOIN task t ON t.id = ct.task_id
                WHERE s.mode = 'ai' AND u.role = 'user' AND u.kind IS NOT 'steer'
                  AND (ct.task_id IS NULL OR (t.session_id = s.id AND t.agent_id = s.agent_id))
                  AND NOT EXISTS (
                      SELECT 1 FROM memory_retracted_source r
                      WHERE r.ref IN ('chat_message:' || u.id, 'task:' || t.id)
                  )
            ), completed_turns AS NOT MATERIALIZED (
                SELECT ct.id, ct.trigger_message_id
                FROM session s JOIN chat_turn ct ON ct.session_id = s.id
                JOIN visible_triggers u ON u.id = ct.trigger_message_id
                JOIN task t ON t.id = ct.task_id AND t.session_id = s.id AND t.agent_id = s.agent_id
                JOIN chat_message a ON a.id = ct.reply_message_id AND a.session_id = s.id
                WHERE ct.trigger_source = 'USER_INPUT' AND t.kind = 'session'
                  AND t.status = 'done' AND a.role = 'assistant'
                  AND NOT EXISTS (
                      SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'chat_message:' || a.id
                  )
            )
            """;

    // Both reads and writes verify the stage and epoch in the same database snapshot.
    private static final String ACCEPTED_STAGE = """
            , accepted_stage AS (
                SELECT c.payload
                FROM candidate c
                JOIN completed_turns stage ON stage.id = json_extract(c.payload, '$.turnId')
                    AND stage.trigger_message_id = json_extract(c.payload, '$.triggerId')
                LEFT JOIN completed_turns epoch ON epoch.id = json_extract(c.payload, '$.epochTurnId')
                LEFT JOIN visible_triggers cutoff ON cutoff.id = json_extract(c.payload, '$.cutoff')
                WHERE (json_extract(c.payload, '$.epochTurnId') = 0 AND json_extract(c.payload, '$.cutoff') = 0)
                   OR (cutoff.id <= stage.trigger_message_id AND stage.trigger_message_id < epoch.trigger_message_id)
            )
            """;

    /** R1：任务段视角的同一套校验——可见触发与已完成 Turn 都限定在该任务段内，stage 存 chat_scene.stage。 */
    private static final String SCOPED_SCENE_TURNS = """
            WITH session AS (
                SELECT s.id, s.mode, COALESCE(s.context_scope_revision, 0) revision, c.stage AS context_stage,
                       COALESCE(NULLIF(s.agent_id, ''), ?) agent_id
                FROM hub_session s JOIN chat_scene c ON c.session_id = s.id
                WHERE c.id = ? AND c.session_id = ?
            ), visible_triggers AS NOT MATERIALIZED (
                SELECT u.id
                FROM session s JOIN chat_message u ON u.session_id = s.id
                JOIN chat_turn ct ON ct.trigger_message_id = u.id AND ct.session_id = s.id AND ct.scene_id = ?
                LEFT JOIN task t ON t.id = ct.task_id
                WHERE s.mode = 'ai' AND u.role = 'user' AND u.kind IS NOT 'steer'
                  AND (ct.task_id IS NULL OR (t.session_id = s.id AND t.agent_id = s.agent_id))
                  AND NOT EXISTS (
                      SELECT 1 FROM memory_retracted_source r
                      WHERE r.ref IN ('chat_message:' || u.id, 'task:' || t.id)
                  )
            ), completed_turns AS NOT MATERIALIZED (
                SELECT ct.id, ct.trigger_message_id
                FROM session s JOIN chat_turn ct ON ct.session_id = s.id AND ct.scene_id = ?
                JOIN visible_triggers u ON u.id = ct.trigger_message_id
                JOIN task t ON t.id = ct.task_id AND t.session_id = s.id AND t.agent_id = s.agent_id
                JOIN chat_message a ON a.id = ct.reply_message_id AND a.session_id = s.id
                WHERE ct.trigger_source = 'USER_INPUT' AND t.kind = 'session'
                  AND t.status = 'done' AND a.role = 'assistant'
                  AND NOT EXISTS (
                      SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'chat_message:' || a.id
                  )
            )
            """;

    public record Excerpt(String kind, String source, int offset, int length, String sha256) {}

    public record Stage(long turnId, long triggerId, long epochTurnId, long cutoff, List<Excerpt> excerpts) {
        public Stage {
            excerpts = List.copyOf(excerpts);
        }
    }

    public record State(long revision, Stage stage) {}

    private record Stored(long revision, String raw, Stage stage, long completingTrigger) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public ChatContextStages(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    public State load(String sessionId) {
        Stored row = read(sessionId, 0);
        return row == null ? null : new State(row.revision(), row.stage());
    }

    public boolean current(String sessionId, long revision) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM hub_session WHERE id = ? AND mode = 'ai' AND COALESCE(context_scope_revision, 0) = ?
                """, rs -> { return rs.next(); }, sessionId, revision));
    }

    /** R1：任务段自己的续接（每任务段一份）；授权代号仍取所属会话的 context_scope_revision。 */
    public State loadScene(long sceneId, String sessionId) {
        Stored row = readScene(sceneId, sessionId, 0);
        return row == null ? null : new State(row.revision(), row.stage());
    }

    /** R1：只有该任务段内成功完成的用户 Turn 才能发布任务段续接或推进其水位。 */
    public void settleScene(long sceneId, String sessionId, long revision, long turnId,
                            List<Excerpt> staged, long cutoff) {
        if (staged == null && cutoff <= 0) {
            return;
        }
        Stored row = readScene(sceneId, sessionId, turnId);
        if (row == null || row.revision() != revision || row.completingTrigger() <= 0) {
            return;
        }
        Stage previous = row.stage();
        if (previous != null && turnId <= Math.max(previous.turnId(), previous.epochTurnId())) {
            return;
        }
        if (staged == null && (previous == null || cutoff <= previous.cutoff())) {
            return;
        }
        Stage next = staged == null
                ? new Stage(previous.turnId(), previous.triggerId(), turnId, cutoff, previous.excerpts())
                : new Stage(turnId, row.completingTrigger(), 0, 0, staged);
        String payload = serialize(next);
        jdbc.update(SCOPED_SCENE_TURNS + ", candidate AS (SELECT ? AS payload)" + ACCEPTED_STAGE + """
                UPDATE chat_scene
                SET stage = (SELECT payload FROM accepted_stage), updated_at = ?
                WHERE id = ? AND session_id = (SELECT id FROM session WHERE revision = ? AND context_stage IS ?)
                  AND EXISTS (SELECT 1 FROM accepted_stage)
                  AND EXISTS (SELECT 1 FROM completed_turns WHERE id = ?)
                """, BuiltinAgents.CORE, sceneId, sessionId, sceneId, sceneId, payload,
                System.currentTimeMillis(), sceneId, revision, row.raw(), turnId);
    }

    private Stored readScene(long sceneId, String sessionId, long completingTurn) {
        return jdbc.query(SCOPED_SCENE_TURNS + """
                , candidate AS (
                    SELECT CASE WHEN json_valid(context_stage) THEN context_stage END AS payload FROM session
                )
                """ + ACCEPTED_STAGE + """
                SELECT revision, context_stage, (SELECT payload FROM accepted_stage) AS accepted,
                       (SELECT trigger_message_id FROM completed_turns WHERE id = ?) AS completing_trigger
                FROM session
                """, rs -> rs.next() ? new Stored(rs.getLong("revision"), rs.getString("context_stage"),
                        deserialize(rs.getString("accepted")), rs.getLong("completing_trigger")) : null,
                BuiltinAgents.CORE, sceneId, sessionId, sceneId, sceneId, completingTurn);
    }

    /** Only a completed user turn in the captured scope can publish a snapshot or start an epoch. */
    public void settle(String sessionId, long revision, long turnId, List<Excerpt> staged, long cutoff) {
        if (staged == null && cutoff <= 0) {
            return;
        }
        Stored row = read(sessionId, turnId);
        if (row == null || row.revision() != revision || row.completingTrigger() <= 0) {
            return;
        }
        Stage previous = row.stage();
        if (previous != null && turnId <= Math.max(previous.turnId(), previous.epochTurnId())) {
            return;
        }
        if (staged == null && (previous == null || cutoff <= previous.cutoff())) {
            return;
        }
        Stage next = staged == null
                ? new Stage(previous.turnId(), previous.triggerId(), turnId, cutoff, previous.excerpts())
                : new Stage(turnId, row.completingTrigger(), 0, 0, staged);
        String payload = serialize(next);
        jdbc.update(SCOPED_TURNS + ", candidate AS (SELECT ? AS payload)" + ACCEPTED_STAGE + """
                UPDATE hub_session
                SET context_stage = (SELECT payload FROM accepted_stage)
                WHERE id = (SELECT id FROM session WHERE revision = ? AND context_stage IS ?)
                  AND EXISTS (SELECT 1 FROM accepted_stage)
                  AND EXISTS (SELECT 1 FROM completed_turns WHERE id = ?)
                """, BuiltinAgents.CORE, sessionId, payload, revision, row.raw(), turnId);
    }

    private Stored read(String sessionId, long completingTurn) {
        return jdbc.query(SCOPED_TURNS + """
                , candidate AS (
                    SELECT CASE WHEN json_valid(context_stage) THEN context_stage END AS payload FROM session
                )
                """ + ACCEPTED_STAGE + """
                SELECT revision, context_stage, (SELECT payload FROM accepted_stage) AS accepted,
                       (SELECT trigger_message_id FROM completed_turns WHERE id = ?) AS completing_trigger
                FROM session
                """, rs -> rs.next() ? new Stored(rs.getLong("revision"), rs.getString("context_stage"),
                        deserialize(rs.getString("accepted")), rs.getLong("completing_trigger")) : null,
                BuiltinAgents.CORE, sessionId, completingTurn);
    }

    private String serialize(Stage stage) {
        try {
            return om.writeValueAsString(stage);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存续接元数据", e);
        }
    }

    private Stage deserialize(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return om.readValue(payload, Stage.class);
        } catch (JsonProcessingException invalid) {
            return null;
        }
    }
}
