package cc.ginpika.minis.agent.heartbeat.store;

import cc.ginpika.minis.agent.runtime.ExecStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 后台运行层的「编制」持久化：heartbeat（每个 Agent 一行的心跳）/ heartbeat_plan（某 Agent 的常驻职责名册）。
 *
 * <p>编制是长期状态：心跳逐 Agent 各一行（{@code agent_id} 主键），职责是一份逐 Agent 隔离的名册。
 * 谁跑了一次、跑得怎么样，不在这一层——那属于 {@link ExecStore}（heartbeat_run / task / changeset_item）。
 *
 * <p>素材不再靠「注入器」声明式注入：心跳与职责的正文只写正文，需要什么就自己调工具现取
 * （everything in tools &amp; bash）。故本层不再有 {@code injectors} 列。
 *
 * <p>子代理（匿名 worker）没有编制：它不留名册，故这里没有它的行；它的执行同样落在 task。
 */
@Repository
public class PlanStore {

    /**
     * 某个 Agent 的心跳：该 Agent「自己醒过来」时的正文与节拍。每拍由它出场，看着自己的职责名册
     * 决定这一拍派谁、怎么派。
     */
    public record HeartbeatRow(String agentId, String content, String modelId, boolean enabled,
                               long tickMs, Long lastBeatAt) {
    }

    /** 常驻职责（某 Agent 的一份编制）：提示词正文 + 角色 + 允许调用的工具 + 节拍参考（interval_ms）+
     *  交付渠道。由大模型经工具自行创建与维护，人只在页面上管一件事：{@code enabled} 这个刹车
     *  （停用不删，只挡自动派出） */
    public record PlanRow(String id, String agentId, String name, String role, String content,
                          String tools, String channels, Long intervalMs, String input,
                          String modelId, String rationale, int sort, Long lastBeatAt,
                          boolean enabled) {
    }

    private final JdbcTemplate jdbc;

    public PlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 逐 Agent 的心跳 ----------

    /** 某 Agent 的心跳行：库里没配过（或整行缺失）时返回 empty，由调用方退到内置正文 */
    public Optional<HeartbeatRow> heartbeat(String agentId) {
        return jdbc.query("SELECT agent_id, content, model_id, enabled, tick_ms, last_beat_at "
                        + "FROM heartbeat WHERE agent_id = ?",
                        PlanStore::mapHeartbeat, agentId)
                .stream().findFirst();
    }

    /** 全部「已启用」的心跳（逐 Agent 一行）：调度器据此扫描谁该醒 */
    public List<HeartbeatRow> enabledHeartbeats() {
        return jdbc.query("SELECT agent_id, content, model_id, enabled, tick_ms, last_beat_at "
                        + "FROM heartbeat WHERE enabled = 1 ORDER BY agent_id",
                PlanStore::mapHeartbeat);
    }

