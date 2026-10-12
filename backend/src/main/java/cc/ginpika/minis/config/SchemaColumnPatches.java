package cc.ginpika.minis.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 结构补丁：把「向后兼容的加列」在启动时补齐（幂等）。
 *
 * <p><b>为什么需要它</b>：库是由 {@code schema.sql} 里那些 {@code CREATE TABLE IF NOT EXISTS} 建的——
 * 表已存在时建表语句是**空操作**，不会补新列。于是「给既有表加一列」这种变更，会让老库在新代码下
 * 当场报 {@code no such column}（{@code chat_message.event_kind} 就是这样炸的）。要么每次加列都让人
 * 删库丢数据，要么在这里补一次——后者是唯一对使用者友好的选择。
 *
 * <p><b>它只做一件事</b>：缺哪列补哪列（{@code ALTER TABLE ... ADD COLUMN}）。一行数据都不动，
 * 老行取该列为 {@code NULL}，因此既安全又可重复执行（已补过就跳过）。
 *
 * <p><b>它不做的事</b>（这些请直接重建库，见架构文档 STEP 7「DB 可破坏」）：改列类型、重命名、删列、
 * 回填历史数据、复制数据到新表。边界刻意划得这么窄——补丁越少，行为越好预测；一旦它会改数据，
 * 「启动即执行」就不再是安全操作了。
 *
 * <p><b>时机</b>：注入 DataSource 的 bean 的 {@code @PostConstruct} 在 schema.sql 执行完之后
 * （同 {@code TaskRecovery} 的时机，{@link DependsOnDatabaseInitialization} 把这条依赖显式化）。
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaColumnPatches {

    private static final Logger log = LoggerFactory.getLogger(SchemaColumnPatches.class);

    /**
     * 待补齐的列。加一列就添一条——已存在的列会被跳过，重复启动不会重复执行。
     *
     * <p>只允许「可空、无默认值」的加列：这样老行不必回填，语句也不会锁表太久。
     */
    private static final List<ColumnPatch> PATCHES = List.of(
            new ColumnPatch("memory_event", "agent_id", "TEXT"),
            new ColumnPatch("memory_event", "project_id", "TEXT"),
            new ColumnPatch("memory_event", "session_id", "TEXT"),
            new ColumnPatch("memory_event", "task_id", "INTEGER"),
            new ColumnPatch("memory_event", "identity", "TEXT"),
            new ColumnPatch("memory_candidate", "valid_until", "INTEGER"),
            new ColumnPatch("memory_item_embedding", "item_version", "INTEGER"),
            new ColumnPatch("memory_snapshot", "content_json", "TEXT"),
            new ColumnPatch("chat_message", "event_kind", "TEXT"),
            new ColumnPatch("hub_session", "agent_id", "TEXT"),
            new ColumnPatch("hub_session", "project_id", "TEXT"),
            new ColumnPatch("roundtable_todo", "depends_on", "TEXT"),
            new ColumnPatch("roundtable_todo", "priority", "INTEGER"),
            new ColumnPatch("roundtable_todo", "block_with", "TEXT"),
            new ColumnPatch("chat_todo", "owner", "TEXT"),
            new ColumnPatch("agent", "role", "TEXT"),
            new ColumnPatch("agent", "role_color", "TEXT"),
            new ColumnPatch("hub_session", "memory_off", "INTEGER"),
            new ColumnPatch("memory_item", "pinned", "INTEGER"),
            new ColumnPatch("task", "memory_checkpoint", "TEXT"),
            /* memory-v5 §4.2：记忆归属/治理增量列（全部可空；历史回填由 SchemaMigrations 负责） */
            new ColumnPatch("memory_item", "user_id", "TEXT"),
            new ColumnPatch("memory_item", "owner", "TEXT"),
            new ColumnPatch("memory_item", "domain", "TEXT"),
            new ColumnPatch("memory_item", "layer", "TEXT"),
            new ColumnPatch("memory_item", "project_id", "TEXT"),
            new ColumnPatch("memory_item", "session_id", "TEXT"),
            new ColumnPatch("memory_item", "task_id", "INTEGER"),
            new ColumnPatch("memory_item", "producer_agent", "TEXT"),
            new ColumnPatch("memory_item", "source_kind", "TEXT"),
            new ColumnPatch("memory_item", "evidence_level", "TEXT"),
            new ColumnPatch("memory_item", "status", "TEXT"),
            new ColumnPatch("memory_item", "retention", "TEXT"),
            new ColumnPatch("memory_item", "version", "INTEGER"),
            new ColumnPatch("memory_item", "fingerprint", "TEXT"),
            new ColumnPatch("memory_item", "valid_at", "INTEGER"),
            new ColumnPatch("memory_item", "invalid_at", "INTEGER"),
            new ColumnPatch("memory_item", "learned_at", "INTEGER"),
            new ColumnPatch("memory_item", "expired_at", "INTEGER"),
            /* memory-v6 §6.2：身份上下文 / 提升来源 / 软过期（全部可空） */
            new ColumnPatch("memory_item", "identity", "TEXT"),
            new ColumnPatch("memory_item", "promoted_from", "INTEGER"),
            new ColumnPatch("memory_item", "expires_soft", "INTEGER"));

    /** 一条补列声明：{表, 列, 类型} */
    private record ColumnPatch(String table, String column, String type) {
        String ddl() {
            return "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type;
        }
    }

    private final JdbcTemplate jdbc;

    public SchemaColumnPatches(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void apply() {
        for (ColumnPatch patch : PATCHES) {
            List<String> columns = columnsOf(patch.table());
            /* 表都还没有（老库里没这张表）：schema.sql 的建表语句会带上全列，无需补；
               列已在：之前补过，跳过。两者都让本方法保持幂等，也避免对不存在的表 ALTER 抛错 */
            if (columns.isEmpty() || columns.contains(patch.column())) {
                continue;
            }
            jdbc.execute(patch.ddl());
            log.info("结构补丁：已为 {}.{} 补上缺失的列（旧行取值为 NULL）",
                    patch.table(), patch.column());
        }
    }

    /** 表里的列名；表不存在时为空表（PRAGMA 的表名不能参数化，故拼接——表名来自本类的常量，不是外部输入） */
    private List<String> columnsOf(String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")",
                (rs, rowNum) -> rs.getString("name"));
    }
}
