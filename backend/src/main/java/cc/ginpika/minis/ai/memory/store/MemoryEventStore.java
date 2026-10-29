package cc.ginpika.minis.ai.memory.store;

import cc.ginpika.minis.ai.core.MemoryExec;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * memory_event（记忆事件流）的读写：三轴记忆里「时序轴」的正本。
 *
 * <p>写入一律走 {@link cc.ginpika.minis.ai.memory.MemoryWriter}（发生点 push、幂等、失败隔离）；
 * 本类只管落行与查询。事件只追加不改写——情节正本在 chat_message / task，
 * 这里存投影：截断后的正文 + 指回正本的 ref（后续向量/图两轴的增强就挂在 ref 上）。
 *
 * <p>时序轴在召回侧只做「窗口过滤」不参与打分：混合检索的 RRF 是 rank-only 融合，
 * 把时间序当第四路源等于断言「最新的最相关」，量纲也不一致——那是融合层的设计错误，
 * 不是本表的问题（见 ai/knowledge/retrieval/FastSearchService 的 rrf）。
 */
@Repository
public class MemoryEventStore {
    public boolean sourceAllowed(String refType,long refId) {
        return cc.ginpika.minis.ai.memory.MemoryResetBoundary.read(jdbc).allows("[\""+refType+":"+refId+"\"]");
    }

    /** 一条记忆事件：kind/actor 见 schema.sql 注释，ref 指回正本行 */
    public record EventRow(long id, String eventKey, String kind, String actor, String title,
                           String payload, String refType, long refId, long createdAt) {
    }

    private static final String COLS =
            "id, event_key, kind, actor, title, payload, ref_type, ref_id, created_at";

    private final JdbcTemplate jdbc;

    public MemoryEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条事件（同 event_key 已存在则静默跳过）：返回是否真的写入 */
    public boolean append(String eventKey, String kind, String actor, String title,
                          String payload, String refType, long refId) {
        return jdbc.update("INSERT OR IGNORE INTO memory_event(event_key, kind, actor, title, payload, "
                + "ref_type, ref_id, created_at) VALUES(?,?,?,?,?,?,?,?)",
                eventKey, kind, actor, title, payload, refType, refId, System.currentTimeMillis()) > 0;
    }

    public boolean append(MemoryExec exec, String eventKey, String kind, String actor, String title,
                          String payload, String refType, long refId) {
        return jdbc.update(
                "INSERT OR IGNORE INTO memory_event(event_key, kind, actor, title, payload, "
                        + "ref_type, ref_id, created_at, agent_id, project_id, session_id, task_id, identity) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                eventKey, kind, actor, title, payload, refType, refId,
                System.currentTimeMillis(), exec == null ? null : exec.agentOrBuiltin(),
                exec == null ? null : exec.projectId(), exec == null ? null : exec.sessionId(),
                exec == null ? null : exec.taskId(),
                exec == null ? null : exec.identityOrInferred().name()) > 0;
    }

    /** 按 event_key 取一条（异步补向量时由幂等键反查事件行）；不存在返回 null */
    public EventRow byKey(String eventKey) {
        return jdbc.query("SELECT " + COLS + " FROM memory_event WHERE event_key = ?",
                rs -> rs.next() ? map(rs) : null, eventKey);
    }

    /** 最近若干条（时间倒序，created_at 同毫秒按 id 分先后） */
    public List<EventRow> recent(int limit) {
        return window(null, null, null, null, limit);
    }

    /**
     * 时间窗收敛候选（时间倒序）：sinceMs/untilMs 为 null 表示该侧不限，
     * kind/actor 可选过滤。这是时序轴在召回侧的全部职责——只收窄候选集，
     * 绝不把时间序当打分源（打分在窗口内由向量轴完成，见 recall 包）。
     */
    public List<EventRow> window(Long sinceMs, Long untilMs, String kind, String actor, int limit) {
        return window(sinceMs, untilMs, kind, actor, List.of(), limit);
    }

    /**
     * 同 {@link #window(Long, Long, String, String, int)}，另可按来源排除——默认召回用它挡掉后台产出
     * （心跳 / 职责 / 子代理），免得「你跟我说过什么」被后台自己跑出来的东西挤占。
     *
     * <p>actor 为空的行（理论上不该有）不参与排除：{@code NOT IN} 遇 NULL 返回 NULL，
     * 不加这层兜底会把它们一并滤掉。
     */
    public List<EventRow> window(Long sinceMs, Long untilMs, String kind, String actor,
                                 Collection<String> excludeActors, int limit) {
        return window(MemoryExec.system(null), sinceMs, untilMs, kind, actor, excludeActors, limit);
    }

    public List<EventRow> window(MemoryExec exec, Long sinceMs, Long untilMs, String kind, String actor,
                                Collection<String> excludeActors, int limit) {
        return queryWindow(exec,sinceMs,untilMs,kind,actor,excludeActors,limit,null);
    }
    public List<EventRow> page(MemoryExec exec,Long since,Long until,String kind,String actor,Collection<String> excluded,long after,int limit) {
        return queryWindow(exec,since,until,kind,actor,excluded,limit,after);
    }
    private List<EventRow> queryWindow(MemoryExec exec,Long sinceMs,Long untilMs,String kind,String actor,Collection<String> excludeActors,int limit,Long after) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM memory_event WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (exec == null || !exec.human()) {
            MemoryExec e = exec == null ? MemoryExec.none() : exec;
            // 原始材料仅属于当前任务/会话；无绑定的后台结论属于本 Agent 的同一项目域。
            sql.append(" AND agent_id IS NOT NULL AND (session_id = ? OR task_id = ? "
                    + "OR (session_id IS NULL AND task_id IS NULL AND agent_id = ? "
                    + "AND project_id IS ?))");
            args.add(e.sessionId()); args.add(e.taskId());
            args.add(e.agentOrBuiltin()); args.add(e.projectId());
        }
        if (sinceMs != null) {
            sql.append(" AND created_at >= ?");
            args.add(sinceMs);
        }
        if (untilMs != null) {
            sql.append(" AND created_at < ?");
            args.add(untilMs);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = ?");
            args.add(kind);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = ?");
            args.add(actor);
        }
        if (excludeActors != null && !excludeActors.isEmpty()) {
            sql.append(" AND (actor IS NULL OR actor NOT IN (")
                    .append(String.join(",", excludeActors.stream().map(a -> "?").toList()))
                    .append("))");
            args.addAll(excludeActors);
        }
        if(after!=null) {sql.append(" AND id>?");args.add(after);}
        sql.append(after==null?" ORDER BY created_at DESC, id DESC LIMIT ?":" ORDER BY id LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, rowNum) -> map(rs), args.toArray());
    }

    static EventRow map(ResultSet rs) throws SQLException {
        return new EventRow(rs.getLong("id"), rs.getString("event_key"), rs.getString("kind"),
                rs.getString("actor"), rs.getString("title"), rs.getString("payload"),
                rs.getString("ref_type"), rs.getLong("ref_id"), rs.getLong("created_at"));
    }

    /**
     * 清空全部时序记忆（设置页「重置知识库」用）：先删向量投影（外键指向 memory_event），
     * 再删事件正本；返回删除的事件条数。正本投影连带向量一并抹除，同 ChunkStore / GraphStore
     * 的 clearAll 清自家卫星向量表的惯例。
     */
    @Transactional
    public int clearAll() {
        jdbc.update("DELETE FROM memory_event_embedding");
        return jdbc.update("DELETE FROM memory_event");
    }
}
