package cc.ginpika.minis.agent.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待办清单留痕（STEP 4 编排层进阶）：{@code chat_todo} 表的读写。
 *
 * <p>一条清单归属一个会话，随会话存档；{@link #replace} 是<b>全量覆盖</b>——写入方（write_todos 工具）
 * 给的是「当前完整视图」，于是清单永远只有一份、不会分叉，也就不需要逐条增删改的接口。
 *
 * <p>与 {@link TaskStore} 的关系：task 是「一次执行」的身份（谁在跑、什么状态）；这里记的是
 * <b>这轮活分了哪几步、各步做到哪</b>——两者都是编排层的正本，但粒度与用途不同，不共用一张表。
 */
@Repository
public class TodoStore {

    /** 未开始 */
    public static final String ST_PENDING = "pending";
    /** 进行中 */
    public static final String ST_IN_PROGRESS = "in_progress";
    /** 已完成 */
    public static final String ST_DONE = "done";
    /** 失败（note 里写原因） */
    public static final String ST_FAILED = "failed";

    /** 合法状态集合：写入侧据此把越界值收敛为 pending（模型偶尔会造词） */
    private static final Set<String> STATUSES = Set.of(ST_PENDING, ST_IN_PROGRESS, ST_DONE, ST_FAILED);

    /** 清单里的一步 */
    public record TodoItem(int seq, String content, String status, String note) {
    }

    /** A proposed completion refers to a specific version of one checklist row. */
    public record CompletionTarget(long id, long updatedAt, String content) {}

    private final JdbcTemplate jdbc;

    public TodoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 整体替换「某会话内、某 Agent」的清单（先清后写，同一事务内）：写入方给的是「当前完整视图」，
     * 于是这位 Agent 的清单永远只有一份；空列表即清空它。
     *
     * <p>{@code owner} 是归属 Agent（成员 id / 内置 Agent，见 {@code AgentScope}）：首页会话里
     * 恒为内置 Agent，圆桌里为当前发言的成员——同会话的多个成员各持一份、互不覆盖。
     */
    @Transactional
    public void replace(String sessionId, String owner, List<TodoItem> items) {
        jdbc.update("DELETE FROM chat_todo WHERE session_id = ? AND owner IS ?", sessionId, owner);
        long now = System.currentTimeMillis();
        int seq = 0;
        for (TodoItem item : items) {
            jdbc.update("INSERT INTO chat_todo(session_id, owner, seq, content, status, note, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    sessionId, owner, seq++, item.content(), normalize(item.status()), item.note(),
                    now, now);
        }
    }

    /** 某会话内、某 Agent 当前的清单（按 seq） */
    public List<TodoItem> list(String sessionId, String owner) {
        return jdbc.query("SELECT seq, content, status, note FROM chat_todo "
                + "WHERE session_id = ? AND owner IS ? ORDER BY seq", TodoStore::map, sessionId, owner);
    }

    public Optional<CompletionTarget> pendingAt(String sessionId, String owner, int item) {
        return jdbc.query("""
                SELECT id, updated_at, content FROM (
                    SELECT id, updated_at, content, status FROM chat_todo
                    WHERE session_id = ? AND owner = ? ORDER BY seq, id LIMIT 1 OFFSET ?
                ) WHERE status = 'pending'
                """, (rs, n) -> new CompletionTarget(rs.getLong("id"), rs.getLong("updated_at"), rs.getString("content")),
                sessionId, owner, item - 1).stream().findFirst();
    }

    private static final String PENDING_TARGET = """
            WHERE session_id = ? AND owner = ? AND id = ? AND updated_at = ? AND content = ? AND status = 'pending'
            """;

    public boolean matchesPending(String sessionId, String owner, CompletionTarget target) {
        return Boolean.TRUE.equals(jdbc.query("SELECT 1 FROM chat_todo " + PENDING_TARGET,
                rs -> { return rs.next(); }, sessionId, owner, target.id(), target.updatedAt(), target.content()));
    }

    public boolean completePending(String sessionId, String owner, CompletionTarget target, String note, long now) {
        return jdbc.update("""
                UPDATE chat_todo SET status = 'done', note = COALESCE(?, note), updated_at = MAX(updated_at + 1, ?)
                """ + PENDING_TARGET, note, now, sessionId, owner, target.id(), target.updatedAt(), target.content()) == 1;
    }

    /** 删除会话时清掉它名下所有 Agent 的清单（会话没了，清单不该留成孤儿行） */
    @Transactional
    public int deleteBySession(String sessionId) {
        return jdbc.update("DELETE FROM chat_todo WHERE session_id = ?", sessionId);
    }

    /**
     * 一次生成收尾时把遗留的「进行中」就地收敛为「未开始」（其余状态不动）。
     *
     * <p>生成一结束就不该再有「正在进行中」——模型若因中断 / 超限而没收尾，这一步替它收敛，
     * 清单不会长期卡在 {@code in_progress} 而无人推进。就地 UPDATE 而非整体替换：
     * 不打乱 seq、不带进无关写入。
     */
    @Transactional
    public void convergeInProgress(String sessionId, String owner) {
        jdbc.update("UPDATE chat_todo SET status = ?, updated_at = ? "
                        + "WHERE session_id = ? AND owner IS ? AND status = ?",
                ST_PENDING, System.currentTimeMillis(), sessionId, owner, ST_IN_PROGRESS);
    }

    /** 状态收敛：合法值原样，越界 / 空值一律按 pending（不因模型造词而丢这一步） */
    private static String normalize(String status) {
        String s = status == null ? "" : status.trim();
        return STATUSES.contains(s) ? s : ST_PENDING;
    }

    /** 清单 → 可读文本（工具回显与上下文注入共用同一形状，模型与前端读到的进度一致）；空清单返回空串 */
    public static String render(List<TodoItem> items) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (TodoItem it : items) {
            sb.append(++n).append(". [").append(label(it.status())).append("] ").append(it.content());
            if (it.note() != null && !it.note().isBlank()) {
                sb.append("　— ").append(it.note().trim());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String label(String status) {
        return switch (normalize(status)) {
            case ST_IN_PROGRESS -> "进行中";
            case ST_DONE -> "已完成";
            case ST_FAILED -> "失败";
            default -> "未开始";
        };
    }

    private static TodoItem map(ResultSet rs, int rowNum) throws SQLException {
        return new TodoItem(rs.getInt("seq"), rs.getString("content"), rs.getString("status"),
                rs.getString("note"));
    }
}
