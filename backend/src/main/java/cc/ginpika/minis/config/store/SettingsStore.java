package cc.ginpika.minis.config.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cc.ginpika.minis.ai.core.prompt.store.PromptStore;

/**
 * 设置页持久化：llm_model / app_setting / embedding_config / rerank_config。
 * 内置 env 项不入库，由 SettingsService 按 LlmProps 合成；提示词的用户版本见 {@link PromptStore}。
 */
@Repository
public class SettingsStore {

    public record ModelRow(String id, String name, String baseUrl, String apiKey, String model,
                           Integer contextWindow) {
    }

    /** 默认模型选择、Embedding 与 Rerank 配置均为单行（id=1），无行时返回 null */
    public record SettingRow(String defaultModelId) {
    }

    public record EmbeddingRow(String mode, String llmModelId, String baseUrl, String apiKey,
                               String model) {
    }

    public record RerankRow(String mode, String llmModelId, String baseUrl, String apiKey,
                            String model) {
    }

    private final JdbcTemplate jdbc;

    public SettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 模型 ----------

    public List<ModelRow> models() {
        return jdbc.query(
                "SELECT id, name, base_url, api_key, model, context_window FROM llm_model "
                        + "ORDER BY created_at",
                SettingsStore::mapModel);
    }

    public Optional<ModelRow> model(String id) {
        return jdbc.query(
                        "SELECT id, name, base_url, api_key, model, context_window "
                                + "FROM llm_model WHERE id = ?",
                        SettingsStore::mapModel, id)
                .stream().findFirst();
    }

    public void insertModel(ModelRow m) {
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO llm_model(id, name, base_url, api_key, model, context_window, "
                        + "created_at, updated_at) VALUES(?,?,?,?,?,?,?,?)",
                m.id(), m.name(), m.baseUrl(), m.apiKey(), m.model(), m.contextWindow(), now, now);
    }

    public void updateModel(ModelRow m) {
        jdbc.update(
                "UPDATE llm_model SET name = ?, base_url = ?, api_key = ?, model = ?, "
                        + "context_window = ?, updated_at = ? WHERE id = ?",
                m.name(), m.baseUrl(), m.apiKey(), m.model(), m.contextWindow(),
                System.currentTimeMillis(), m.id());
    }

    public void deleteModel(String id) {
        jdbc.update("DELETE FROM llm_model WHERE id = ?", id);
    }

    // ---------- 默认模型选择 ----------

    public Optional<SettingRow> setting() {
        return jdbc.query(
                        "SELECT default_model_id FROM app_setting WHERE id = 1",
                        (rs, n) -> new SettingRow(rs.getString("default_model_id")))
                .stream().findFirst();
    }

    public void saveSetting(String defaultModelId) {
        jdbc.update(
                "INSERT INTO app_setting(id, default_model_id, updated_at) "
                        + "VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET "
                        + "default_model_id = excluded.default_model_id, "
                        + "updated_at = excluded.updated_at",
                defaultModelId, System.currentTimeMillis());
    }

    // ---------- Embedding ----------

    public Optional<EmbeddingRow> embedding() {
        return jdbc.query(
                        "SELECT mode, llm_model_id, base_url, api_key, model "
                                + "FROM embedding_config WHERE id = 1",
                        (rs, n) -> new EmbeddingRow(rs.getString("mode"),
                                rs.getString("llm_model_id"), rs.getString("base_url"),
                                rs.getString("api_key"), rs.getString("model")))
                .stream().findFirst();
    }

    public void saveEmbedding(EmbeddingRow e) {
        jdbc.update(
                "INSERT INTO embedding_config(id, mode, llm_model_id, base_url, api_key, model, "
                        + "updated_at) VALUES(1,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                        + "mode = excluded.mode, llm_model_id = excluded.llm_model_id, "
                        + "base_url = excluded.base_url, api_key = excluded.api_key, "
                        + "model = excluded.model, updated_at = excluded.updated_at",
                e.mode(), e.llmModelId(), e.baseUrl(), e.apiKey(), e.model(),
                System.currentTimeMillis());
    }

    // ---------- Rerank ----------

    public Optional<RerankRow> rerank() {
        return jdbc.query(
                        "SELECT mode, llm_model_id, base_url, api_key, model "
                                + "FROM rerank_config WHERE id = 1",
                        (rs, n) -> new RerankRow(rs.getString("mode"),
                                rs.getString("llm_model_id"), rs.getString("base_url"),
                                rs.getString("api_key"), rs.getString("model")))
                .stream().findFirst();
    }

    public void saveRerank(RerankRow r) {
        jdbc.update(
                "INSERT INTO rerank_config(id, mode, llm_model_id, base_url, api_key, model, "
                        + "updated_at) VALUES(1,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET "
                        + "mode = excluded.mode, llm_model_id = excluded.llm_model_id, "
                        + "base_url = excluded.base_url, api_key = excluded.api_key, "
                        + "model = excluded.model, updated_at = excluded.updated_at",
                r.mode(), r.llmModelId(), r.baseUrl(), r.apiKey(), r.model(),
                System.currentTimeMillis());
    }

    private static ModelRow mapModel(ResultSet rs, int rowNum) throws SQLException {
        return new ModelRow(rs.getString("id"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("api_key"), rs.getString("model"),
                (Integer) rs.getObject("context_window"));
    }
}
