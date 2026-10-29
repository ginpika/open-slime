package cc.ginpika.minis.agent.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 任务留痕：{@code task} 表的读写——它同时是「一次执行」的<b>唯一正本</b>。
 *
 * <p>身份与生命周期（谁在跑、什么状态、能不能重跑）与过程（组装出的上下文 / 思维链 / 工具往返）
 * 同处一行；{@link ExecStore} 在本表上按「执行来源」过滤与聚合，供页面读。
 *
 * <p>{@link #insert} 用于「先有任务、后才有执行」的情形（前台会话的任务由
 * {@link cc.ginpika.minis.agent.chat.ChatTaskRunner} 建，它没有独立上下文）；真正的执行由
 * {@link #beginExec} 一步建行（带上上下文）。
 */
@Repository
public class TaskStore {

    /** 任务种类：前台会话 */
    public static final String KIND_SESSION = "session";
    /** 任务种类：匿名子代理（当前唯一在用的） */
    public static final String KIND_SUBAGENT = "subagent";
    /** 任务种类：心跳编制（任务计划 / 心跳主会话） */
    public static final String KIND_PLAN = "plan";
    /** 任务种类：编码 Agent 的项目任务 */
    public static final String KIND_CODE = "code";

    /** 状态机：pending → running → (suspended) → done / failed / cancelled */
    public static final String ST_PENDING = "pending";
    public static final String ST_RUNNING = "running";
    public static final String ST_SUSPENDED = "suspended";
    public static final String ST_DONE = "done";
    public static final String ST_FAILED = "failed";
    public static final String ST_CANCELLED = "cancelled";
    /** 进程重启打断：不是任务自身的终态，而是「上次进程没跑完，可重跑」的标记 */
    public static final String ST_INTERRUPTED = "interrupted";

    /** 产物种类：结论文本（content 存正文） */
    public static final String ART_TEXT = "text";
    /** 产物种类：生成的文件（ref 存路径） */
    public static final String ART_FILE = "file";
    /** 产物种类：外部引用（ref 存 chunk id / 地址） */
    public static final String ART_REF = "ref";

    public record TaskRow(long id, Long parentId, String kind, String status, String title,
                          String sessionId, String agentId, String modelId, String workspaceId,
                          String budget, String payload, String origin, long createdAt,
                          long updatedAt) {
    }

    /** 一条任务产物：kind 见 {@link #ART_TEXT} 等；content 与 ref 二选一（text 用前者、file/ref 用后者） */
    public record ArtifactRow(long id, long taskId, Long execId, String kind, String name,
                              String content, String ref, long createdAt) {
    }

    /** 列清单（各查询共用）：顺序与 {@link #map} 对应 */
    private static final String COLUMNS = "id, parent_id, kind, status, title, session_id, agent_id, "
            + "model_id, workspace_id, budget, payload, origin, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public TaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Execution provenance invalidated when conversation history is rewound. Audit rows remain intact. */
    public List<Long> idsSince(String sessionId, long fromTime) {
        return jdbc.queryForList("SELECT id FROM task WHERE session_id=? AND created_at>=? ORDER BY id",
                Long.class, sessionId, fromTime);
    }

    /**
     * 建一条任务（pending），返回任务 id。
     *
     * @param parentId 父任务（嵌套 / 重跑时挂原任务下）；null=顶层
     * @param agentId  归属 Agent（这次执行是哪个智能体产生的）；null=未归属
     * @param payload  重跑所需的原始输入（JSON 字符串）；无可重跑输入时为 null
     */
    public long insert(String kind, Long parentId, String title, String sessionId, String agentId,
                       String payload) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task(parent_id, kind, status, title, session_id, agent_id, payload, "
                            + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, parentId);
            ps.setString(2, kind);
            ps.setString(3, ST_PENDING);
            ps.setString(4, title);
            ps.setString(5, sessionId);
            ps.setString(6, agentId);
            ps.setString(7, payload);
            ps.setLong(8, now);
            ps.setLong(9, now);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /**
     * 开一次执行的行：建行 + 写过程列（上下文 / 批次 / 编制 / 来源）+ 置 running，一步到位。
     *
     * <p>「一次执行」就是这一行——识别信息与过程同处一表，不再另开一行留痕（原先是
     * {@code sub_exec} 一行 + 本表一行、靠 {@code exec_id} 互指）。跑完由执行器收尾。
     *
     * @param origin   执行来源：心跳 / 计划 / 子代理（见 {@link ExecStore#KIND_HEARTBEAT} 等），
     *                 写进 {@code origin} 列——读侧按它分类（资源用量分桶、执行详情）
     * @param parentId 派它的那个任务（心跳 → 计划 → 子代理 这条链就靠它连起来）；顶层任务传 null
     * @param sessionId 归属会话（子代理：结论要投回的那个会话）；无会话的执行传 null
     * @param agentId  归属 Agent（{@code agent.id}）：这次执行是哪个智能体产生的；可为 null
     * @param context  组装出的完整 LLM 上下文（messages JSON）
     * @return 这次执行的 id（也就是它这行任务的 id）
     */
    public long beginExec(String origin, String runId, String planId, Long parentId,
                          String sessionId, String agentId, String title, String payload,
                          String context) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task(parent_id, kind, status, title, session_id, agent_id, payload, run_id, "
                            + "plan_id, origin, context, started_at, created_at, updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, parentId);
            ps.setString(2, taskKindOf(origin));
            ps.setString(3, ST_RUNNING);
            ps.setString(4, title);
            ps.setString(5, sessionId);
            ps.setString(6, agentId);
            ps.setString(7, payload);
            ps.setString(8, runId);
            ps.setString(9, planId);
            ps.setString(10, origin);
            ps.setString(11, context);
            ps.setLong(12, now);
            ps.setLong(13, now);
            ps.setLong(14, now);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** 执行来源 → 任务种类：心跳主会话也是「编制」跑出来的一次，故与计划同属 plan */
    private static String taskKindOf(String origin) {
        return ExecStore.KIND_AGENT.equals(origin) ? KIND_SUBAGENT : KIND_PLAN;
    }

    /** 任务收尾：跑出结论是 done，没跑出（含抛错）是 failed */
    public int finish(long id, boolean done) {
        return setStatus(id, done ? ST_DONE : ST_FAILED);
    }

    /**
     * 最近若干条任务（倒序）。agentId 给 null 表示不过滤（全部 Agent）；kind 给 null 表示不过滤种类。
     */
    public List<TaskRow> recent(String agentId, String kind, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM task WHERE 1=1");
        boolean byAgent = agentId != null && !agentId.isBlank();
        boolean byKind = kind != null && !kind.isBlank();
        if (byAgent) {
            sql.append(" AND agent_id = ?");
        }
        if (byKind) {
            sql.append(" AND kind = ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        if (byAgent && byKind) {
            return jdbc.query(sql.toString(), TaskStore::map, agentId, kind, limit);
        }
        if (byAgent) {
            return jdbc.query(sql.toString(), TaskStore::map, agentId, limit);
        }
        if (byKind) {
            return jdbc.query(sql.toString(), TaskStore::map, kind, limit);
        }
        return jdbc.query(sql.toString(), TaskStore::map, limit);
    }

    /** 状态跃迁：调用点就是状态机的实现，这里不另做合法性校验（越权流转应在调用点显形） */
    public int setStatus(long id, String status) {
        return jdbc.update("UPDATE task SET status = ?, updated_at = ? WHERE id = ?",
                status, System.currentTimeMillis(), id);
    }

    /** 写任务记忆检查点（memory-v5 §9）：暂停 / 中断前把工作状态快照（JSON）落到 task.memory_checkpoint */
    public int setCheckpoint(long id, String checkpointJson) {
        return jdbc.update("UPDATE task SET memory_checkpoint = ?, updated_at = ? WHERE id = ?",
                checkpointJson, System.currentTimeMillis(), id);
    }

    /** 读任务记忆检查点（无则 null） */
    public String checkpointOf(long id) {
        return jdbc.query("SELECT memory_checkpoint FROM task WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }

    /** 带检查点的任务 id（重启后可恢复的工作状态；供启动恢复上报） */
    public List<Long> idsWithCheckpoint(String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT id FROM task WHERE memory_checkpoint IS NOT NULL "
                    + "ORDER BY id DESC", (rs, n) -> rs.getLong(1));
        }
        return jdbc.query("SELECT id FROM task WHERE memory_checkpoint IS NOT NULL AND status = ? "
                + "ORDER BY id DESC", (rs, n) -> rs.getLong(1), status);
    }

    /** 落一条任务产物（任务的「产出」）：结论文本 / 生成的文件 / 外部引用 */
    public void addArtifact(long taskId, Long execId, String kind, String name, String content,
                            String ref) {
        jdbc.update("INSERT INTO task_artifact(task_id, exec_id, kind, name, content, ref, "
                        + "created_at) VALUES(?,?,?,?,?,?,?)",
                taskId, execId, kind, name, content, ref, System.currentTimeMillis());
    }

    /** 某条任务的产物（按落库顺序） */
    public List<ArtifactRow> artifactsOf(long taskId) {
        return jdbc.query("SELECT id, task_id, exec_id, kind, name, content, ref, created_at "
                        + "FROM task_artifact WHERE task_id = ? ORDER BY id",
                (rs, n) -> new ArtifactRow(rs.getLong("id"), rs.getLong("task_id"),
                        nullableLong(rs, "exec_id"), rs.getString("kind"), rs.getString("name"),
                        rs.getString("content"), rs.getString("ref"), rs.getLong("created_at")),
                taskId);
    }

    public Optional<TaskRow> task(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task WHERE id = ?", TaskStore::map, id)
                .stream().findFirst();
    }

    /**
     * 启动恢复：把上次进程遗留的在途任务（pending / running）置为 interrupted——
     * 它们背后的线程随进程一起没了，状态还留着只是残影；标成 interrupted 前端才看得见
     * 「已中断、可重跑」，而不是永远显示「进行中」。
     *
     * @return 改动的行数
     */
    public int interruptRunning() {
        return jdbc.update("UPDATE task SET status = ?, updated_at = ? WHERE status IN (?, ?)",
                ST_INTERRUPTED, System.currentTimeMillis(), ST_PENDING, ST_RUNNING);
    }

    private static TaskRow map(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRow(rs.getLong("id"), nullableLong(rs, "parent_id"),
                rs.getString("kind"), rs.getString("status"), rs.getString("title"),
                rs.getString("session_id"), rs.getString("agent_id"), rs.getString("model_id"),
                rs.getString("workspace_id"), rs.getString("budget"), rs.getString("payload"),
                rs.getString("origin"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    /**
     * 可空整型列的读取：xerial sqlite-jdbc 对小整数返回 Integer、对大整数返回 Long，
     * 直接 {@code (Long) rs.getObject(...)} 会在小 id 上 ClassCastException，故按 long 读再判空。
     */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
