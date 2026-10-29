package cc.ginpika.minis.agent.roundtable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 圆桌共享任务区（{@code roundtable_todo}）的读写口：讨论态与执行态之间的任务板。
 *
 * <p>与 {@code chat_todo}（单会话、全量覆盖、写者自用）不同，这是<b>会话级共享板</b>：
 * 同一张桌子上所有成员都能看到；计划修改权由主控工具边界控制。每一项可指定负责人（{@code owner_peer}），
 * 未指定即历史公共条目。它是「规划 → 执行 → 检查 → 主控决策」闭环的共享状态。
 *
 * <p>状态：{@code pending}（待做）→ {@code doing}（进行中）→ {@code done}（完成）；
 * 途中卡住置 {@code blocked}（附原因，等讨论处理）；不再需要的置 {@code dropped}。
 *
 * <p>{@code depends_on} 是条目之间的前置关系（TODO id 的 JSON 数组）：只有当前置全部
 * {@code done} / {@code dropped} 之后，这条才 {@link #claimable 可被领取}——用于「下游角色等上游产出」
 * 这种分工，避免下游在没有上游结果时开工、白白失败。
 */
@Repository
public class RoundtableTodoStore {

    /** 待做 */
    public static final String STATUS_PENDING = "pending";
    /** 进行中 */
    public static final String STATUS_DOING = "doing";
    public static final String STATUS_REVIEWING = "reviewing";
    public static final String STATUS_CHECKING = "checking";
    /** 受阻（等讨论处理） */
    public static final String STATUS_BLOCKED = "blocked";
    /** 已完成 */
    public static final String STATUS_DONE = "done";
    /** 已放弃 */
    public static final String STATUS_DROPPED = "dropped";

    /** 默认优先级（模型落条目时的值） */
    public static final int PRIORITY_NORMAL = 0;
    /** 「立刻检查」拉到的最高优先级：这条会插到该成员队列的最前 */
    public static final int PRIORITY_URGENT = 100;
    /** 「暂时跳过」压到的最低优先级：没有别的活时才轮到它 */
    public static final int PRIORITY_LOW = -100;

    /**
     * {@code block_with} 里的保留值：目标不是某位成员，而是<b>用户</b>——受阻成员点名用户拍板，
     * 于是全桌暂停、等用户发言（不再叫起任何成员）。成员 id 是 uuid，不会与这个值撞上。
     */
    public static final String BLOCK_WITH_USER = "user";

    public record TodoRow(String id, String sessionId, String topicId, String ownerPeer, String title,
                          String detail, String dependsOn, int priority, String status, String createdBy,
                          String result, String blockReason, String blockWith, int seq, long createdAt,
                          long updatedAt) {
    }

    private static final String COLS =
            "id, session_id, topic_id, owner_peer, title, detail, depends_on, priority, status, "
                    + "created_by, result, block_reason, block_with, seq, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public RoundtableTodoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 往任务板加一条：返回新条目的 id（优先级取默认值）。
     *
     * @param dependsOn 前置依赖的 TODO id JSON 数组文本（无依赖传 null）
     */
    public String add(String sessionId, String topicId, String ownerPeer, String title, String detail,
                      String createdBy, String dependsOn) {
        String id = UUID.randomUUID().toString();
        int seq = nextSeq(sessionId);
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO roundtable_todo(id, session_id, topic_id, owner_peer, title, detail, "
                        + "depends_on, priority, status, created_by, seq, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, sessionId, topicId, ownerPeer, title, detail, dependsOn, PRIORITY_NORMAL,
                STATUS_PENDING, createdBy, seq, now, now);
        return id;
    }

    /** 改优先级：人的干预落在这里（「暂时跳过」压最低、「立刻检查」拉最高） */
    public void setPriority(String id, int priority) {
        jdbc.update("UPDATE roundtable_todo SET priority = ?, updated_at = ? WHERE id = ?",
                priority, System.currentTimeMillis(), id);
    }

    /**
     * 记下「这条受阻时想定向找谁沟通」（{@code agent.id}；null = 全桌一起议）。
     *
     * <p>这是受阻成员自己做的那次决策：只缺某位同伴的信息就写它的 id（系统只安排两人一轮定向沟通），
     * 牵涉多方 / 需要改分工则留空。执行运行时据此把受阻报告<b>定向</b>投给那一位，其余成员不受打扰。
     */
    public void setBlockWith(String id, String peerId) {
        jdbc.update("UPDATE roundtable_todo SET block_with = ?, updated_at = ? WHERE id = ?",
                peerId, System.currentTimeMillis(), id);
    }

    /** 某会话的任务板（按 seq 升序）：所有成员共享的可见视图 */
    public List<TodoRow> list(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_todo WHERE session_id = ? ORDER BY seq",
                RoundtableTodoStore::map, sessionId);
    }

    /** 某会话里还没做完的条目（pending / doing / blocked） */
    public List<TodoRow> openOnes(String sessionId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_todo WHERE session_id = ? "
                + "AND status NOT IN (?, ?) ORDER BY seq", RoundtableTodoStore::map, sessionId,
                STATUS_DONE, STATUS_DROPPED);
    }

    /**
     * 某成员「接下来可以开工」的条目：他名下的或公共的、状态为 pending、<b>且前置依赖都已完成</b>；
     * 按「优先级从高到低、同级按 seq」排——「立刻检查」拉满优先级就插到队首，
     * 「暂时跳过」压到最低就排到最后。
     *
     * <p>只取 pending：doing 表示他正在做（同一 worker 不会重复取），blocked 要等讨论处理。
     *
     * <p>依赖是「等上游做完再做」：{@code depends_on} 里的每条都到 {@code done} / {@code dropped}
     * 才算就绪——于是下游的执行线程在依赖满足前<b>根本领不到这条活</b>，也就不会被唤醒去白跑一趟。
     * 依赖指向的条目已被删掉时按「已完成」处理，免得一条悬空引用把任务板永久卡死。
     */
    public List<TodoRow> claimable(String sessionId, String peerId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_todo WHERE session_id = ? "
                        + "AND (owner_peer = ? OR owner_peer IS NULL) AND status = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM json_each(COALESCE(depends_on, '[]')) dep "
                        + "WHERE COALESCE((SELECT p.status FROM roundtable_todo p WHERE p.id = dep.value), ?) "
                        + "NOT IN (?, ?)) ORDER BY COALESCE(priority, 0) DESC, seq",
                RoundtableTodoStore::map, sessionId, peerId, STATUS_PENDING,
                STATUS_DONE, STATUS_DONE, STATUS_DROPPED);
    }

    /** 取一条（点开任务 / 解析前置依赖时用） */
    public Optional<TodoRow> todo(String id) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_todo WHERE id = ?",
                RoundtableTodoStore::map, id).stream().findFirst();
    }

    /** SQL compare-and-set is the ownership boundary, including for public work. */
    public Optional<TodoRow> claim(String sessionId, String peerId) {
        for (TodoRow candidate : claimable(sessionId, peerId)) {
            if (jdbc.update("UPDATE roundtable_todo SET status='doing',owner_peer=?,updated_at=? "
                    + "WHERE id=? AND session_id=? AND status='pending' "
                    + "AND (owner_peer=? OR owner_peer IS NULL)", peerId, System.currentTimeMillis(),
                    candidate.id(), sessionId, peerId) == 1) return todo(candidate.id());
        }
        return Optional.empty();
    }

    /** A controller dispatch grants exactly one item; never fall through to another pending item. */
    public Optional<TodoRow> claimAssigned(String session, String peer, String todo, boolean review) {
        if (review) {
            return reviewable(session, peer).stream().filter(t -> t.id().equals(todo)).findFirst()
                    .filter(t -> transition(todo, session, STATUS_REVIEWING, STATUS_CHECKING, null, null))
                    .flatMap(t -> todo(todo));
        }
        return claimable(session, peer).stream().filter(t -> t.id().equals(todo)).findFirst()
                .filter(t -> jdbc.update("UPDATE roundtable_todo SET status='doing',owner_peer=?,updated_at=? "
                        + "WHERE id=? AND session_id=? AND status='pending' AND (owner_peer=? OR owner_peer IS NULL)",
                        peer, System.currentTimeMillis(), todo, session, peer) == 1).flatMap(t -> todo(todo));
    }

    public void revise(String id, String title, String dependsOn) {
        jdbc.update("UPDATE roundtable_todo SET title=COALESCE(?,title),depends_on=COALESCE(?,depends_on),updated_at=? WHERE id=?",
                title, dependsOn, System.currentTimeMillis(), id);
    }

    public List<TodoRow> reviewable(String sessionId, String peerId) {
        return jdbc.query("SELECT " + COLS + " FROM roundtable_todo WHERE session_id=? "
                + "AND status='reviewing' AND owner_peer IS NOT NULL AND owner_peer<>? ORDER BY seq",
                RoundtableTodoStore::map, sessionId, peerId);
    }

    public Optional<TodoRow> claimReview(String sessionId, String peerId) {
        for (TodoRow candidate : reviewable(sessionId, peerId)) {
            if (transition(candidate.id(), sessionId, STATUS_REVIEWING, STATUS_CHECKING, null, null))
                return todo(candidate.id());
        }
        return Optional.empty();
    }

    /** Expected state prevents a late worker from overwriting user intervention. */
    public boolean transition(String id, String session, String expected, String status, String result, String reason) {
        return jdbc.update("UPDATE roundtable_todo SET status=?,result=COALESCE(?,result),block_reason=?,updated_at=? "
                + "WHERE id=? AND session_id=? AND status=?", status, result, reason,
                System.currentTimeMillis(), id, session, expected) == 1;
    }

    /** 改状态（可同时写结论 / 受阻原因 / 负责人） */
    public void update(String id, String status, String result, String blockReason, String ownerPeer,
                       String detail) {
        jdbc.update("UPDATE roundtable_todo SET "
                        + "status = COALESCE(?, status), "
                        + "result = COALESCE(?, result), "
                        + "block_reason = COALESCE(?, block_reason), "
                        + "owner_peer = COALESCE(?, owner_peer), "
                        + "detail = COALESCE(?, detail), "
                        + "updated_at = ? WHERE id = ?",
                status, result, blockReason, ownerPeer, detail, System.currentTimeMillis(), id);
    }

    /** 删除一条（讨论中「这条不做了」的直接移除；保留 dropped 语义时用 {@link #update}） */
    public int remove(String id) {
        return jdbc.update("DELETE FROM roundtable_todo WHERE id = ?", id);
    }

    /** 删除某会话的全部条目（删会话时清理） */
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM roundtable_todo WHERE session_id = ?", sessionId);
    }

    private int nextSeq(String sessionId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM roundtable_todo WHERE session_id = ?",
                Integer.class, sessionId);
        return (max == null ? 0 : max) + 1;
    }

    private static TodoRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TodoRow(rs.getString("id"), rs.getString("session_id"), rs.getString("topic_id"),
                rs.getString("owner_peer"), rs.getString("title"), rs.getString("detail"),
                rs.getString("depends_on"), rs.getInt("priority"), rs.getString("status"),
                rs.getString("created_by"), rs.getString("result"), rs.getString("block_reason"),
                rs.getString("block_with"), rs.getInt("seq"), rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
