package cc.ginpika.minis.config.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * llm_model 增量迁移（幂等，启动时执行一次）。
 *
 * <p>schema.sql 的 CREATE TABLE IF NOT EXISTS 不会给已存在的旧表补列，故旧库在此补
 * context_window（上下文窗口，token 数）列；新库已由 schema.sql 建好，检测到该列即跳过。
 * 时机：注入 DataSource 的 bean 构造时 schema.sql 已由 sql init 执行完（Spring Boot 保证）。
 */
@Component
public class LlmModelMigrator {

    private static final Logger log = LoggerFactory.getLogger(LlmModelMigrator.class);

    private final DataSource dataSource;

    public LlmModelMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            if (hasContextWindowColumn(c)) {
                return;
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE llm_model ADD COLUMN context_window INTEGER");
            }
            log.info("llm_model 已补 context_window（上下文窗口）列");
        } catch (SQLException e) {
            throw new IllegalStateException("llm_model 迁移失败: " + e.getMessage(), e);
        }
    }

    private static boolean hasContextWindowColumn(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pragma_table_info('llm_model') WHERE name='context_window'")) {
            return rs.next();
        }
    }
}
