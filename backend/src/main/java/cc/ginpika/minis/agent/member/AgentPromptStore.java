package cc.ginpika.minis.agent.member;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 成员提示词持久化：agent_prompt_version（每个成员每个提示词位下多份版本）+
 * agent_prompt_binding（成员选定哪一份生效）+ agent_prompt_order（成员上下文内的拼装顺序）。
 *
 * <p>与全局的 {@code PromptStore} 同构但<b>按成员隔离</b>：首页对话用全局那一套，圆桌上的每位成员
 * 各用自己的一套，互不干扰。内置提示词不入库，由 {@code AgentPromptService} 回退到代码内置合成。
 */
@Repository
public class AgentPromptStore {

    /** 一个成员的一份提示词版本 */
    public record VersionRow(String id, String peerId, String promptKey, String name, String content,
                             long updatedAt) {
    }

    /** 绑定行：versionId 为空 = 回退全局（提示词页绑定 → 内置） */
    public record BindingRow(String promptKey, String versionId) {
    }

    private static final String VERSION_COLS = "id, agent_id, prompt_key, name, content, updated_at";

    private final JdbcTemplate jdbc;

    public AgentPromptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 版本 ----------

    /** 某成员的全部提示词版本（按提示词位、建立时间排） */
    public List<VersionRow> versions(String peerId) {
        return jdbc.query("SELECT " + VERSION_COLS + " FROM agent_prompt_version "
                + "WHERE agent_id = ? ORDER BY prompt_key, created_at", AgentPromptStore::map, peerId);
    }

    /** 按 id 取版本（归属由调用方校验：同一个 id 只可能属于一位成员） */
    public Optional<VersionRow> version(String id) {
        return jdbc.query("SELECT " + VERSION_COLS + " FROM agent_prompt_version WHERE id = ?",
                        AgentPromptStore::map, id)
                .stream().findFirst();
    }

    public void insertVersion(VersionRow v) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO agent_prompt_version(id, agent_id, prompt_key, name, content, "
                        + "created_at, updated_at) VALUES(?,?,?,?,?,?,?)",
                v.id(), v.peerId(), v.promptKey(), v.name(), v.content(), now, now);
    }

    public void updateVersion(String id, String name, String content) {
        jdbc.update("UPDATE agent_prompt_version SET name = ?, content = ?, updated_at = ? "
                + "WHERE id = ?", name, content, System.currentTimeMillis(), id);
    }

    public void deleteVersion(String id) {
        jdbc.update("DELETE FROM agent_prompt_version WHERE id = ?", id);
    }

    // ---------- 绑定 ----------

    /** 某成员的全部绑定（提示词位 → 版本 id，允许无行/空值） */
    public List<BindingRow> bindings(String peerId) {
        return jdbc.query("SELECT prompt_key, version_id FROM agent_prompt_binding "
                        + "WHERE agent_id = ?",
                (rs, n) -> new BindingRow(rs.getString("prompt_key"), rs.getString("version_id")),
                peerId);
    }

    /** 某成员某提示词位选定的版本；无行 / 行在但值为空都返回空（空 = 回退全局） */
    public Optional<String> binding(String peerId, String promptKey) {
        List<String> rows = jdbc.query("SELECT version_id FROM agent_prompt_binding WHERE agent_id = ? "
                        + "AND prompt_key = ?",
                (rs, n) -> rs.getString("version_id"), peerId, promptKey);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public void saveBinding(String peerId, String promptKey, String versionId) {
        jdbc.update("INSERT INTO agent_prompt_binding(agent_id, prompt_key, version_id, updated_at) "
                        + "VALUES(?,?,?,?) ON CONFLICT(agent_id, prompt_key) DO UPDATE SET "
                        + "version_id = excluded.version_id, updated_at = excluded.updated_at",
                peerId, promptKey, versionId, System.currentTimeMillis());
    }

    /** 版本被删除时清掉指向它的绑定（落回全局；保留行，避免重复识别） */
    public void clearBindingsOfVersion(String versionId) {
        jdbc.update("UPDATE agent_prompt_binding SET version_id = NULL, updated_at = ? "
                + "WHERE version_id = ?", System.currentTimeMillis(), versionId);
    }

    // ---------- 上下文内的拼装顺序 ----------

    /** 某成员某上下文已保存的顺序：slot_key → sort（未配置的位不在表里，取注册表顺序） */
    public Map<String, Integer> orders(String peerId, String contextKey) {
        Map<String, Integer> map = new HashMap<>();
        jdbc.query("SELECT slot_key, sort FROM agent_prompt_order WHERE agent_id = ? "
                        + "AND context_key = ?",
                rs -> {
                    map.put(rs.getString("slot_key"), rs.getInt("sort"));
                }, peerId, contextKey);
        return map;
    }

    /** 整体覆盖某成员某上下文的顺序（先清后写，顺序即下标） */
    public void saveOrder(String peerId, String contextKey, List<String> slotKeys) {
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM agent_prompt_order WHERE agent_id = ? AND context_key = ?",
                peerId, contextKey);
        for (int i = 0; i < slotKeys.size(); i++) {
            jdbc.update("INSERT INTO agent_prompt_order(agent_id, context_key, slot_key, sort, "
                            + "updated_at) VALUES(?,?,?,?,?)",
                    peerId, contextKey, slotKeys.get(i), i, now);
        }
    }

    // ---------- 清理 ----------

    /** 成员被移出圆桌时，连同它自己那套提示词一并清掉（版本 / 绑定 / 顺序） */
    public void deleteAll(String peerId) {
        jdbc.update("DELETE FROM agent_prompt_order WHERE agent_id = ?", peerId);
        jdbc.update("DELETE FROM agent_prompt_binding WHERE agent_id = ?", peerId);
        jdbc.update("DELETE FROM agent_prompt_version WHERE agent_id = ?", peerId);
    }

    private static VersionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(rs.getString("id"), rs.getString("agent_id"),
                rs.getString("prompt_key"), rs.getString("name"), rs.getString("content"),
                rs.getLong("updated_at"));
    }
}
