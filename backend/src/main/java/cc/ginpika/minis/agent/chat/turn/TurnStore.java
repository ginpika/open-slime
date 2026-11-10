package cc.ginpika.minis.agent.chat.turn;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Turn 正本（{@code chat_turn}）：会话历史的<b>结构骨架</b>——哪条提问配哪条回答、这次执行挂在哪条任务上。
 *
 * <p>只存<b>引用</b>、不复制正文：正文仍在 {@code chat_message}（前端读取路径不变）。于是「配对」
 * 从过去「扫描消息行、猜哪条回答配哪条提问」的启发式，变成一条显式外键。
 *
 * <p>两种写法：
 * <ul>
 *   <li>{@link #begin} + {@link #complete}——人机对话：提问落库时先开一条（此时还没有回答），
 *       答完再补上回答行 id。于是「正在生成」这段时间里，这条 Turn 也是看得见的；</li>
 *   <li>{@link #record}——旁路投递（子代理结论、潜意识投递）：消息落库即一次落定，没有中间态。</li>
 * </ul>
 * 都按 {@code INSERT OR IGNORE} 幂等：同一个提问重复开、同一条回答重复记，都只留一行。
 */
@Repository
public class TurnStore {

    private static final String COLS = "id, session_id, trigger_source, trigger_message_id, "
            + "reply_message_id, task_id, scene_id, created_at, updated_at";

    /** 一条 Turn 的落库形态（两端都是引用，不是正文） */
    public record TurnRow(long id, String sessionId, String triggerSource, Long triggerMessageId,
                          Long replyMessageId, Long taskId, Long sceneId, long createdAt, long updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public TurnStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 开一条 Turn（提问已落库、回答还没出来）：立刻有身份与配对关系，回答出来再用 {@link #complete} 补。
     *
     * @param taskId 归属任务（{@code kind=session}）的 id；不跟踪任务的流传 null
     * @return 这条 Turn 的行 id
     */
    public long begin(String sessionId, TurnTrigger.Source source, long triggerMessageId, Long taskId) {
        return begin(sessionId, source, triggerMessageId, taskId, null);
    }

    /** 带 任务段 的开 Turn：单聊试点把每个 Turn 归入开始时捕获的任务段（R1）。 */
    public long begin(String sessionId, TurnTrigger.Source source, long triggerMessageId, Long taskId,
                      Long sceneId) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT OR IGNORE INTO chat_turn(session_id, trigger_source, trigger_message_id, "
                            + "reply_message_id, task_id, scene_id, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sessionId);
            ps.setString(2, source.name());
            ps.setLong(3, triggerMessageId);
            ps.setNull(4, Types.INTEGER);
            if (taskId == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setLong(5, taskId);
            }
            if (sceneId == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setLong(6, sceneId);
            }
            ps.setLong(7, now);
            ps.setLong(8, now);
            return ps;
        }, key);
        Number id = key.getKey();
        if (id != null) {
            return id.longValue();
        }
        /* 已存在（同一提问重复开）：取回原来那条，保证调用方总能拿到 id */
        Long existing = jdbc.query("SELECT id FROM chat_turn WHERE trigger_message_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, triggerMessageId);
        return existing == null ? -1L : existing;
    }

    /** 补上这条 Turn 的输出（回答行 id）；{@code turnId <= 0}（本次生成没记 Turn）时空转 */
    public void complete(long turnId, long replyMessageId) {
        if (turnId <= 0) {
            return;
        }
        jdbc.update("UPDATE chat_turn SET reply_message_id = ?, updated_at = ? WHERE id = ?",
                replyMessageId, System.currentTimeMillis(), turnId);
    }

    /** 旁路投递一次落定（没有「先生成后补」的过程）：子代理结论、潜意识投递这类写入 */
    public void record(String sessionId, TurnTrigger.Source source, long replyMessageId) {
        record(sessionId, source, replyMessageId, null);
    }

    /** 带任务段的旁路投递：子代理报告按落地时的活动任务段归档（R1）。 */
    public void record(String sessionId, TurnTrigger.Source source, long replyMessageId, Long sceneId) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT OR IGNORE INTO chat_turn(session_id, trigger_source, trigger_message_id, "
                        + "reply_message_id, task_id, scene_id, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)",
                sessionId, source.name(), null, replyMessageId, null, sceneId, now, now);
    }

    /** 某 Turn 归属的任务段（R1）；无任务段（普通段或旧数据）返回 null。 */
    public Long sceneOf(String sessionId, long turnId) {
        List<Long> rows = jdbc.query(
                "SELECT scene_id FROM chat_turn WHERE session_id = ? AND id = ?",
                (rs, n) -> nullableLong(rs, "scene_id"), sessionId, turnId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 本会话的 Turn，按落库顺序 */
    public List<TurnRow> turns(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM chat_turn WHERE session_id = ? ORDER BY id",
                TurnStore::map, sessionId);
    }

    public void deleteBySession(String sessionId) {
        jdbc.update("DELETE FROM chat_turn WHERE session_id = ?", sessionId);
    }

    /** 回退后 Turn 行仍可留痕，只在原提问还存在时接受其后台回报。 */
    public boolean triggerPresent(String sessionId, String turnId) {
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM chat_turn t JOIN chat_message m
                  ON m.id = t.trigger_message_id AND m.session_id = t.session_id
                WHERE t.session_id = ? AND t.id = ?
                """, rs -> { return rs.next(); }, sessionId, turnId));
    }

    private static TurnRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TurnRow(
                rs.getLong("id"),
                rs.getString("session_id"),
                rs.getString("trigger_source"),
                nullableLong(rs, "trigger_message_id"),
                nullableLong(rs, "reply_message_id"),
                nullableLong(rs, "task_id"),
                nullableLong(rs, "scene_id"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    /** 可空整数列：xerial sqlite-jdbc 对小整数会返回 Integer，直接 (Long) 强转会炸，故按 Number 取 */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
