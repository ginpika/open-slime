package cc.ginpika.minis.agent.chat.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * chat_message 增量迁移（幂等，启动时执行一次）。
 *
 * <p>schema.sql 的 CREATE TABLE IF NOT EXISTS 不会给已存在的旧表补列，故旧库在此补
 * reasoning（思维链 CoT）/ trace（思考分段）/ tool_rounds（工具轮原生消息）/ kind（消息形态）
 * / meta（形态元信息）列；新库已由 schema.sql 建好，检测到该列即跳过。
 * 时机：注入 DataSource 的 bean 构造时 schema.sql 已由 sql init 执行完（Spring Boot 保证）。
 */
@Component
public class ChatMessageMigrator {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageMigrator.class);

    private final DataSource dataSource;

    public ChatMessageMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            addColumnIfMissing(c, "reasoning", "TEXT", "思维链");
            addColumnIfMissing(c, "trace", "TEXT", "思考分段");
            addColumnIfMissing(c, "tool_rounds", "TEXT", "工具轮原生消息");
            addColumnIfMissing(c, "kind", "TEXT", "消息形态");
            addColumnIfMissing(c, "meta", "TEXT", "形态元信息");
        } catch (SQLException e) {
            throw new IllegalStateException("chat_message 迁移失败: " + e.getMessage(), e);
        }
    }

    /** 缺列才补（列名/类型均为类内常量，无注入风险） */
    private static void addColumnIfMissing(Connection c, String column, String type, String label)
            throws SQLException {
        if (hasColumn(c, column)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE chat_message ADD COLUMN " + column + " " + type);
        }
        log.info("chat_message 已补 {}({})列", column, label);
    }

    private static boolean hasColumn(Connection c, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pragma_table_info('chat_message') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
