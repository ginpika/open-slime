package cc.ginpika.minis.agent.subconscious.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模块历史结构迁移（幂等，启动时执行一次）。
 *
 * <p>本模块经历过三次概念演进，这里把旧结构一律归一到当前的「编制 + 执行」两张表：
 * <ul>
 *   <li><b>编制</b> — {@code sub_plan}（任务计划）/ {@code sub_heartbeat}（心跳主会话）</li>
 *   <li><b>执行</b> — {@code sub_exec}（一次执行，{@code kind} 区分 heartbeat / plan / agent）
 *       + {@code sub_exec_edit}（写库清单）；{@code sub_run} 是一批执行</li>
 * </ul>
 *
 * <p>三步归一，每步都幂等（源表不在就跳过）：
 * <ol>
 *   <li><b>编制表改名</b>：{@code sub_task}（更早）或 {@code sub_agent}（上一版）→ {@code sub_plan}。
 *       三者列集完全一致，故直接改名而不搬数据；schema.sql 刚建出的空 {@code sub_plan} 先丢弃。
 *       更早的「模板层」（{@code sub_prompt} + {@code prompt_id}）在这里一次性合并进来。</li>
 *   <li><b>执行表改名</b>：{@code sub_agent_result} → {@code sub_exec}，
 *       列 {@code task_id} / {@code agent_id} → {@code plan_id}、{@code task_name} / {@code agent_name}
 *       → {@code title}，并补出 {@code kind}（历史行只有心跳与计划两种）。</li>
 *   <li><b>写库清单改名</b>：{@code sub_agent_edit} → {@code sub_exec_edit}，{@code result_id} → {@code exec_id}。</li>
 * </ol>
 * 另外注入器名 {@code tasks} / {@code task_input} → {@code agents} / {@code agent_input}（只改绑定的注入器名，
 * 插槽名由用户自拟、原样保留）。
 *
 * <p>改名顺序必须「先表后列、最后建索引」：{@code idx_sub_exec_plan(kind, plan_id)} 依赖改名后的列，
 * 而它们不能写在 schema.sql 里——旧库执行到那里时列还叫别的名字，会让启动直接失败。
 *
 * <p>时机与 {@link HubSessionMigrator} 相同：注入 DataSource 的 bean 构造时 schema.sql 已执行完。
 */
@Component
public class SubconsciousMigrator {

    private static final Logger log = LoggerFactory.getLogger(SubconsciousMigrator.class);

    /** 编制表的旧名（按新旧顺序）：越靠前越老 */
    private static final String[] LEGACY_PLAN_TABLES = {"sub_task", "sub_agent"};

    /** 改名后作废的索引名（随表改名残留下来，清掉以免与新索引重复） */
    private static final String[] STALE_INDEXES = {
            "idx_sub_agent_run", "idx_sub_agent_task", "idx_sub_agent_agent", "idx_sub_edit_result"};

    private final DataSource dataSource;

