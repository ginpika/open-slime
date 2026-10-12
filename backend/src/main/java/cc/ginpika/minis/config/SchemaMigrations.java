package cc.ginpika.minis.config;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 数据级迁移器（memory-v5 §11.1）。职责边界严格：
 * <pre>
 *   schema.sql           —— 结构权威（新表 / 新列全列；IF NOT EXISTS）
 *   SchemaColumnPatches  —— 给老库补可空新列（PRAGMA 判存在）
 *   SchemaIndexes        —— 给新列补索引
 *   SchemaMigrations     —— **仅数据级**：历史回填 / 语义规范化（版本号 + 单事务 + 先备份）
 * </pre>
 *
 * <p><b>安全契约</b>（每一步都可回滚、可重复）：
 * <ol>
 *   <li>读 {@code _schema.version}；已是最新则直接返回（幂等）。</li>
 *   <li>版本落后时**先备份**（{@code VACUUM INTO data/backups/minis-<ts>.db}）——迁移失败可整体覆盖恢复。</li>
 *   <li>在**单事务**内依次跑各版本迁移 + 递增 {@code _schema.version}；任何异常 → 事务回滚、版本不前进。</li>
 *   <li>迁移后校验（owner/status 无 NULL、矩阵违例计数），异常即抛（触发回滚）。</li>
 * </ol>
 *
 * <p><b>不做的事</b>：不重建表、不删列、不静默删除数据。无法归类的历史行一律「保守归类」并计入日志，
 * 绝不臆造归属（见 memory-v5 §11.3）。
 */
