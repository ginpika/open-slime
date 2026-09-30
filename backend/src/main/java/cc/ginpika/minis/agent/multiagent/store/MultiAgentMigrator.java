package cc.ginpika.minis.agent.multiagent.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 多智能体结构迁移（幂等，启动时执行一次）。
 *
 * <p>本模块的表都由 schema.sql 建；这里只负责把<b>后加的列</b>补到旧库上——旧库里那张表是
 * {@code CREATE TABLE IF NOT EXISTS} 建出来的，往后往建表语句里加列不会自动发生。
 *
 * <p>时机与 {@code HubSessionMigrator} 相同：注入 DataSource 的 bean 在构造时执行，
 * 此时 schema.sql 已经跑完；而真正写这些列的代码都在请求期才跑，所以「启动期补列」够用。
 */
@Component
public class MultiAgentMigrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentMigrator.class);

    private final DataSource dataSource;

    public MultiAgentMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            /* 发言当时的原始上下文（点开一条发言回看它看到了什么） */
            addColumn(connection, "ma_message", "context", "TEXT");
            /* 状态机动作行对应的话题事件序号（role=event 时才有；补写水位线） */
            addColumn(connection, "ma_message", "event_seq", "INTEGER");
            /* 成员头像（base64 data URL 小图）：旧库补列 */
            addColumn(connection, "ma_peer", "avatar", "TEXT");
            /* 成员的图谱人格锚点（entities.id：types 含 ANCHOR 的实体）：旧库补列 */
            addColumn(connection, "ma_peer", "anchor_id", "TEXT");
            /* 成员绑定的工作空间（term_target.id 的 JSON 数组）：旧库补列 */
            addColumn(connection, "ma_peer", "terminals", "TEXT");
            /* 会话的成员清单（建会话时选的那几位；旧会话为空 = 全体名册） */
            addColumn(connection, "ma_session", "member_ids", "TEXT");
        } catch (SQLException e) {
            throw new IllegalStateException("多智能体结构迁移失败: " + e.getMessage(), e);
        }
    }

    /** 缺列才补：SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，先查 pragma_table_info */
    private void addColumn(Connection connection, String table, String column, String type)
            throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("多智能体：{}.{} 已补出", table, column);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1 FROM pragma_table_info('" + table
                        + "') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
