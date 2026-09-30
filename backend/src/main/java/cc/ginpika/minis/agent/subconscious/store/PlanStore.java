package cc.ginpika.minis.agent.subconscious.store;

import cc.ginpika.minis.agent.runtime.ExecStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 潜意识模块的「编制」持久化：sub_heartbeat（心跳主会话的上下文＝总线）/ sub_plan（任务计划）。
 *
 * <p>编制是长期状态：心跳主会话只有一行，任务计划是一份名册。谁跑了一次、跑得怎么样，
 * 不在这一层——那属于 {@link ExecStore}（sub_run / sub_exec / sub_exec_edit）。
 *
 * <p>子代理（匿名 worker）没有编制：它不留名册，故这里没有它的行；它的执行同样落在 sub_exec。
 */
@Repository
public class PlanStore {

    /** 心跳主会话在 sub_heartbeat 里的固定单行 id */
    public static final String HEARTBEAT_ID = "heartbeat";

    /**
     * 心跳主会话（总线）：心跳自己的那份上下文——正文（含 {{插槽}}）+ 插槽绑定 + 模型。
     * 每拍先由它出场，看着任务计划名册决定这一拍派谁、怎么派。
     */
    public record HeartbeatRow(String content, String injectors, String modelId) {
    }

    /** 任务计划：一份常驻编制——提示词正文（含 {{插槽}}）+ 角色 + 允许调用的工具 +
     *  插槽绑定 + 节拍参考（interval_ms）+ 交付渠道。由大模型经工具自行创建与维护，
     *  没有「启用」开关：派不派完全由心跳主会话看着名册决定 */
    public record PlanRow(String id, String name, String role, String content, String tools,
                          String injectors, String channels, Long intervalMs,
                          String input, String modelId, int sort, Long lastBeatAt) {
    }

    private final JdbcTemplate jdbc;

    public PlanStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 心跳主会话（总线） ----------

    /** 主会话的上下文：库里没配过（或整行缺失）时返回 empty，由调用方退到内置正文 */
    public Optional<HeartbeatRow> heartbeat() {
        return jdbc.query("SELECT content, injectors, model_id FROM sub_heartbeat WHERE id = ?",
                        (rs, n) -> new HeartbeatRow(rs.getString("content"),
                                rs.getString("injectors"), rs.getString("model_id")),
                        HEARTBEAT_ID)
                .stream().findFirst();
    }

    /** 主会话只有一行：整体覆盖写（id 固定，不存在则插入） */
    public void saveHeartbeat(HeartbeatRow h) {
        jdbc.update(
                "INSERT OR REPLACE INTO sub_heartbeat(id, content, injectors, model_id, updated_at) "
                        + "VALUES(?,?,?,?,?)",
                HEARTBEAT_ID, h.content(), h.injectors(), h.modelId(), System.currentTimeMillis());
    }

    // ---------- 任务计划 ----------

    /** 列清单（plans / plan 共用）：顺序与 {@link #mapPlan} 对应 */
    private static final String PLAN_COLUMNS =
            "id, name, role, content, tools, injectors, channels, interval_ms, input, "
                    + "model_id, sort, last_beat_at";

    public List<PlanRow> plans() {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM sub_plan ORDER BY sort, id",
                PlanStore::mapPlan);
    }

    public Optional<PlanRow> plan(String id) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM sub_plan WHERE id = ?",
                        PlanStore::mapPlan, id)
                .stream().findFirst();
    }

    /** 按名字找计划：名字是模型派活时最顺手的引用（id 是 uuid，对模型不友好） */
    public Optional<PlanRow> planByName(String name) {
        return jdbc.query("SELECT " + PLAN_COLUMNS + " FROM sub_plan WHERE name = ? ORDER BY sort, id",
                        PlanStore::mapPlan, name)
                .stream().findFirst();
    }

    public void insertPlan(PlanRow p) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO sub_plan(id, name, role, content, tools, injectors, channels, "
                        + "interval_ms, input, model_id, sort, created_at, "
                        + "updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(), p.name(), p.role(), p.content(), p.tools(), p.injectors(), p.channels(),
                p.intervalMs(), p.input(), p.modelId(), p.sort(), now, now);
    }

    public int updatePlan(PlanRow p) {
        return jdbc.update(
                "UPDATE sub_plan SET name = ?, role = ?, content = ?, tools = ?, injectors = ?, "
                        + "channels = ?, interval_ms = ?, input = ?, model_id = ?, "
                        + "sort = ?, updated_at = ? WHERE id = ?",
                p.name(), p.role(), p.content(), p.tools(), p.injectors(), p.channels(),
                p.intervalMs(), p.input(), p.modelId(), p.sort(),
                System.currentTimeMillis(), p.id());
    }

    public int deletePlan(String id) {
        return jdbc.update("DELETE FROM sub_plan WHERE id = ?", id);
    }

    /** 记下该计划最近一次被派出的时间，供节拍倍数（interval_ms）判定下次该不该派 */
    public void markPlanBeat(String id, long at) {
        jdbc.update("UPDATE sub_plan SET last_beat_at = ? WHERE id = ?", at, id);
    }

    // ---------- 映射 ----------

    private static PlanRow mapPlan(ResultSet rs, int rowNum) throws SQLException {
        return new PlanRow(rs.getString("id"), rs.getString("name"), rs.getString("role"),
                rs.getString("content"), rs.getString("tools"), rs.getString("injectors"),
                rs.getString("channels"), optLong(rs, "interval_ms"), rs.getString("input"),
                rs.getString("model_id"), rs.getInt("sort"), (Long) rs.getObject("last_beat_at"));
    }

    /** 可空整型列：sqlite-jdbc 对取值小的 INTEGER 列返回 Integer，直接强转 Long 会炸 */
    static Long optLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
