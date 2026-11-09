package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.agent.runtime.TodoStore.CompletionTarget;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import cc.ginpika.minis.ai.core.BuiltinAgents;
import cc.ginpika.minis.ai.core.tool.chat.RequestTodoCompletionTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** A persisted approval safe point for completing one item from the personal-chat checklist. */
@Service
public class ChatTodoApprovals {
    private static final Logger log = LoggerFactory.getLogger(ChatTodoApprovals.class);

    private static final String VISIBLE_APPROVALS = """
            SELECT a.*, k.status AS task_status FROM chat_todo_approval a
            JOIN hub_session s ON s.id = a.session_id
            JOIN task k ON k.id = a.operation_id AND k.session_id = s.id AND k.agent_id = a.agent_id
            JOIN chat_turn t ON t.id = a.turn_id AND t.session_id = s.id AND t.task_id = k.id
            JOIN chat_message u ON u.id = a.trigger_message_id AND u.id = t.trigger_message_id
                AND u.session_id = s.id AND u.role = 'user'
            JOIN chat_message r ON r.id = a.reply_message_id AND r.id = t.reply_message_id
                AND r.session_id = s.id AND r.role = 'assistant'
            WHERE a.session_id = ? AND a.actor_id = ? AND s.mode = 'ai'
              AND a.agent_id = COALESCE(NULLIF(s.agent_id, ''), ?)
              AND (a.agent_id = ? OR EXISTS (SELECT 1 FROM agent WHERE id = a.agent_id))
              AND a.project_id IS NULLIF(s.project_id, '')
              AND (a.project_id IS NULL OR EXISTS (SELECT 1 FROM project p WHERE p.id = a.project_id
                  AND p.status = 'active' AND (p.user_id IS NULL OR p.user_id = a.actor_id)))
              AND a.scope_revision = COALESCE(s.context_scope_revision, 0)
              AND k.kind = 'session' AND t.trigger_source = 'USER_INPUT'
              AND NOT EXISTS (SELECT 1 FROM memory_retracted_source x
                  WHERE x.ref IN ('task:' || k.id, 'chat_message:' || u.id, 'chat_message:' || r.id))
            """;

    public record Scope(String sessionId, String agentId, String projectId, String actorId, long revision) {}

    public record Draft(CompletionTarget target, String note) {
        public String prompt() {
            return "请确认将以下待办标记为已完成。\n\n" + target.content()
                    + (note == null ? "" : "\n\n验收说明\n" + note) + "\n\n等待你确认或拒绝。";
        }
    }

    public record Approval(long operationId, long replyMessageId, String content, String note,
                           String status, Long decidedAt) {}

    private record Binding(String agentId, String projectId, long revision, boolean projectAllowed) {}

    private record Stored(Approval approval, String agentId, long turnId, CompletionTarget target,
                          String decision, String taskStatus) {}

