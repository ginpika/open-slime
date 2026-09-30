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
 * hub_session 结构迁移（幂等，启动时执行一次）。
 *
 * <p>两条路径：
 * <ol>
 *   <li>旧结构重建：Yuni BFF 时代的旧表含 yuni_session_id NOT NULL UNIQUE / last_synced_message_id 列；
 *       新架构 INSERT 不再携带 yuni_session_id → NOT NULL 违反 → 500（schema.sql 注释已预告需重建）。
 *       检测到旧列时事务性重建表，保留 id/title/mode/created_at/updated_at 与 chat_message 数据。</li>
 *   <li>配置列补齐：hub_session 后加的会话配置列（model_id / prompt_id / show_cot / context_trace /
 *       max_rounds / show_rounds，存「…」菜单在该会话的选择），schema.sql 的 CREATE TABLE IF NOT EXISTS
 *       不给旧表补列，故在此按缺 ALTER TABLE ADD COLUMN。</li>
 * </ol>
 * 时机：注入 DataSource 的 bean 构造时 schema.sql 已由 sql init 执行完（Spring Boot 保证）。
 */
@Component
public class HubSessionMigrator {

    private static final Logger log = LoggerFactory.getLogger(HubSessionMigrator.class);

    private final DataSource dataSource;

    public HubSessionMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            if (hasLegacyColumn(c)) {
                rebuildLegacy(c);
            }
            ensureConfigColumns(c);
        } catch (SQLException e) {
            throw new IllegalStateException("hub_session 迁移失败: " + e.getMessage(), e);
        }
    }

    /** 旧结构（含 yuni_session_id）重建为新结构；重建表即含最新配置列 */
    private void rebuildLegacy(Connection c) throws SQLException {
        log.info("检测到旧版 hub_session（含 yuni_session_id），开始迁移重建");
        // PRAGMA 不能在事务内生效，先关外键再开事务（DROP 旧表时避免 chat_message 外键报错）
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
        }
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE hub_session_migrated (
                      id          TEXT PRIMARY KEY,
                      title       TEXT,
                      mode        TEXT NOT NULL DEFAULT 'ai',
                      model_id    TEXT,
                      prompt_id   TEXT,
                      show_cot    INTEGER NOT NULL DEFAULT 1,
                      context_trace INTEGER NOT NULL DEFAULT 0,
                      max_rounds  INTEGER NOT NULL DEFAULT 30,
                      show_rounds INTEGER NOT NULL DEFAULT 0,
                      unread      INTEGER NOT NULL DEFAULT 0,
                      created_at  INTEGER NOT NULL,
                      updated_at  INTEGER
                    )""");
            st.execute("""
                    INSERT INTO hub_session_migrated(id, title, mode, created_at, updated_at)
                    SELECT id, title, mode, created_at, updated_at FROM hub_session""");
            st.execute("DROP TABLE hub_session");
            // RENAME 后 chat_message 的外键（引用 hub_session 文本未变）重新指向新表
            st.execute("ALTER TABLE hub_session_migrated RENAME TO hub_session");
            c.commit();
            log.info("hub_session 迁移完成");
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** 补齐会话配置列（幂等；新库已由 schema.sql 建好，检测到即跳过） */
    private void ensureConfigColumns(Connection c) throws SQLException {
        addColumnIfMissing(c, "model_id", "ALTER TABLE hub_session ADD COLUMN model_id TEXT");
        addColumnIfMissing(c, "prompt_id", "ALTER TABLE hub_session ADD COLUMN prompt_id TEXT");
        addColumnIfMissing(c, "show_cot",
                "ALTER TABLE hub_session ADD COLUMN show_cot INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(c, "context_trace",
                "ALTER TABLE hub_session ADD COLUMN context_trace INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "max_rounds",
                "ALTER TABLE hub_session ADD COLUMN max_rounds INTEGER NOT NULL DEFAULT 30");
        addColumnIfMissing(c, "show_rounds",
                "ALTER TABLE hub_session ADD COLUMN show_rounds INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "unread",
                "ALTER TABLE hub_session ADD COLUMN unread INTEGER NOT NULL DEFAULT 0");
    }

    private void addColumnIfMissing(Connection c, String column, String ddl) throws SQLException {
        if (hasColumn(c, column)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute(ddl);
        }
        log.info("hub_session 已补 {} 列", column);
    }

    private static boolean hasLegacyColumn(Connection c) throws SQLException {
        return hasColumn(c, "yuni_session_id");
    }

    private static boolean hasColumn(Connection c, String column) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM pragma_table_info('hub_session') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