    public SubconsciousMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
        migrate();
    }

    private void migrate() {
        try (Connection c = dataSource.getConnection()) {
            dropRetiredThreadTables(c);
            renamePlanTable(c);
            renameExecTable(c);
            renameEditTable(c);
            renameInjectors(c);
            rebuildIndexes(c);
        } catch (SQLException e) {
            throw new IllegalStateException("潜意识结构迁移失败: " + e.getMessage(), e);
        }
    }

    // ---------- 编制表：sub_task / sub_agent → sub_plan ----------

    private void renamePlanTable(Connection c) throws SQLException {
        String legacy = firstExisting(c, LEGACY_PLAN_TABLES);
        if (legacy == null) {
            return;
        }
        if ("sub_task".equals(legacy)) {
            mergeLegacyPromptLayer(c);
            dropRetiredPlanColumns(c);
        }
        dropTable(c, "sub_plan");
        renameTable(c, legacy, "sub_plan");
    }

    /** 更早的一次重构：把 {@code sub_prompt}「模板」层并入编制本身（正文/角色/工具/模型原本挂在模板上） */
    private void mergeLegacyPromptLayer(Connection c) throws SQLException {
        if (!hasColumn(c, "sub_task", "prompt_id")) {
            return;
        }
        // PRAGMA 不能在事务内生效，先关外键（重建期间避免子表引用报错）
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=OFF");
        }
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE sub_task_merged (
                      id            TEXT PRIMARY KEY,
                      name          TEXT NOT NULL,
                      role          TEXT NOT NULL DEFAULT 'system',
                      content       TEXT NOT NULL,
                      tools         TEXT,
                      injectors     TEXT,
                      channels      TEXT,
                      interval_ms   INTEGER,
                      input         TEXT,
                      model_id      TEXT,
                      sort          INTEGER NOT NULL DEFAULT 0,
                      last_beat_at  INTEGER,
                      created_at    INTEGER NOT NULL,
                      updated_at    INTEGER NOT NULL
                    )""");
            // 模板表缺失（理论上不该发生）时退化成全空的一行源，正文留空而不是让迁移失败
            String templates = hasTable(c, "sub_prompt") ? "sub_prompt"
                    : "(SELECT NULL AS id, NULL AS role, NULL AS content, NULL AS tools, "
                            + "NULL AS model_id)";
            st.execute("INSERT INTO sub_task_merged(id, name, role, content, tools, injectors, "
                    + "channels, interval_ms, input, model_id, sort, "
                    + "last_beat_at, created_at, updated_at) "
                    + "SELECT t.id, t.name, COALESCE(p.role, 'system'), COALESCE(p.content, ''), "
                    + "p.tools, COALESCE(t.injectors, p.injectors), t.channels, "
                    + "t.interval_ms, t.input, COALESCE(t.model_id, p.model_id), "
                    + "t.sort, t.last_beat_at, t.created_at, t.updated_at "
                    + "FROM sub_task t LEFT JOIN " + templates + " p ON p.id = t.prompt_id");
            st.execute("DROP TABLE sub_task");
            st.execute("ALTER TABLE sub_task_merged RENAME TO sub_task");
            st.execute("DROP TABLE IF EXISTS sub_prompt");
            c.commit();
            log.info("潜意识：提示词模板已并入编制（模板层移除）");
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
        }
        dropColumn(c, "sub_agent_result", "prompt_id");
    }

    /** 更早的编制表上两处废掉的列：enabled（「启用」开关）与 trigger_kind（「触发方式」） */
    private void dropRetiredPlanColumns(Connection c) throws SQLException {
        dropColumn(c, "sub_task", "enabled");
        dropColumn(c, "sub_task", "trigger_kind");
    }

    // ---------- 执行表：sub_agent_result → sub_exec ----------

    private void renameExecTable(Connection c) throws SQLException {
        if (!hasTable(c, "sub_agent_result")) {
            return;
        }
        dropTable(c, "sub_exec");
        renameTable(c, "sub_agent_result", "sub_exec");
        renameColumn(c, "sub_exec", "task_id", "plan_id");
        renameColumn(c, "sub_exec", "agent_id", "plan_id");
        renameColumn(c, "sub_exec", "task_name", "title");
        renameColumn(c, "sub_exec", "agent_name", "title");
        if (!hasColumn(c, "sub_exec", "kind")) {
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE sub_exec ADD COLUMN kind TEXT");
                // 历史行只有两种：心跳主会话（plan_id 固定 'heartbeat'）与计划被派出
                st.execute("UPDATE sub_exec SET kind = CASE "
                        + "WHEN plan_id = 'heartbeat' THEN 'heartbeat' "
                        + "WHEN plan_id IS NULL THEN 'agent' ELSE 'plan' END");
            }
            log.info("潜意识：sub_exec 已补出 kind 列（历史行按 heartbeat / plan 归类）");
        }
    }

    // ---------- 写库清单：sub_agent_edit → sub_exec_edit ----------

    private void renameEditTable(Connection c) throws SQLException {
        if (!hasTable(c, "sub_agent_edit")) {
            return;
        }
        dropTable(c, "sub_exec_edit");
        renameTable(c, "sub_agent_edit", "sub_exec_edit");
        renameColumn(c, "sub_exec_edit", "result_id", "exec_id");
    }

    // ---------- 注入器名 ----------

    /**
     * 注入器改名。历史上的名字一路收敛到今天：
     * 名册 {@code tasks} / {@code agents} → {@code plans}，本轮交代 {@code task_input} / {@code agent_input}
     * → {@code plan_input}。只改绑定的注入器名，插槽名由用户自拟、原样保留（绑定按插槽名索引，
     * 所以插槽名不改也不影响取值）。
     */
    private void renameInjectors(Connection c) throws SQLException {
        String[][] renames = {
                {"tasks", "plans"}, {"agents", "plans"},
                {"task_input", "plan_input"}, {"agent_input", "plan_input"}};
        for (String table : new String[]{"sub_heartbeat", "sub_plan"}) {
            for (String[] pair : renames) {
                rewriteInjector(c, table, pair[0], pair[1]);
            }
        }
    }

    private void rewriteInjector(Connection c, String table, String from, String to)
            throws SQLException {
        String needle = "\"injector\":\"" + from + "\"";
        try (Statement st = c.createStatement()) {
            int n = st.executeUpdate("UPDATE " + table + " SET injectors = REPLACE(injectors, '"
                    + needle + "', '\"injector\":\"" + to + "\"') "
                    + "WHERE injectors LIKE '%" + needle + "%'");
            if (n > 0) {
                log.info("潜意识：{}.injectors 的注入器 {} → {}（{} 行）", table, from, to, n);
            }
        }
    }

    // ---------- 索引 ----------

    /** 清掉随改名残留的旧索引，按当前列补建（幂等） */
    private void rebuildIndexes(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String index : STALE_INDEXES) {
                st.execute("DROP INDEX IF EXISTS " + index);
            }
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_exec_run ON sub_exec(run_id, id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_exec_plan ON sub_exec(plan_id, id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_exec_kind ON sub_exec(kind, id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sub_exec_edit ON sub_exec_edit(exec_id, id)");
        }
    }

    // ---------- 旧「会话」表 ----------

    /**
     * 旧「会话（thread）」体系已退役：子代的 open_thread / send_message 改为直接往首页 chat 的
     * hub_session / chat_message 投递（同名复用、无则新建）。这两张表不再有任何读写方，启动时顺手清掉；已清过则跳过。
     */
    private void dropRetiredThreadTables(Connection c) throws SQLException {
        if (!hasTable(c, "sub_thread") && !hasTable(c, "sub_thread_message")) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS sub_thread_message");
            st.execute("DROP TABLE IF EXISTS sub_thread");
        }
        log.info("潜意识：旧「会话（thread）」表已移除（子代理改投首页 chat）");
    }

    // ---------- 结构助手 ----------

    /** 复数候选里第一个存在的表名；都不存在返回 null */
    private static String firstExisting(Connection c, String[] tables) throws SQLException {
        for (String table : tables) {
            if (hasTable(c, table)) {
                return table;
            }
        }
        return null;
    }

    /** 先丢目标表再改名：目标表此时一定是 schema.sql 刚建出的空表（有数据的库走不到这一步） */
    private void dropTable(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private void renameTable(Connection c, String from, String to) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + from + " RENAME TO " + to);
            log.info("潜意识：{} 已改名为 {}", from, to);
        }
    }

    /** 旧列改名；源列不在或目标列已在都跳过（幂等） */
    private void renameColumn(Connection c, String table, String from, String to) throws SQLException {
        if (!hasColumn(c, table, from) || hasColumn(c, table, to)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " RENAME COLUMN " + from + " TO " + to);
            log.info("已重命名列 {}.{} → {}", table, from, to);
        }
    }

    /** 废弃列能删就删（SQLite 3.35+ 才支持 DROP COLUMN），删不掉留一列空值不影响运行 */
    private void dropColumn(Connection c, String table, String column) throws SQLException {
        if (!hasColumn(c, table, column)) {
            return;
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
            log.info("已移除废弃列 {}.{}", table, column);
        } catch (SQLException e) {
            log.warn("{}.{} 未能移除（留一列空值不影响运行）：{}", table, column, e.getMessage());
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
             ResultSet rs = st.executeQuery("SELECT 1 FROM pragma_table_info('" + table
                     + "') WHERE name='" + column + "'")) {
            return rs.next();
        }
    }
}