    /**
     * 覆盖写某 Agent 的心跳（按 agent_id upsert）。{@code last_beat_at} 传 null 时保留库里已有的值
     * （改正文 / 开关不该抹掉上一次真敲的时间）。
     */
    public void saveHeartbeat(HeartbeatRow h) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO heartbeat(agent_id, content, model_id, enabled, tick_ms, last_beat_at, "
                        + "updated_at) VALUES(?,?,?,?,?,?,?) "
                        + "ON CONFLICT(agent_id) DO UPDATE SET content = excluded.content, "
                        + "model_id = excluded.model_id, enabled = excluded.enabled, "
                        + "tick_ms = excluded.tick_ms, "
                        + "last_beat_at = COALESCE(excluded.last_beat_at, heartbeat.last_beat_at), "
                        + "updated_at = excluded.updated_at",
                h.agentId(), h.content(), h.modelId(), h.enabled() ? 1 : 0,
                Math.max(5_000L, h.tickMs()), h.lastBeatAt(), now);
    }

    /** 记下某 Agent 心跳最近一次真敲的时间（调度据此判「到点」） */
    public void markHeartbeatBeat(String agentId, long at) {
        jdbc.update("UPDATE heartbeat SET last_beat_at = ?, updated_at = ? WHERE agent_id = ?",
                at, System.currentTimeMillis(), agentId);
    }

    /** 人工开关：启用 / 停用某 Agent 的心跳 */
    public int setHeartbeatEnabled(String agentId, boolean enabled) {
        return jdbc.update("UPDATE heartbeat SET enabled = ?, updated_at = ? WHERE agent_id = ?",
                enabled ? 1 : 0, System.currentTimeMillis(), agentId);
    }

    /** 运行时调整某 Agent 的心跳节拍（毫秒） */
    public int setHeartbeatTick(String agentId, long tickMs) {
        return jdbc.update("UPDATE heartbeat SET tick_ms = ?, updated_at = ? WHERE agent_id = ?",
                Math.max(5_000L, tickMs), System.currentTimeMillis(), agentId);
    }

    // ---------- 某 Agent 的常驻职责 ----------

    /** 列清单（plans / plan 共用）：顺序与 {@link #mapPlan} 对应 */
    private static final String PLAN_COLUMNS =
            "id, agent_id, name, role, content, tools, channels, interval_ms, input, "
                    + "model_id, rationale, sort, last_beat_at, enabled";

    public List<PlanRow> plans(String agentId) {
        return jdbc.query("SELECT " + PLAN_COLUMNS
                        + " FROM heartbeat_plan WHERE agent_id = ? ORDER BY sort, id",
                PlanStore::mapPlan, agentId);
    }

    public Optional<PlanRow> planById(String id) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM heartbeat_plan WHERE id = ?",
                        PlanStore::mapPlan, id)
                .stream().findFirst();
    }

    /** 按名字找某 Agent 的计划：名字是模型派活时最顺手的引用（id 是 uuid，对模型不友好） */
    public Optional<PlanRow> planByName(String agentId, String name) {
        return jdbc.query("SELECT " + PLAN_COLUMNS
                        + " FROM heartbeat_plan WHERE agent_id = ? AND name = ? ORDER BY sort, id",
                        PlanStore::mapPlan, agentId, name)
                .stream().findFirst();
    }

    public void insertPlan(PlanRow p) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO heartbeat_plan(id, agent_id, name, role, content, tools, channels, "
                        + "interval_ms, input, model_id, rationale, enabled, sort, created_at, "
                        + "updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(), p.agentId(), p.name(), p.role(), p.content(), p.tools(), p.channels(),
                p.intervalMs(), p.input(), p.modelId(), p.rationale(), p.enabled() ? 1 : 0,
                p.sort(), now, now);
    }

    public int updatePlan(PlanRow p) {
        return jdbc.update(
                "UPDATE heartbeat_plan SET name = ?, role = ?, content = ?, tools = ?, "
                        + "channels = ?, interval_ms = ?, input = ?, model_id = ?, rationale = ?, "
                        + "enabled = ?, sort = ?, updated_at = ? WHERE id = ?",
                p.name(), p.role(), p.content(), p.tools(), p.channels(),
                p.intervalMs(), p.input(), p.modelId(), p.rationale(), p.enabled() ? 1 : 0,
                p.sort(), System.currentTimeMillis(), p.id());
    }

    /** 人工刹车：启用 / 停用一份职责（停用不删，另有 {@link #deletePlan} 才是真删） */
    public int setPlanEnabled(String id, boolean enabled) {
        return jdbc.update("UPDATE heartbeat_plan SET enabled = ?, updated_at = ? WHERE id = ?",
                enabled ? 1 : 0, System.currentTimeMillis(), id);
    }

    public int deletePlan(String id) {
        return jdbc.update("DELETE FROM heartbeat_plan WHERE id = ?", id);
    }

    /** 记下该计划最近一次被派出的时间，供节拍倍数（interval_ms）判定下次该不该派 */
    public void markPlanBeat(String id, long at) {
        jdbc.update("UPDATE heartbeat_plan SET last_beat_at = ? WHERE id = ?", at, id);
    }

    // ---------- 映射 ----------

    private static HeartbeatRow mapHeartbeat(ResultSet rs, int rowNum) throws SQLException {
        return new HeartbeatRow(rs.getString("agent_id"), rs.getString("content"),
                rs.getString("model_id"), rs.getInt("enabled") != 0, rs.getLong("tick_ms"),
                optLong(rs, "last_beat_at"));
    }

    private static PlanRow mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanRow(rs.getString("id"), rs.getString("agent_id"), rs.getString("name"),
                rs.getString("role"), rs.getString("content"), rs.getString("tools"),
                rs.getString("channels"), optLong(rs, "interval_ms"), rs.getString("input"),
                rs.getString("model_id"), rs.getString("rationale"), rs.getInt("sort"),
                optLong(rs, "last_beat_at"), rs.getInt("enabled") != 0);
    }

    /** 可空整型列：sqlite-jdbc 对取值小的 INTEGER 列返回 Integer，直接强转 Long 会炸 */
    static Long optLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
