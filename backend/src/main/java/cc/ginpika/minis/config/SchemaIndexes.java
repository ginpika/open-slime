package cc.ginpika.minis.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 结构补索引：给「由 {@link SchemaColumnPatches} 补出来的新列」建索引（幂等）。
 *
 * <p><b>为什么单独一个类</b>：{@code schema.sql} 是结构权威，启动时先执行；但老库里 memory-v5 的新列
 * （owner / project_id / domain / status / fingerprint …）尚不存在，若在 schema.sql 里对这些列建索引，
 * 会在老库上当场报 {@code no such column} 而中断启动。于是把「依赖新列的索引」挪到加列之后执行：
 * <pre>schema.sql（建表 + 老列索引） → SchemaColumnPatches（补新列） → SchemaIndexes（建新列索引）</pre>
 *
 * <p>全部 {@code CREATE INDEX IF NOT EXISTS}，对新库/老库、重复启动都安全。它只建索引，不动一行数据。
 */
@Component
@DependsOnDatabaseInitialization
@DependsOn("schemaColumnPatches")
public class SchemaIndexes {

    private static final Logger log = LoggerFactory.getLogger(SchemaIndexes.class);

    /** 依赖 memory-v5 新列的索引（schema.sql 里刻意不建，见该文件注释） */
    private static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_memory_item_owner "
                    + "ON memory_item(owner, project_id, agent_id, domain, status)",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_item_fp "
                    + "ON memory_item(fingerprint) WHERE status IN ('active','conflicted')",
            /* memory-v6 §6.3：按层检索、L3 会话隔离读（依赖 identity / session_id 等新列） */
            "CREATE INDEX IF NOT EXISTS idx_memory_item_layer "
                    + "ON memory_item(owner, layer, status)",
            "CREATE INDEX IF NOT EXISTS idx_memory_item_session "
                    + "ON memory_item(session_id, status)");

    private final JdbcTemplate jdbc;

    public SchemaIndexes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void apply() {
        for (String ddl : INDEXES) {
            try {
                jdbc.execute(ddl);
            } catch (RuntimeException e) {
                /* 索引是优化不是正确性前提：建不出来（极老库结构异常）只告警，不阻断启动 */
                log.warn("结构补索引失败（忽略）：{} —— {}", ddl, e.getMessage());
            }
        }
    }
}
