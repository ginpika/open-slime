package cc.ginpika.minis.ai.core.prompt.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 提示词「用户版本」体系迁移（幂等，启动时执行一次）。
 *
 * <p>旧模型：聊天人格独占 {@code system_prompt} 表，默认项存 {@code app_setting.default_prompt_id}，
 * 内置项 {@code env}（.env 的 LLM_SYSTEM_PROMPT）不入库。新模型：所有写死的提示词统一为
 * 「提示词位 + 版本」（{@code prompt_version} / {@code prompt_binding}），聊天人格即
 * {@code identity} 位，.env 仍是它的内置默认。
 *
 * <p>迁移动作：
 * <ol>
 *   <li>{@code system_prompt} 存在 → 逐行迁入 {@code prompt_version}（prompt_key='identity'，id 原样保留，
 *       故 {@code hub_session.prompt_id} 无需改写），随后 DROP 旧表；</li>
 *   <li>{@code app_setting.default_prompt_id} 有值且 identity 尚无绑定行 → 迁入 {@code prompt_binding}；
 *       列本身保留（SQLite 掉列代价大），此后不再读写；</li>
 *   <li>旧内置项 id {@code env} 统一改叫 {@code builtin}（会话级选择里也一并改写）。</li>
 * </ol>
 * 时机：注入 DataSource 的 bean 构造时 schema.sql 已由 sql init 执行完（Spring Boot 保证）。
 */
@Component
public class PromptMigrator {

    private static final Logger log = LoggerFactory.getLogger(PromptMigrator.class);

    /** 对话身份提示词位（见 IdentityBlock.KEY） */
    private static final String IDENTITY = "identity";

    private final DataSource dataSource;

    public PromptMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            migratePersonas(c);
            migrateDefaultBinding(c);
            renameLegacyEnvChoice(c);
        } catch (SQLException e) {
            throw new IllegalStateException("提示词迁移失败: " + e.getMessage(), e);
        }
    }

    /** 旧 system_prompt 逐行迁入 identity 位；id 原样保留，迁完 DROP 旧表 */
    private void migratePersonas(Connection c) throws SQLException {
        if (!hasTable(c, "system_prompt")) {
            return;
        }
        try (Statement st = c.createStatement()) {
            int copied = st.executeUpdate(
                    "INSERT OR IGNORE INTO prompt_version(id, prompt_key, name, content, "
                            + "created_at, updated_at) "
                            + "SELECT id, '" + IDENTITY + "', name, content, created_at, updated_at "
                            + "FROM system_prompt");
            st.execute("DROP TABLE system_prompt");
            log.info("提示词迁移：system_prompt → prompt_version(identity)，共 {} 行", copied);
        }
    }

    /** 旧默认人格 → identity 位绑定；identity 已有绑定行（哪怕为空 = 内置）即视为已迁移 */
    private void migrateDefaultBinding(Connection c) throws SQLException {
        if (!hasColumn(c, "app_setting", "default_prompt_id") || hasIdentityBinding(c)) {
            return;
        }
        String versionId;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT default_prompt_id FROM app_setting WHERE id = 1")) {
            if (!rs.next()) {
                return;
            }
            versionId = rs.getString("default_prompt_id");
        }
        /* 空/旧内置项（env）都表示「用内置」，无需迁绑定 */
        if (versionId == null || versionId.isBlank() || "env".equals(versionId)) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO prompt_binding(prompt_key, version_id, updated_at) VALUES(?,?,?)")) {
            ps.setString(1, IDENTITY);
            ps.setString(2, versionId);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        log.info("提示词迁移：默认人格 {} → prompt_binding(identity)", versionId);
    }

    /** 内置项旧 id env → builtin（会话级提示词选择） */
    private void renameLegacyEnvChoice(Connection c) throws SQLException {
        if (!hasTable(c, "hub_session")) {
            return;
        }
        try (Statement st = c.createStatement()) {
            int n = st.executeUpdate("UPDATE hub_session SET prompt_id = 'builtin' "
                    + "WHERE prompt_id = 'env'");
            if (n > 0) {
                log.info("提示词迁移：hub_session.prompt_id env → builtin，共 {} 行", n);
            }
        }
    }

    private static boolean hasIdentityBinding(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM prompt_binding "
                     + "WHERE prompt_key = '" + IDENTITY + "'")) {
            return rs.next();
        }
    }

    private static boolean hasTable(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pragma_table_info('" + table + "') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
