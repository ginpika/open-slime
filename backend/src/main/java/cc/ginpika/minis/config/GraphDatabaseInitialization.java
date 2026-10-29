package cc.ginpika.minis.config;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Rename the existing claim table before schema initialization, preserving IDs and SQLite foreign keys. */
@Configuration(proxyBeanMethods = false)
public class GraphDatabaseInitialization {
    @Bean
    SqlDataSourceScriptDatabaseInitializer dataSourceScriptDatabaseInitializer(
            DataSource dataSource, SqlInitializationProperties properties) {
        upgradeLegacyClaims(dataSource);
        return new SqlDataSourceScriptDatabaseInitializer(dataSource, properties);
    }

    public static void upgradeLegacyClaims(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer legacy = jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='memory_item'", Integer.class);
        if (legacy == null || legacy == 0) return;
        if (jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='graph_claim'", Integer.class) != 0) {
            throw new IllegalStateException("memory_item 与 graph_claim 同时存在，需先检查迁移状态，不能覆盖任何一份数据");
        }
        String file = jdbc.query("PRAGMA database_list", rs -> {
            while (rs.next()) if ("main".equals(rs.getString("name"))) return rs.getString("file");
            return null;
        });
        if (file != null && !file.isBlank()) {
            try {
                Path backups = Path.of(file).toAbsolutePath().getParent().resolve("backups");
                Files.createDirectories(backups);
                String target = backups.resolve("before-graph-" + System.currentTimeMillis() + ".db").toString().replace("'", "''");
                jdbc.execute("VACUUM INTO '" + target + "'");
            } catch (Exception e) { throw new IllegalStateException("图谱迁移前备份失败，已停止迁移", e); }
        }
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
                jdbc.execute("ALTER TABLE memory_item RENAME TO graph_claim"));
    }
}
