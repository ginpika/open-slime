package cc.ginpika.minis.ai.core.prompt.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 提示词「用户版本」持久化：prompt_version（每个提示词位下多份用户版本）+ prompt_binding
 * （每个提示词位选定哪一份覆盖内置）。内置提示词不入库，由
 * {@link cc.ginpika.minis.ai.core.prompt.PromptManager} 按 {@code PromptLibrary} 与 .env 合成。
 */
@Repository
public class PromptStore {

    public record VersionRow(String id, String promptKey, String name, String content,
                             long updatedAt) {
    }

    /** 绑定行：versionId 为空 = 用内置 */
    public record BindingRow(String promptKey, String versionId) {
    }

    private final JdbcTemplate jdbc;

    public PromptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 版本 ----------

    public List<VersionRow> versions() {
        return jdbc.query("SELECT id, prompt_key, name, content, updated_at FROM prompt_version "
                + "ORDER BY prompt_key, created_at", PromptStore::mapVersion);
    }

    public Optional<VersionRow> version(String id) {
        return jdbc.query("SELECT id, prompt_key, name, content, updated_at FROM prompt_version "
                        + "WHERE id = ?", PromptStore::mapVersion, id)
                .stream().findFirst();
    }

    public void insertVersion(VersionRow v) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO prompt_version(id, prompt_key, name, content, created_at, "
                        + "updated_at) VALUES(?,?,?,?,?,?)",
                v.id(), v.promptKey(), v.name(), v.content(), now, now);
    }

    public void updateVersion(String id, String name, String content) {
        jdbc.update("UPDATE prompt_version SET name = ?, content = ?, updated_at = ? WHERE id = ?",
                name, content, System.currentTimeMillis(), id);
    }

    public void deleteVersion(String id) {
        jdbc.update("DELETE FROM prompt_version WHERE id = ?", id);
    }

    // ---------- 绑定 ----------

    public List<BindingRow> bindings() {
        return jdbc.query("SELECT prompt_key, version_id FROM prompt_binding",
                (rs, n) -> new BindingRow(rs.getString("prompt_key"), rs.getString("version_id")));
    }

    public void saveBinding(String promptKey, String versionId) {
        jdbc.update("INSERT INTO prompt_binding(prompt_key, version_id, updated_at) VALUES(?,?,?) "
                        + "ON CONFLICT(prompt_key) DO UPDATE SET version_id = excluded.version_id, "
                        + "updated_at = excluded.updated_at",
                promptKey, versionId, System.currentTimeMillis());
    }

    /** 版本被删除时清掉指向它的绑定（落内置；保留行，避免迁移逻辑重复识别旧默认项） */
    public void clearBindingsOfVersion(String versionId) {
        jdbc.update("UPDATE prompt_binding SET version_id = NULL, updated_at = ? WHERE version_id = ?",
                System.currentTimeMillis(), versionId);
    }

    // ---------- 上下文内的拼装顺序 ----------

    /** 某上下文已保存的顺序：slot_key → sort（未配置的位不在表里，取注册表顺序） */
    public Map<String, Integer> orders(String contextKey) {
        Map<String, Integer> map = new HashMap<>();
        jdbc.query("SELECT slot_key, sort FROM prompt_order WHERE context_key = ?",
                rs -> {
                    map.put(rs.getString("slot_key"), rs.getInt("sort"));
                }, contextKey);
        return map;
    }

    /** 整体覆盖某上下文的顺序（先清后写，顺序即下标） */
    public void saveOrder(String contextKey, List<String> slotKeys) {
        long now = System.currentTimeMillis();
        jdbc.update("DELETE FROM prompt_order WHERE context_key = ?", contextKey);
        for (int i = 0; i < slotKeys.size(); i++) {
            jdbc.update("INSERT INTO prompt_order(context_key, slot_key, sort, updated_at) "
                    + "VALUES(?,?,?,?)", contextKey, slotKeys.get(i), i, now);
        }
    }

    private static VersionRow mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new VersionRow(rs.getString("id"), rs.getString("prompt_key"), rs.getString("name"),
                rs.getString("content"), rs.getLong("updated_at"));
    }
}
