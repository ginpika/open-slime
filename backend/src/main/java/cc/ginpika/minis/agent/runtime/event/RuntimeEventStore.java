package cc.ginpika.minis.agent.runtime.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 运行时事件正本（{@code runtime_event}）：只追加、不改写，按 id（全局递增）排序。
 *
 * <p>设计一 §二把「事件日志是事实来源」放在第一位：任何输入都先落成事件，再被折叠成状态与调度意图。
 * 本表与 {@code roundtable_topic_event}（话题内的状态跃迁正本）互补——
 * 前者记「系统层面发生了什么（用户说了话、工具跑了、任务完成了、有人请求打断）」，
 * 后者记「圆桌状态机怎么跃迁的」。两者靠 {@code correlation_id} / {@code task_id} 关联。
 *
 * <p><b>幂等</b>：{@code event_id} 上有唯一约束；重复投递同一事件时 {@link #append} 返回既有行的 id，
 * 不产生第二条（设计一 §五-3 的「同一事件重复投递不能重复执行副作用」）。
 */
@Repository
public class RuntimeEventStore {

    private static final String COLS =
            "id, event_id, session_id, type, source, target, task_id, priority, correlation_id, "
                    + "payload, created_at";

    public record EventRow(long id, String eventId, String sessionId, String type, String source,
                           String target, String taskId, int priority, String correlationId,
                           String payload, long createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public RuntimeEventStore(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    /**
     * 追加结果。
     *
     * @param id      事件正本的行 id
     * @param created 是否<b>本次新增</b>（false = 同 {@code event_id} 已存在，幂等命中）
     */
    public record AppendResult(long id, boolean created) {
    }

    /**
     * 追加一条事件（幂等）：返回它的行 id 与是否新增。
     *
     * <p>同 {@code event_id} 已存在时返回既有 id、{@code created=false}，不覆盖、不新增。
     * 并发下唯一约束会挡住第二条，这里捕获后转成「读回既有 id」——调用方无需自己判重，
     * 并可据 {@code created} 决定要不要重复广播（见 {@code RuntimeEventBus#publish}）。
     */
    public AppendResult append(RuntimeEvent e) {
        String payload = writeJson(e.payload());
        try {
            jdbc.update("INSERT INTO runtime_event(event_id, session_id, type, source, target, task_id, "
                            + "priority, correlation_id, payload, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    e.eventId(), e.sessionId(), e.type().name(), e.source(), e.target(), e.taskId(),
                    e.priority(), e.correlationId(), payload,
                    e.createdAt() > 0 ? e.createdAt() : System.currentTimeMillis());
            return new AppendResult(existingId(e.eventId()), true);
        } catch (org.springframework.dao.DataAccessException dup) {
            /* 同 event_id 重复投递：SQLite 的唯一约束冲突被 Spring 归为 DataAccessException
               （本仓库未注册 SQLite 错误码翻译器，故具体是 UncategorizedSQLException）。
               只要既有行读得回来，就按幂等处理；读不回来说明是别的写失败，原样上抛。 */
            long existing = existingId(e.eventId());
            if (existing > 0) {
                return new AppendResult(existing, false);
            }
            throw dup;
        }
    }

    /** 按 event_id 读回既有行 id（不存在返回 -1） */
    private long existingId(String eventId) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM runtime_event WHERE event_id = ?",
                Long.class, eventId);
        return ids.isEmpty() ? -1L : ids.get(0);
    }

    /** 某会话在某游标之后的事件（断线补推 / 恢复时重放调度意图） */
    public List<EventRow> since(String sessionId, long afterId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM runtime_event WHERE session_id = ? AND id > ? "
                + "ORDER BY id LIMIT ?", RuntimeEventStore::map, sessionId, afterId, limit);
    }

    /** 某会话最近若干条事件（倒序） */
    public List<EventRow> recent(String sessionId, int limit) {
        return jdbc.query("SELECT " + COLS + " FROM runtime_event WHERE session_id = ? "
                + "ORDER BY id DESC LIMIT ?", RuntimeEventStore::map, sessionId, limit);
    }

    /** 某任务相关的事件（正序）：还原一次执行的完整生命周期 */
    public List<EventRow> byTask(String taskId) {
        return jdbc.query("SELECT " + COLS + " FROM runtime_event WHERE task_id = ? ORDER BY id",
                RuntimeEventStore::map, taskId);
    }

    /** 删除某会话的事件（删会话时清理） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM runtime_event WHERE session_id = ?", sessionId);
    }

    /** 未完成的事件数（线程堵塞等排障用） */
    public int countBySession(String sessionId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM runtime_event WHERE session_id = ?",
                Integer.class, sessionId);
        return n == null ? 0 : n;
    }

    private String writeJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return om.writeValueAsString(new LinkedHashMap<>(payload));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static EventRow map(ResultSet rs, int rowNum) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("event_id"), rs.getString("session_id"),
                rs.getString("type"), rs.getString("source"), rs.getString("target"),
                rs.getString("task_id"), rs.getInt("priority"), rs.getString("correlation_id"),
                rs.getString("payload"), rs.getLong("created_at"));
    }
}