    private record Decision(Approval approval, boolean changed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TodoStore todos;
    private final RuntimeEventBus runtimeEvents;
    private final SessionEventBus sessionEvents;
    private final TransactionTemplate transactions;

    public ChatTodoApprovals(JdbcTemplate jdbc, ObjectMapper json, TodoStore todos,
                             RuntimeEventBus runtimeEvents, SessionEventBus sessionEvents,
                             PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.todos = todos;
        this.runtimeEvents = runtimeEvents;
        this.sessionEvents = sessionEvents;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** An inactive or unavailable project disables this tool for the Turn. */
    public Scope capture(HubRow row, String actorId) {
        requireActor(actorId);
        Binding binding = binding(row.id(), actorId);
        String project = row.projectId() == null || row.projectId().isBlank() ? null : row.projectId();
        if (binding == null || !binding.projectAllowed()
                || !ChatSessionService.ownerOf(row.agentId()).equals(binding.agentId())
                || !Objects.equals(project, binding.projectId())) {
            return null;
        }
        return new Scope(row.id(), binding.agentId(), binding.projectId(), actorId, binding.revision());
    }

    public Draft propose(Scope scope, JsonNode call) {
        requireScope(scope);
        try {
            JsonNode args = json.readTree(call.path("function").path("arguments").asText(""));
            if (args == null || !args.isObject()) {
                throw new IllegalArgumentException("待办审批参数必须是 JSON 对象");
            }
            JsonNode item = args.path("item");
            if (!item.isIntegralNumber() || !item.canConvertToInt() || item.intValue() < 1) {
                throw new IllegalArgumentException("请提供当前待办清单中从 1 开始的序号");
            }
            JsonNode note = args.get("note");
            String text = null;
            if (note != null) {
                if (!note.isTextual() || note.textValue().codePointCount(0, note.textValue().length())
                        > RequestTodoCompletionTool.MAX_NOTE_LENGTH) {
                    throw new IllegalArgumentException("验收说明须为最多 "
                            + RequestTodoCompletionTool.MAX_NOTE_LENGTH + " 字的文本");
                }
                text = note.textValue().isBlank() ? null : note.textValue();
            }
            CompletionTarget target = todos.pendingAt(scope.sessionId(), scope.agentId(), item.intValue())
                    .orElseThrow(() -> new IllegalArgumentException("该序号没有可提交审批的未开始待办"));
            return new Draft(target, text);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("待办审批参数必须是有效 JSON", invalid);
        }
    }

    /** The prompt, original Turn and suspended task become visible together with the operation. */
    public void suspend(Scope scope, Draft draft, long taskId, long turnId, LongSupplier saveReply) {
        transactions.executeWithoutResult(transaction -> {
            lock(scope.sessionId());
            requireScope(scope);
            long triggerId = runningTrigger(scope, taskId, turnId);
            CompletionTarget target = draft.target();
            if (!todos.matchesPending(scope.sessionId(), scope.agentId(), target)) {
                throw conflict("待办已变化，请刷新清单后重新提出审批");
            }
            long replyId = saveReply.getAsLong();
            long now = System.currentTimeMillis();
            int inserted = jdbc.update("""
                    INSERT INTO chat_todo_approval(operation_id, session_id, turn_id, trigger_message_id,
                        reply_message_id, actor_id, agent_id, project_id, scope_revision, todo_id,
                        todo_updated_at, content, note, status, created_at)
                    SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?
                    FROM chat_turn t JOIN chat_message r ON r.id = t.reply_message_id AND r.session_id = t.session_id
                    WHERE t.id = ? AND t.session_id = ? AND t.task_id = ? AND t.trigger_message_id = ?
                      AND r.id = ? AND r.role = 'assistant'
                    """, taskId, scope.sessionId(), turnId, triggerId, replyId, scope.actorId(), scope.agentId(),
                    scope.projectId(), scope.revision(), target.id(), target.updatedAt(), target.content(),
                    draft.note(), now, turnId, scope.sessionId(), taskId, triggerId, replyId);
            if (inserted != 1) {
                throw conflict("审批提问未能保存到当前轮次");
            }
            transition(taskId, TaskStore.ST_RUNNING, TaskStore.ST_SUSPENDED, now);
        });
    }

    public List<Approval> list(String sessionId, String actorId) {
        requireActor(actorId);
        return jdbc.query("WITH visible AS (" + VISIBLE_APPROVALS + """
                ) SELECT * FROM visible WHERE status = 'pending' AND task_status = 'suspended'
                UNION ALL
                SELECT * FROM (SELECT * FROM visible WHERE status <> 'pending' ORDER BY operation_id DESC LIMIT 20)
                ORDER BY operation_id DESC
                """, ChatTodoApprovals::approval, sessionId, actorId, BuiltinAgents.CORE, BuiltinAgents.CORE);
    }

    /** Human decision, checked against the captured scope, original Turn and current checklist state. */
    public Approval decide(String sessionId, long operationId, String actorId, String decision) {
        requireActor(actorId);
        if (!"approve".equals(decision) && !"reject".equals(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision 须为 approve 或 reject");
        }
        Decision result = transactions.execute(transaction -> decideLocked(sessionId, operationId, actorId, decision));
        if (result.changed()) {
            // The transaction above has committed. The UI refresh is a projection of the durable receipt.
            try {
                sessionEvents.publish(new SessionEventBus.Event(sessionId, SessionEventBus.KIND_TODO, false, null));
            } catch (RuntimeException error) {
                log.warn("待办审批状态已保存，前端通知发送失败: session={}, operation={}", sessionId, operationId, error);
            }
        }
        return result.approval();
    }

    private Decision decideLocked(String sessionId, long operationId, String actorId, String decision) {
        lock(sessionId);
        Stored row = jdbc.query(VISIBLE_APPROVALS + " AND a.operation_id = ?",
                        ChatTodoApprovals::stored, sessionId, actorId, BuiltinAgents.CORE, BuiltinAgents.CORE, operationId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "待办审批不存在或已不在当前会话作用域"));
        Approval approval = row.approval();
        if (!"pending".equals(approval.status())) {
            if (decision.equals(row.decision())) {
                return new Decision(approval, false);
            }
            throw conflict("该审批已有处理结果");
        }
        if (!TaskStore.ST_SUSPENDED.equals(row.taskStatus())) {
            throw conflict("原任务已不在待审批状态");
        }
        if ("approve".equals(decision) && Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM task WHERE session_id = ? AND kind = 'session' AND status = 'running' LIMIT 1
                """, rs -> { return rs.next(); }, sessionId))) {
            throw conflict("当前会话正在生成，请结束本轮后再确认待办");
        }
        long now = System.currentTimeMillis();
        String status;
        if ("reject".equals(decision)) {
            status = "rejected";
        } else {
            status = todos.completePending(sessionId, row.agentId(), row.target(), approval.note(), now)
                    ? "approved" : "stale";
        }
        transition(operationId, TaskStore.ST_SUSPENDED,
                "approved".equals(status) ? TaskStore.ST_DONE : TaskStore.ST_CANCELLED, now);
        if (jdbc.update("""
                UPDATE chat_todo_approval SET status = ?, decision = ?, decided_at = ?
                WHERE operation_id = ? AND status = 'pending'
                """, status, decision, now, operationId) != 1) {
            throw conflict("待办审批已变化，请刷新后重试");
        }
        RuntimeEventType type = RuntimeEventType.TODO_APPROVAL_DECIDED;
        RuntimeEvent event = new RuntimeEvent("chat-todo-approval:" + operationId, type, sessionId,
                "user", null, Long.toString(operationId), type.priority(), Long.toString(row.turnId()), now,
                Map.of("operationId", operationId, "todoId", row.target().id(), "decision", decision, "status", status));
        if (runtimeEvents.publish(event) <= 0) {
            throw new IllegalStateException("待办审批事件未能保存");
        }
        return new Decision(new Approval(operationId, approval.replyMessageId(), approval.content(), approval.note(),
                status, now), true);
    }

    /** SQLite takes the writer lock before any scope or version read. */
    private void lock(String sessionId) {
        if (jdbc.update("UPDATE hub_session SET id = id WHERE id = ? AND mode = 'ai'", sessionId) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "单聊会话不存在");
        }
    }

    private Binding binding(String sessionId, String actorId) {
        return jdbc.query("""
                SELECT COALESCE(NULLIF(s.agent_id, ''), ?), NULLIF(s.project_id, ''),
                    COALESCE(s.context_scope_revision, 0),
                    NULLIF(s.project_id, '') IS NULL OR EXISTS (SELECT 1 FROM project p WHERE p.id = s.project_id
                        AND p.status = 'active' AND (p.user_id IS NULL OR p.user_id = ?))
                FROM hub_session s WHERE s.id = ? AND s.mode = 'ai'
                  AND (COALESCE(NULLIF(s.agent_id, ''), ?) = ? OR EXISTS (
                      SELECT 1 FROM agent WHERE id = s.agent_id
                  ))
                """, (rs, n) -> new Binding(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4)),
                BuiltinAgents.CORE, actorId, sessionId, BuiltinAgents.CORE, BuiltinAgents.CORE)
                .stream().findFirst().orElse(null);
    }

    private void requireScope(Scope scope) {
        Binding current = binding(scope.sessionId(), scope.actorId());
        if (!new Binding(scope.agentId(), scope.projectId(), scope.revision(), true).equals(current)) {
            throw conflict("当前会话的审批作用域已变化");
        }
    }

    private long runningTrigger(Scope scope, long taskId, long turnId) {
        return jdbc.query("""
                SELECT u.id FROM task k
                JOIN chat_turn t ON t.task_id = k.id AND t.session_id = k.session_id
                JOIN chat_message u ON u.id = t.trigger_message_id AND u.session_id = k.session_id
                WHERE k.id = ? AND t.id = ? AND k.session_id = ? AND k.agent_id = ? AND k.kind = 'session'
                  AND k.status = 'running' AND t.trigger_source = 'USER_INPUT' AND u.role = 'user'
                  AND t.reply_message_id IS NULL AND NOT EXISTS (SELECT 1 FROM memory_retracted_source x
                      WHERE x.ref IN ('task:' || k.id, 'chat_message:' || u.id))
                """, (rs, n) -> rs.getLong(1), taskId, turnId, scope.sessionId(), scope.agentId())
                .stream().findFirst().orElseThrow(() -> conflict("原提问或任务已无法提交待办审批"));
    }

    private void transition(long taskId, String from, String to, long now) {
        if (jdbc.update("UPDATE task SET status = ?, updated_at = ? WHERE id = ? AND status = ?", to, now, taskId, from) != 1) {
            throw conflict("原任务状态已变化");
        }
    }

    private static Approval approval(ResultSet rs, int rowNum) throws SQLException {
        long decidedAt = rs.getLong("decided_at");
        Long timestamp = rs.wasNull() ? null : decidedAt;
        return new Approval(rs.getLong("operation_id"), rs.getLong("reply_message_id"), rs.getString("content"),
                rs.getString("note"), rs.getString("status"), timestamp);
    }

    private static Stored stored(ResultSet rs, int rowNum) throws SQLException {
        Approval approval = approval(rs, rowNum);
        return new Stored(approval, rs.getString("agent_id"), rs.getLong("turn_id"),
                new CompletionTarget(rs.getLong("todo_id"), rs.getLong("todo_updated_at"), approval.content()),
                rs.getString("decision"), rs.getString("task_status"));
    }

    private static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
    }

    private static ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