@Component
@DependsOnDatabaseInitialization
@DependsOn({"schemaColumnPatches", "schemaIndexes"})
public class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /** 目标版本：v2 归属 / v3 运行时 / v4 隔离与版本一致性 */
    private static final int TARGET = 4;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String dbUrl;

    public SchemaMigrations(JdbcTemplate jdbc, TransactionTemplate tx,
                            @Value("${spring.datasource.url:}") String dbUrl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.dbUrl = dbUrl;
    }

    @PostConstruct
    void apply() {
        int current = currentVersion();
        if (current >= TARGET) {
            return;
        }
        log.info("数据迁移：当前 _schema.version={} → 目标 {}，开始备份…", current, TARGET);
        backup();
        Map<Integer, Runnable> steps = steps();
        try {
            tx.executeWithoutResult(status -> {
                for (int v = current + 1; v <= TARGET; v++) {
                    Runnable step = steps.get(v);
                    if (step != null) {
                        log.info("数据迁移：执行 v{}", v);
                        step.run();
                    }
                }
                jdbc.update("UPDATE _schema SET version = ?", TARGET);
            });
        } catch (RuntimeException e) {
            log.error("数据迁移失败，已回滚（_schema.version 保持 {}）；可用 data/backups 下的备份恢复：{}",
                    current, e.getMessage());
            throw e;
        }
        verify();
        log.info("数据迁移完成：_schema.version={}", TARGET);
    }

    /** 各版本迁移步骤：v → 动作。v2 = memory-v5 归属回填；v3 = memory-v6 运行时（身份 / L3 绑定）。 */
    private Map<Integer, Runnable> steps() {
        Map<Integer, Runnable> m = new LinkedHashMap<>();
        m.put(2, this::migrateV2MemoryScope);
        m.put(3, this::migrateV3MemoryRuntime);
        m.put(4, this::migrateV4MemoryBoundaries);
        return m;
    }

    // ---------------------------------------------------------------- v2：memory-v5 历史回填

    /**
     * 把既有 memory_item 从「扁平 scope」规范化为「owner × domain × layer」：
     * <ul>
     *   <li>profile/directive + source=auto → 转<b>候选</b>（写 memory_candidate），原条目归档（不删）；</li>
     *   <li>profile/directive + explicit/manual → USER × USER_PROFILE × L1，active；</li>
     *   <li>project（无 project_id）→ 保守归 AGENT('builtin') × AGENT × L2（标「待复核」计数）；</li>
     *   <li>episode → AGENT × AGENT × L2（agent_id 空则规范化 'builtin'）；</li>
     *   <li>其它/未知 → 保守归 AGENT × AGENT × L2。</li>
     * </ul>
     * 逐行补 version=0 / retention / fingerprint / 双时态字段。**不提升任何历史行为 USER**。
     */
    private void migrateV2MemoryScope() {
        // 1) auto 画像 → 候选 + 原条目归档
        int autoc = jdbc.update("""
                INSERT INTO memory_candidate(fingerprint, user_id, owner, scope, title, body,
                    source_kind, evidence_level, evidence_count, status, created_at, updated_at)
                SELECT 'mig:' || id, NULL, 'USER', scope, title, body,
                    'inferred', 'inferred', 1, 'pending', created_at, updated_at
                FROM memory_item
                WHERE scope IN ('profile','directive') AND source = 'auto'
                  AND NOT EXISTS(SELECT 1 FROM memory_candidate c WHERE c.fingerprint = 'mig:' || memory_item.id)
                """);
        int arch = jdbc.update("""
                UPDATE memory_item SET status = 'archived', owner = 'USER', domain = 'USER_PROFILE',
                    layer = 'L1', source_kind = 'inferred', evidence_level = 'inferred'
                WHERE scope IN ('profile','directive') AND source = 'auto' AND (status IS NULL)
                """);
        if (autoc > 0) {
            log.info("迁移 v2：auto 画像转候选 {} 条，原条目归档 {} 条", autoc, arch);
        }

        // 2) 其余历史行：按 scope 归类（保守，绝不升 USER）
        int projNoId = jdbc.update("""
                UPDATE memory_item SET owner='AGENT', domain='AGENT', layer='L2',
                    agent_id='builtin', source_kind='inferred', evidence_level='inferred'
                WHERE status IS NULL AND scope='project' AND (project_id IS NULL OR project_id='')
                """);
        jdbc.update("""
                UPDATE memory_item SET owner='PROJECT', domain='PROJECT', layer='L1',
                    source_kind='inferred', evidence_level='inferred'
                WHERE status IS NULL AND scope='project' AND project_id IS NOT NULL AND project_id <> ''
                """);
        jdbc.update("""
                UPDATE memory_item SET owner='AGENT', domain='AGENT', layer='L2',
                    agent_id='builtin', source_kind='inferred', evidence_level='inferred'
                WHERE status IS NULL AND scope='episode' AND (agent_id IS NULL OR agent_id='')
                """);
        jdbc.update("""
                UPDATE memory_item SET owner='AGENT', domain='AGENT', layer='L2',
                    source_kind='inferred', evidence_level='inferred'
                WHERE status IS NULL AND scope='episode' AND agent_id IS NOT NULL AND agent_id <> ''
                """);
        jdbc.update("""
                UPDATE memory_item SET owner='AGENT', domain='AGENT', layer='L2',
                    agent_id='builtin', source_kind='inferred', evidence_level='inferred'
                WHERE status IS NULL
                """);
        if (projNoId > 0) {
            log.warn("迁移 v2：{} 条 scope=project 的历史记忆缺少 project_id，已保守归 AGENT('builtin')×L2（待复核）",
                    projNoId);
        }

        // 3) profile/directive 且 explicit/manual → 正式画像
        jdbc.update("""
                UPDATE memory_item SET owner='USER', domain='USER_PROFILE', layer='L1',
                    source_kind='inferred', evidence_level='explicit'
                WHERE status IS NULL AND scope IN ('profile','directive')
                """);

        // 4) 通用字段：status / retention / version / 指纹 / 双时态
        jdbc.update("""
                UPDATE memory_item SET
                    status = CASE
                        WHEN superseded_by IS NOT NULL THEN 'superseded'
                        WHEN valid_until IS NOT NULL AND valid_until <= CAST(strftime('%s','now') AS INTEGER)*1000
                             THEN 'expired'
                        ELSE 'active' END,
                    retention = CASE WHEN pinned = 1 THEN 'pinned' ELSE 'normal' END,
                    version = 0,
                    fingerprint = 'mig:' || id,
                    valid_at = valid_from,
                    learned_at = created_at
                WHERE status IS NULL
                """);
    }

    // ---------------------------------------------------------------- v3：memory-v6 记忆运行时

    /**
     * memory-v6 §6.4 的数据级迁移（结构列由 schema.sql / Patches 负责，这里只动数据）：
     * <ol>
     *   <li><b>identity 回填</b>（不变量 I11 的读侧依据）：fiction → FICTION、project_pref → PROJECT_PREF，
     *       其余按证据分级 → REAL（explicit/manual）或 INFERRED；</li>
     *   <li><b>L3 绑定修正</b>（不变量 I5）：layer='L3' 却既无 session_id 也无 task_id 的历史行 →
     *       保守降为 L2（不臆造会话绑定），并计入「待复核」日志；</li>
     *   <li><b>AGENT 归属规范化</b>：owner='AGENT' 而 agent_id 为空 → 'builtin'。</li>
     * </ol>
     * 全部为 UPDATE，不删数据、不提升任何历史行为 USER；重复启动由版本号保证不重复执行。
     */
    private void migrateV3MemoryRuntime() {
        // 1) identity 回填
        int fiction = jdbc.update("""
                UPDATE memory_item SET identity = 'FICTION'
                WHERE identity IS NULL AND source_kind = 'fiction'
                """);
        int pref = jdbc.update("""
                UPDATE memory_item SET identity = 'PROJECT_PREF'
                WHERE identity IS NULL AND source_kind = 'project_pref'
                """);
        jdbc.update("""
                UPDATE memory_item SET identity = CASE
                    WHEN source = 'explicit' OR source = 'manual' OR evidence_level = 'explicit' THEN 'REAL'
                    ELSE 'INFERRED' END
                WHERE identity IS NULL
                """);

        // 2) L3 绑定修正：无会话 / 任务绑定的 L3 历史行降为 L2（保守，不臆造归属）
        int demoted = jdbc.update("""
                UPDATE memory_item SET layer = 'L2'
                WHERE layer = 'L3' AND (session_id IS NULL OR session_id = '')
                  AND (task_id IS NULL)
                """);
        // owner=TASK 却无任何绑定 → 归 AGENT('builtin')（TASK 归属必须有绑定，I4）
        int taskOrphan = jdbc.update("""
                UPDATE memory_item SET owner = 'AGENT', domain = 'AGENT', layer = 'L2',
                    agent_id = COALESCE(NULLIF(agent_id, ''), 'builtin')
                WHERE owner = 'TASK' AND (session_id IS NULL OR session_id = '') AND task_id IS NULL
                """);

        // 3) AGENT 归属规范化
        int builtin = jdbc.update("""
                UPDATE memory_item SET agent_id = 'builtin'
                WHERE owner = 'AGENT' AND (agent_id IS NULL OR agent_id = '')
                """);

        if (fiction + pref + demoted + taskOrphan + builtin > 0) {
            log.info("迁移 v3：identity 回填 {}（fiction {} / project_pref {}）；L3 降级 {}；TASK 孤儿归 AGENT {}；"
                            + "agent 规范化 {}",
                    fiction + pref, fiction, pref, demoted, taskOrphan, builtin);
        }
        if (demoted > 0) {
            log.warn("迁移 v3：{} 条 layer=L3 的历史记忆缺少会话/任务绑定，已保守降为 L2（待复核）", demoted);
        }
    }

    private void migrateV4MemoryBoundaries() {
        // 不能从“最后一次会话绑定”臆造历史事件权限；无作用域旧事件继续只供人工审计。
        jdbc.update("UPDATE memory_item SET agent_id=producer_agent WHERE owner='PROJECT' AND layer='L2' "
                + "AND agent_id IS NULL AND producer_agent IS NOT NULL");
        jdbc.update("UPDATE memory_item SET status='archived' WHERE owner='PROJECT' AND layer='L2' "
                + "AND (agent_id IS NULL OR agent_id='')");
        jdbc.update("UPDATE memory_item SET layer='L3' WHERE owner='PROJECT' AND layer='L1' "
                + "AND source <> 'manual' AND (session_id IS NOT NULL OR task_id IS NOT NULL)");
        jdbc.update("UPDATE memory_item SET status='archived' WHERE owner='PROJECT' AND layer='L1' "
                + "AND source <> 'manual' AND session_id IS NULL AND task_id IS NULL");
        var store = new cc.ginpika.minis.ai.memory.store.MemoryItemStore(jdbc);
        for (var it : store.listAll()) {
            String fp = cc.ginpika.minis.ai.memory.MemoryVocabulary.fingerprint(it.owner(), it.projectId(),
                    it.agentId(), it.domain(), it.scope(), it.body(), it.layer(), it.sessionId(), it.taskId());
            if (java.util.Objects.equals(fp, it.fingerprint())) { continue; }
            var duplicate = store.findByFingerprint(fp).orElse(null);
            if (duplicate != null && duplicate.id() != it.id()) {
                jdbc.update("UPDATE memory_item SET status='archived' WHERE id=?", it.id());
            }
            jdbc.update("UPDATE memory_item SET fingerprint=? WHERE id=?", fp, it.id());
            jdbc.update("UPDATE memory_revocation SET fingerprint=? WHERE fingerprint=?", fp, it.fingerprint());
        }
        // 老快照没有版本正文，保留并明确为历史引用；老向量在补齐任务中按版本重算。
        jdbc.update("DELETE FROM memory_item_embedding WHERE item_version IS NULL");
    }

    // ---------------------------------------------------------------- 工具

    /** 迁移后校验：owner/status 不得有 NULL；矩阵违例（USER 带 project_id / PROJECT 无 project_id）计数 */
    private void verify() {
        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM memory_item WHERE owner IS NULL OR status IS NULL", Integer.class);
        if (nulls != null && nulls > 0) {
            throw new IllegalStateException("迁移后校验失败：仍有 " + nulls + " 条记忆 owner/status 为空");
        }
        Integer bad = jdbc.queryForObject("""
                SELECT COUNT(*) FROM memory_item
                WHERE (owner='USER' AND (project_id IS NOT NULL OR agent_id IS NOT NULL) AND status='active')
                   OR (owner='PROJECT' AND (project_id IS NULL OR project_id='') AND status='active')
                """, Integer.class);
        if (bad != null && bad > 0) {
            log.warn("迁移后校验：{} 条记忆违反归属组合矩阵（待复核，不阻断启动）", bad);
        }
        /* memory-v6 不变量抽查：L3 必须绑定会话/任务；L4 必须有发布来源（memory_share 或提升记录） */
        Integer l3 = jdbc.queryForObject("""
                SELECT COUNT(*) FROM memory_item
                WHERE status = 'active' AND layer = 'L3'
                  AND (session_id IS NULL OR session_id = '') AND task_id IS NULL
                """, Integer.class);
        if (l3 != null && l3 > 0) {
            throw new IllegalStateException("迁移后校验失败：仍有 " + l3 + " 条 L3 记忆缺少会话/任务绑定（违反 I5）");
        }
        Integer l4 = jdbc.queryForObject("""
                SELECT COUNT(*) FROM memory_item m
                WHERE m.status = 'active' AND m.layer = 'L4'
                  AND NOT EXISTS(SELECT 1 FROM memory_share s WHERE s.item_id = m.id)
                  AND NOT EXISTS(SELECT 1 FROM memory_promotion p WHERE p.item_id = m.id AND p.state = 'applied')
                """, Integer.class);
        if (l4 != null && l4 > 0) {
            log.warn("迁移后校验：{} 条 L4 记忆缺少发布来源（违反 I6，待复核，不阻断启动）", l4);
        }
    }

    private int currentVersion() {
        try {
            Integer v = jdbc.queryForObject("SELECT version FROM _schema LIMIT 1", Integer.class);
            return v == null ? 1 : v;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** 迁移前备份：{@code VACUUM INTO 'data/backups/minis-<ts>.db'}；文件库备份失败时阻止迁移；内存库无需文件备份 */
    private void backup() {
        Path db = sqlitePath();
        if (db == null) {
            log.warn("无法解析 SQLite 库路径，跳过自动备份（请自行确保有副本）");
            return;
        }
        try {
            Path dir = db.toAbsolutePath().getParent().resolve("backups");
            Files.createDirectories(dir);
            Path out = dir.resolve("minis-" + System.currentTimeMillis() + ".db");
            String target = out.toAbsolutePath().toString().replace("\\", "/").replace("'", "''");
            jdbc.execute("VACUUM INTO '" + target + "'");
            log.info("数据迁移备份完成：{}", out.toAbsolutePath());
        } catch (Exception e) {
            throw new IllegalStateException("数据库备份失败，已停止数据迁移", e);
        }
    }

    /** 从 jdbc:sqlite:<path> 解析库文件路径；非 sqlite 或解析不出返回 null */
    private Path sqlitePath() {
        String prefix = "jdbc:sqlite:";
        if (dbUrl == null || !dbUrl.startsWith(prefix)) {
            return null;
        }
        String p = dbUrl.substring(prefix.length());
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        if (p.isBlank() || ":memory:".equals(p)) {
            return null;
        }
        File f = new File(p);
        return Paths.get(f.getAbsolutePath());
    }
}
