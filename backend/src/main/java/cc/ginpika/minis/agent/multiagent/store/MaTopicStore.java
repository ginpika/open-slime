package cc.ginpika.minis.agent.multiagent.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ma_topic（一次话题：对话 loop 的边界）。
 *
 * <p>「一个话题」就是一段有始有终的圆桌讨论：从某人（用户或成员）的一条发言开始，
 * 到全体成员都表态结束（含向沉默者征询一轮）为止。同一会话同一时刻至多一个未关闭的话题——
 * 上一话题关掉之后，人的下一条发言就开启新的一轮。
 *
 * <p>{@code floor_peer} 是「当前谁在说话」这个位：授予与让出都落在这里，
 * 抢答（interject）抢的也是它。{@code resolve_round} 记已经重开过几次，
 * 用来给「沉默者发言 → 全员重新决策」这条回路设上限（见 MultiAgentProps）。
 */
@Repository
public class MaTopicStore {

    /** 进行中 */
    public static final String STATUS_OPEN = "open";
    /** 已进入「向沉默者征询」阶段 */
    public static final String STATUS_CLOSING = "closing";
    /** 有人请用户表态：全桌暂停，等用户发言（人的下一句即刻恢复为 open） */
    public static final String STATUS_WAITING = "waiting";
    /** 已收束 */
    public static final String STATUS_CLOSED = "closed";

    /** 话题由人开启 */
    public static final String ORIGIN_USER = "user";
    /** 话题由某个成员开启 */
    public static final String ORIGIN_PEER = "peer";

    public record TopicRow(String id, String sessionId, String origin, String originPeer,
                           String status, String floorPeer, int resolveRound, long createdAt,
                           Long closedAt) {
    }

    private static final String COLS =
            "id, session_id, origin, origin_peer, status, floor_peer, resolve_round, created_at, "
                    + "closed_at";

    private final JdbcTemplate jdbc;

    public MaTopicStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(TopicRow row) {
        jdbc.update("INSERT INTO ma_topic(id, session_id, origin, origin_peer, status, floor_peer, "
                        + "resolve_round, created_at) VALUES(?,?,?,?,?,?,?,?)",
                row.id(), row.sessionId(), row.origin(), row.originPeer(), row.status(),
                row.floorPeer(), row.resolveRound(), System.currentTimeMillis());
    }

    public Optional<TopicRow> topic(String id) {
        return jdbc.query("SELECT " + COLS + " FROM ma_topic WHERE id = ?", MaTopicStore::map, id)
                .stream().findFirst();
    }

    /** 某会话当前未关闭的话题（优先级：进行中 → 征询中；没有则空） */
    public Optional<TopicRow> openBySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_topic WHERE session_id = ? "
                        + "AND status <> '" + STATUS_CLOSED + "' ORDER BY id DESC LIMIT 1",
                MaTopicStore::map, sessionId).stream().findFirst();
    }

    /** 某会话最近的话题（含已关闭的），新建话题时判断上一轮是否已经收束 */
    public Optional<TopicRow> latestBySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_topic WHERE session_id = ? "
                        + "ORDER BY id DESC LIMIT 1", MaTopicStore::map, sessionId)
                .stream().findFirst();
    }

    public List<TopicRow> topicsBySession(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM ma_topic WHERE session_id = ? ORDER BY id",
                MaTopicStore::map, sessionId);
    }

    /** 某会话的全部话题（删除会话时清理；事件须由调用方先清） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM ma_topic WHERE session_id = ?", sessionId);
    }

    public void setStatus(String id, String status) {
        jdbc.update("UPDATE ma_topic SET status = ? WHERE id = ?", status, id);
    }

    /** 授予 / 让出发言权（peerId 传 null 表示此刻没人在说） */
    public void setFloor(String id, String peerId) {
        jdbc.update("UPDATE ma_topic SET floor_peer = ? WHERE id = ?", peerId, id);
    }

    /** 重开一次（沉默者发言 → 全员重新决策），返回累计次数 */
    public int incResolveRound(String id) {
        jdbc.update("UPDATE ma_topic SET resolve_round = resolve_round + 1 WHERE id = ?", id);
        return topic(id).map(TopicRow::resolveRound).orElse(0);
    }

    /** 收束话题：置 closed、清发言权、记关闭时间（幂等，已关闭则不再改） */
    public int close(String id) {
        return jdbc.update("UPDATE ma_topic SET status = ?, floor_peer = NULL, closed_at = ? "
                + "WHERE id = ? AND status <> ?",
                STATUS_CLOSED, System.currentTimeMillis(), id, STATUS_CLOSED);
    }

    private static TopicRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TopicRow(rs.getString("id"), rs.getString("session_id"),
                rs.getString("origin"), rs.getString("origin_peer"), rs.getString("status"),
                rs.getString("floor_peer"), rs.getInt("resolve_round"),
                rs.getLong("created_at"), MaRows.nullableLong(rs, "closed_at"));
    }
}
