package cc.ginpika.minis.agent.chat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 会话事件正本（STEP 4）：{@code session_events} 表的读写——SSE 推送的持久化基座。
 *
 * <p>为什么要落库：推送本身是内存广播，进程内订阅者收得到；但用户切走标签页、连接断开再回来时，
 * 断线期间产生的事件只有库里还留着。每条事件自带自增 {@code id}，前端重连时带上
 * {@code Last-Event-ID}（SSE 协议头），后端据此把漏掉的补推一遍——断线因此不丢事件。
 *
 * <p>{@code event_id} 是幂等键（{@code UNIQUE(hub_session_id, event_id)}）：同一件事重复投递
 * 也只落一行。当前每次生成一个新 uuid，为将来「同一事件双路产生（本地 + 补扫）」留位。
 */
@Repository
public class SessionEventStore {

    /** 一条已落库的事件 */
    public record EventRow(long id, String sessionId, String kind, String payload, long ts) {
    }

    private final JdbcTemplate jdbc;

    /** 事件在流里的单调序号：只用于排序与游标，不参与唯一约束（唯一性在 event_id 上） */
    private final AtomicLong seq = new AtomicLong();

    public SessionEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一条事件，返回带自增 id 的整行（id 即 SSE 游标 / {@code Last-Event-ID}）。
     * 落库失败返回 null——调用方据此跳过广播，绝不因审计失败牵连推送。
     */
    public EventRow record(String sessionId, String kind, String payload) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO session_events(hub_session_id, seq, kind, event_id, payload, ts) "
                                + "VALUES(?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, sessionId);
                ps.setLong(2, seq.incrementAndGet());
                ps.setString(3, kind);
                ps.setString(4, UUID.randomUUID().toString());
                ps.setString(5, payload);
                ps.setLong(6, now);
                return ps;
            }, key);
        } catch (RuntimeException e) {
            return null;
        }
        Number id = key.getKey();
        return id == null ? null : new EventRow(id.longValue(), sessionId, kind, payload, now);
    }

    /** 游标 {@code afterId} 之后的事件（按 id 升序，最多 limit 条）：重连补推用 */
    public List<EventRow> since(long afterId, int limit) {
        return jdbc.query("SELECT id, hub_session_id, kind, payload, ts FROM session_events "
                        + "WHERE id > ? ORDER BY id LIMIT ?",
                SessionEventStore::map, afterId, limit);
    }

    private static EventRow map(ResultSet rs, int rowNum) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("hub_session_id"),
                rs.getString("kind"), rs.getString("payload"), rs.getLong("ts"));
    }
}
