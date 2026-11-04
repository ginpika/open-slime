package cc.ginpika.minis.agent.member;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * agent（多智能体成员名册）。
 *
 * <p>成员由人在「多智能体 · 成员」页配置，<b>不由大模型自建</b>——它是一份常驻岗位名册，
 * 与大模型自己攒出来的编制（{@code heartbeat_plan}）语义不同：那边是「按节拍被派出去的活」，
 * 这边是「在圆桌上坐着的人」。
 *
 * <p>身份由每位成员自己那套「对话」提示词决定（见 {@code agent_prompt_*}）；这一行只回答
 * 「是不是内置 Agent、额外能调什么工具、用哪个模型、坐第几位」。
 */
@Repository
public class AgentStore {

    /** 一个成员：builtin=1 的是内置 Agent（不可删除、默认存在）；
     *  tools 为 JSON 数组文本（允许调用的额外工具名），NULL=默认只读检索；
     *  terminals 为 JSON 数组文本（绑定给它的工作空间 term_target.id），NULL/空=不能用 run_command；
     *  anchorId 为该成员的图谱人格锚点（entities.id），NULL=尚未建立 */
    public record AgentRow(String id, String name, boolean builtin, String tools, String terminals,
                           String modelId, int sort, String note, String avatar, String anchorId,
                           long createdAt, long updatedAt, String role, String roleColor) {
    }

    private static final String COLS =
            "id, name, builtin, tools, terminals, model_id, sort, note, avatar, anchor_id, "
                    + "created_at, updated_at, role, role_color";

    private final JdbcTemplate jdbc;

    public AgentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部成员，按发言顺序（sort 相同则按建立时间，保证顺序稳定不抖动） */
    public List<AgentRow> peers() {
        return jdbc.query("SELECT " + COLS + " FROM agent WHERE id <> 'builtin-coding' ORDER BY sort, created_at, id",
                AgentStore::map);
    }

    public Optional<AgentRow> peer(String id) {
        return jdbc.query("SELECT " + COLS + " FROM agent WHERE id = ? AND id <> 'builtin-coding'", AgentStore::map, id)
                .stream().findFirst();
    }

    /** 按展示名查成员（发言里的 @名字 用它定位；同名取最早建立的那个） */
    public Optional<AgentRow> peerByName(String name) {
        return jdbc.query("SELECT " + COLS + " FROM agent WHERE name = ? AND id <> 'builtin-coding' ORDER BY created_at, id",
                        AgentStore::map, name)
                .stream().findFirst();
    }

    public void insert(AgentRow row) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO agent(id, name, builtin, tools, terminals, model_id, sort, "
                        + "note, avatar, anchor_id, created_at, updated_at, role, role_color) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.name(), row.builtin() ? 1 : 0, row.tools(), row.terminals(),
                row.modelId(), row.sort(), row.note(), row.avatar(), row.anchorId(), now, now,
                row.role(), row.roleColor());
    }

    public void update(AgentRow row) {
        jdbc.update("UPDATE agent SET name = ?, builtin = ?, tools = ?, terminals = ?, "
                        + "model_id = ?, sort = ?, note = ?, avatar = ?, anchor_id = ?, "
                        + "updated_at = ?, role = ?, role_color = ? WHERE id = ?",
                row.name(), row.builtin() ? 1 : 0, row.tools(), row.terminals(), row.modelId(),
                row.sort(), row.note(), row.avatar(), row.anchorId(),
                System.currentTimeMillis(), row.role(), row.roleColor(), row.id());
    }

    /** 绑定 / 解除这位成员的图谱人格锚点（anchorId 为 null = 解除绑定；锚点实体本身不动） */
    public void setAnchor(String peerId, String anchorId) {
        jdbc.update("UPDATE agent SET anchor_id = ?, updated_at = ? WHERE id = ?",
                anchorId, System.currentTimeMillis(), peerId);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM agent WHERE id = ? AND id <> 'builtin-coding'", id);
    }

    /** 现有最大排序号（新成员排到末尾）；空名册返回 0 */
    public int maxSort() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM agent", Integer.class);
        return max == null ? 0 : max;
    }

    private static AgentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRow(rs.getString("id"), rs.getString("name"), rs.getInt("builtin") != 0,
                rs.getString("tools"), rs.getString("terminals"), rs.getString("model_id"),
                rs.getInt("sort"), rs.getString("note"), rs.getString("avatar"),
                rs.getString("anchor_id"), rs.getLong("created_at"), rs.getLong("updated_at"),
                rs.getString("role"), rs.getString("role_color"));
    }
}
