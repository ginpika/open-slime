package cc.ginpika.minis.agent.subagent;

import cc.ginpika.minis.agent.member.AgentDirectory;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Authorization shared by delegated execution, transcript projection, and report delivery. */
@Service
public class SubagentScope {
    public static final String CAPTURE = "_minis_context";
    public static final String SCOPED_PAYLOAD = """
            json_type(CASE WHEN json_valid(t.payload) THEN t.payload ELSE '{}' END, '$._minis_context') = 'object'
            """;

    /** Expects source_session(id, agent_id, project_id) with the current authorized binding. */
    public static final String VISIBLE_EXECUTIONS = """
            SELECT t.id
            FROM (SELECT task.*, CASE WHEN json_valid(payload) THEN payload ELSE '{}' END AS safe_payload FROM task) t
            JOIN source_session s ON s.id = t.session_id AND s.agent_id = t.agent_id
            JOIN task parent ON parent.id = json_extract(t.safe_payload, '$._minis_context.parent_task_id')
                AND parent.session_id = s.id AND parent.agent_id = s.agent_id AND parent.kind = 'session'
            JOIN chat_turn ct ON ct.task_id = parent.id AND ct.session_id = s.id
                AND ct.trigger_source = 'USER_INPUT'
                AND ct.id = json_extract(t.safe_payload, '$._minis_context.turn_id')
            JOIN chat_message u ON u.id = ct.trigger_message_id AND u.session_id = s.id AND u.role = 'user'
            WHERE t.kind = 'subagent' AND t.origin = 'agent'
              AND json_type(t.safe_payload, '$._minis_context') = 'object'
              AND json_extract(t.safe_payload, '$._minis_context.project_id') IS s.project_id
              AND t.status <> 'cancelled'
              AND parent.status NOT IN ('cancelled', 'interrupted')
              AND (ct.reply_message_id IS NULL OR EXISTS (
                  SELECT 1 FROM chat_message m WHERE m.id = ct.reply_message_id AND m.session_id = s.id
              ))
              AND NOT EXISTS (
                  SELECT 1 FROM memory_retracted_source r
                  WHERE r.ref IN ('task:' || t.id, 'task:' || parent.id,
                                  'chat_message:' || u.id, 'chat_message:' || ct.reply_message_id)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM task_artifact a JOIN memory_retracted_source r ON r.ref = 'task_artifact:' || a.id
                  WHERE a.task_id = t.id AND a.kind IN ('text', 'tool_result')
              )
              AND NOT EXISTS (
                  SELECT 1 FROM json_each(t.safe_payload, '$._minis_context.refs') ref
                  WHERE NOT (
                      (ref.value LIKE 'message:%' AND EXISTS (
                          SELECT 1 FROM chat_message m
                          WHERE m.id = CAST(SUBSTR(ref.value, 9) AS INTEGER) AND m.session_id = s.id
                            AND m.role IN ('user', 'assistant')
                            AND COALESCE(m.kind, '') NOT IN ('subagent', 'subagent_batch', 'steer', 'steer_reply')
                            AND NOT EXISTS (
                                SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'chat_message:' || m.id
                            )
                            AND NOT EXISTS (
                                SELECT 1 FROM chat_turn mt LEFT JOIN task tt ON tt.id = mt.task_id
                                WHERE mt.session_id = s.id AND mt.task_id IS NOT NULL
                                  AND (mt.trigger_message_id = m.id OR mt.reply_message_id = m.id)
                                  AND (tt.id IS NULL OR tt.session_id IS NOT s.id OR tt.agent_id IS NOT s.agent_id
                                      OR EXISTS (SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'task:' || tt.id))
                            )
                      )) OR (ref.value LIKE 'artifact:%' AND EXISTS (
                          SELECT 1 FROM task_artifact a JOIN task at ON at.id = a.task_id
                          JOIN chat_turn ac ON ac.task_id = at.id AND ac.session_id = s.id
                              AND ac.trigger_source = 'USER_INPUT'
                          JOIN chat_message am ON am.id = ac.trigger_message_id AND am.session_id = s.id
                          WHERE a.id = CAST(SUBSTR(ref.value, 10) AS INTEGER) AND at.kind = 'session'
                            AND at.session_id = s.id AND at.agent_id = s.agent_id
                            AND a.kind IN ('text', 'tool_result') AND a.content IS NOT NULL
                            AND (a.exec_id IS NULL OR a.exec_id = at.id)
                            AND (ac.reply_message_id IS NULL OR EXISTS (
                                SELECT 1 FROM chat_message m WHERE m.id = ac.reply_message_id AND m.session_id = s.id
                            ))
                            AND NOT EXISTS (
                                SELECT 1 FROM memory_retracted_source r WHERE r.ref IN (
                                    'task_artifact:' || a.id, 'task:' || at.id,
                                    'chat_message:' || am.id, 'chat_message:' || ac.reply_message_id)
                            )
                      ))
                  )
              )
            """;

