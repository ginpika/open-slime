package cc.ginpika.minis.agent.multiagent.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ma_peer（多智能体成员名册）。
 *
 * <p>成员由人在「多智能体 · 成员」页配置，<b>不由大模型自建</b>——它是一份常驻岗位名册，
 * 与大模型自己攒出来的编制（{@code sub_plan}）语义不同：那边是「按节拍被派出去的活」，
 * 这边是「在圆桌上坐着的人」。
 *
 * <p>身份不在这里存正文：{@code prompt_id} 指向 {@code prompt_version} 里 identity 位
 * （「对话身份」）的某个版本，与人设编辑入口（提示词页）保持单一来源；这一行只回答
 * 「用哪份身份、额外能调什么工具、用哪个模型、坐第几位」。
 */
@Repository
public class MaPeerStore {

    /** 一个成员：tools 为 JSON 数组文本（允许调用的额外工具名），NULL=默认只读检索；
     *  terminals 为 JSON 数组文本（绑定给它的工作空间 term_target.id），NULL/空=不能用 run_command；
     *  anchorId 为该成员的图谱人格锚点（entities.id），NULL=尚未建立 */
    public record PeerRow(String id, String name, String promptId, String tools, String terminals,
                          String modelId, int sort, String note, String avatar, String anchorId,
                          long createdAt, long updatedAt) {
    }

    private static final String COLS =
            "id, name, prompt_id, tools, terminals, model_id, sort, note, avatar, anchor_id, "
                    + "created_at, updated_at";

    private final JdbcTemplate jdbc;

    public MaPeerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部成员，按发言顺序（sort 相同则按建立时间，保证顺序稳定不抖动） */
    public List<PeerRow> peers() {
        return jdbc.query("SELECT " + COLS + " FROM ma_peer ORDER BY sort, created_at, id",
                MaPeerStore::map);
    }

    public Optional<PeerRow> peer(String id) {
        return jdbc.query("SELECT " + COLS + " FROM ma_peer WHERE id = ?", MaPeerStore::map, id)
                .stream().findFirst();
    }

    /** 按展示名查成员（发言里的 @名字 用它定位；同名取最早建立的那个） */
    public Optional<PeerRow> peerByName(String name) {
        return jdbc.query("SELECT " + COLS + " FROM ma_peer WHERE name = ? ORDER BY created_at, id",
                        MaPeerStore::map, name)
                .stream().findFirst();
    }

    public void insert(PeerRow row) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO ma_peer(id, name, prompt_id, tools, terminals, model_id, sort, "
                        + "note, avatar, anchor_id, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.name(), row.promptId(), row.tools(), row.terminals(), row.modelId(),
                row.sort(), row.note(), row.avatar(), row.anchorId(), now, now);
    }

    public void update(PeerRow row) {
        jdbc.update("UPDATE ma_peer SET name = ?, prompt_id = ?, tools = ?, terminals = ?, "
                        + "model_id = ?, sort = ?, note = ?, avatar = ?, anchor_id = ?, "
                        + "updated_at = ? WHERE id = ?",
                row.name(), row.promptId(), row.tools(), row.terminals(), row.modelId(), row.sort(),
                row.note(), row.avatar(), row.anchorId(), System.currentTimeMillis(), row.id());
    }

    /** 绑定 / 解除这位成员的图谱人格锚点（anchorId 为 null = 解除绑定；锚点实体本身不动） */
    public void setAnchor(String peerId, String anchorId) {
        jdbc.update("UPDATE ma_peer SET anchor_id = ?, updated_at = ? WHERE id = ?",
                anchorId, System.currentTimeMillis(), peerId);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM ma_peer WHERE id = ?", id);
    }

    /** 现有最大排序号（新成员排到末尾）；空名册返回 0 */
    public int maxSort() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort), 0) FROM ma_peer", Integer.class);
        return max == null ? 0 : max;
    }

    private static PeerRow map(ResultSet rs, int rowNum) throws SQLException {
        return new PeerRow(rs.getString("id"), rs.getString("name"), rs.getString("prompt_id"),
                rs.getString("tools"), rs.getString("terminals"), rs.getString("model_id"),
                rs.getInt("sort"), rs.getString("note"), rs.getString("avatar"),
                rs.getString("anchor_id"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