    private static final String SESSION = """
            SELECT s.id, COALESCE(NULLIF(s.agent_id, ''), ?) AS agent_id, NULLIF(s.project_id, '') AS project_id
            FROM hub_session s WHERE s.id = ? AND s.mode = 'ai'
              AND (NULLIF(s.agent_id, '') IS NULL OR s.agent_id = ? OR EXISTS (
                  SELECT 1 FROM agent WHERE id = s.agent_id
              ))
            """;

    private static final String VISIBLE_IN_BINDING = "WITH session_binding AS (" + SESSION + """
            ), source_session AS (SELECT * FROM session_binding WHERE agent_id = ? AND project_id IS NULLIF(?, '')),
            visible AS (
            """ + VISIBLE_EXECUTIONS + ")\n";

    private final JdbcTemplate jdbc;

    public SubagentScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean scoped(long execId) {
        return exists("SELECT 1 FROM task t WHERE t.id = ? AND " + SCOPED_PAYLOAD, execId);
    }

    public boolean visible(long execId, String sessionId) {
        return exists("WITH source_session AS (" + SESSION + "), visible AS (" + VISIBLE_EXECUTIONS
                + ") SELECT 1 FROM visible WHERE id = ?", AgentDirectory.BUILTIN_ID, sessionId,
                AgentDirectory.BUILTIN_ID, execId);
    }

    public boolean visible(long execId, String sessionId, String agentId, String projectId) {
        return exists(VISIBLE_IN_BINDING + "SELECT 1 FROM visible WHERE id = ?",
                AgentDirectory.BUILTIN_ID, sessionId, AgentDirectory.BUILTIN_ID, agentId, projectId, execId);
    }

    record Report(String status, String content) {}

    /** Scope validation and report retrieval share a database snapshot. */
    Optional<Report> report(long execId, String sessionId, String agentId, String projectId) {
        return jdbc.query(VISIBLE_IN_BINDING + """
                SELECT t.status, COALESCE(a.content, '') AS content
                FROM task t JOIN source_session s ON s.id = t.session_id AND s.agent_id = t.agent_id
                LEFT JOIN task_artifact a ON a.id = (
                    SELECT MAX(id) FROM task_artifact WHERE task_id = t.id AND kind = 'text'
                ) AND t.status = 'done'
                WHERE t.id = ? AND t.origin = 'agent' AND (t.id IN (SELECT id FROM visible) OR (
                    NOT (""" + SCOPED_PAYLOAD + """
                    ) AND NOT EXISTS (SELECT 1 FROM memory_retracted_source r
                                      WHERE r.ref IN ('task:' || t.id, 'task_artifact:' || a.id))
                ))
                """, (rs, n) -> new Report(rs.getString("status"), rs.getString("content")),
                AgentDirectory.BUILTIN_ID, sessionId, AgentDirectory.BUILTIN_ID, agentId, projectId, execId)
                .stream().findFirst();
    }

    public boolean visibleLegacy(long execId, String sessionId, String agentId) {
        return exists("""
                SELECT 1 FROM task t WHERE t.id = ? AND t.session_id IS ? AND t.agent_id IS ?
                  AND NOT EXISTS (SELECT 1 FROM memory_retracted_source r WHERE r.ref = 'task:' || t.id)
                """, execId, sessionId, agentId);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.query(sql, rs -> { return rs.next(); }, args));
    }
}
