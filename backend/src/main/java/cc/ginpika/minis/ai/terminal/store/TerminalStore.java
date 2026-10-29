package cc.ginpika.minis.ai.terminal.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 命令行终端的持久化：term_target（有哪些可执行环境）/ term_command（每次执行的审计）。
 *
 * <p>两类表的性质与潜意识模块同构：{@code term_target} 是「编制」——由人在设置页维护的长期状态；
 * {@code term_command} 是「留痕」——每次执行只追加，供页面回溯与模型自查。
 * 凭证随目标一起存在这一层，与 {@code llm_model.api_key} 同一个取舍（单用户私域部署）。
 */
@Repository
public class TerminalStore {

    /**
     * 一个终端目标的完整定义。两类目标（docker / ssh）共用一张宽表，各自只填自己那几列：
     * 比拆成两张表更省事，读起来也只多几个 null——与 {@code heartbeat_plan} 的多可选列同一个取舍。
     */
    public record TargetRow(String id, String name, String kind, boolean enabled, String workdir,
                            Long timeoutMs, Integer maxOutput, String allowPatterns,
                            String denyPatterns, String dockerHost, String image, String container,
                            String sshHost, Integer sshPort, String sshUser, String sshAuth,
                            String sshPassword, String sshPrivateKey, String sshPassphrase,
                            String hostKeyCheck, String note, String hostWorkdir, long createdAt, long updatedAt) {
    }

    /** 一次命令执行的审计行（id 为 null 表示待插入） */
    public record CommandRow(Long id, String targetId, String targetName, String command,
                             String workdir, Integer exitCode, long durationMs, String stdout,
                             String stderr, boolean truncated, boolean timedOut, boolean ok,
                             String source, long createdAt) {
    }

    /** 列清单（各查询共用）：顺序与 {@link #mapTarget} 对应 */
    private static final String TARGET_COLUMNS =
            "id, name, kind, enabled, workdir, timeout_ms, max_output, allow_patterns, "
                    + "deny_patterns, docker_host, image, container, ssh_host, ssh_port, ssh_user, "
                    + "ssh_auth, ssh_password, ssh_private_key, ssh_passphrase, host_key_check, "
                    + "note, host_workdir, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public TerminalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 终端目标 ----------

    /** 全部目标：按名字排序（设置页与 list_terminals 共用同一份稳定顺序） */
    public List<TargetRow> targets() {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM term_target ORDER BY name",
                TerminalStore::mapTarget);
    }

    public Optional<TargetRow> targetById(String id) {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM term_target WHERE id = ?",
                        TerminalStore::mapTarget, id)
                .stream().findFirst();
    }

    public Optional<TargetRow> targetByName(String name) {
        return jdbc.query("SELECT " + TARGET_COLUMNS + " FROM term_target WHERE name = ?",
                        TerminalStore::mapTarget, name)
                .stream().findFirst();
    }

    /** 名字是否已被别的目标占用（id 为 null 时表示新建）：目标名是模型指认目标的唯一凭据，必须唯一 */
    public boolean nameTaken(String name, String exceptId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_target WHERE name = ? AND id <> ?",
                Integer.class, name, exceptId == null ? "" : exceptId) > 0;
    }

    /** 新建或整体覆盖一个目标（id 为空则新建）；返回落库后的行 */
    public TargetRow save(TargetRow row) {
        long now = System.currentTimeMillis();
        if (row.id() == null || row.id().isBlank()) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                    "INSERT INTO term_target(id, name, kind, enabled, workdir, timeout_ms, "
                            + "max_output, allow_patterns, deny_patterns, docker_host, image, "
                            + "container, ssh_host, ssh_port, ssh_user, ssh_auth, ssh_password, "
                            + "ssh_private_key, ssh_passphrase, host_key_check, note, host_workdir, created_at, "
                            + "updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, row.name(), row.kind(), row.enabled() ? 1 : 0, row.workdir(), row.timeoutMs(),
                    row.maxOutput(), row.allowPatterns(), row.denyPatterns(), row.dockerHost(),
                    row.image(), row.container(), row.sshHost(), row.sshPort(), row.sshUser(),
                    row.sshAuth(), row.sshPassword(), row.sshPrivateKey(), row.sshPassphrase(),
                    row.hostKeyCheck(), row.note(), row.hostWorkdir(), now, now);
            return targetById(id).orElseThrow();
        }
        jdbc.update(
                "UPDATE term_target SET name = ?, kind = ?, enabled = ?, workdir = ?, "
                        + "timeout_ms = ?, max_output = ?, allow_patterns = ?, deny_patterns = ?, "
                        + "docker_host = ?, image = ?, container = ?, ssh_host = ?, ssh_port = ?, "
                        + "ssh_user = ?, ssh_auth = ?, ssh_password = ?, ssh_private_key = ?, "
                        + "ssh_passphrase = ?, host_key_check = ?, note = ?, host_workdir = ?, updated_at = ? "
                        + "WHERE id = ?",
                row.name(), row.kind(), row.enabled() ? 1 : 0, row.workdir(), row.timeoutMs(),
                row.maxOutput(), row.allowPatterns(), row.denyPatterns(), row.dockerHost(),
                row.image(), row.container(), row.sshHost(), row.sshPort(), row.sshUser(),
                row.sshAuth(), row.sshPassword(), row.sshPrivateKey(), row.sshPassphrase(),
                row.hostKeyCheck(), row.note(), row.hostWorkdir(), now, row.id());
        return targetById(row.id()).orElseThrow();
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM term_target WHERE id = ?", id);
    }

    // ---------- 命令审计 ----------

    public void insertCommand(CommandRow row) {
        jdbc.update(
                "INSERT INTO term_command(target_id, target_name, command, workdir, exit_code, "
                        + "duration_ms, stdout, stderr, truncated, timed_out, ok, source, created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.targetId(), row.targetName(), row.command(), row.workdir(), row.exitCode(),
                row.durationMs(), row.stdout(), row.stderr(), row.truncated() ? 1 : 0,
                row.timedOut() ? 1 : 0, row.ok() ? 1 : 0, row.source(),
                System.currentTimeMillis());
    }

    /** 最近若干条命令（倒序）：设置页看「模型都干了什么」 */
    public List<CommandRow> recentCommands(int limit) {
        return jdbc.query(
                "SELECT id, target_id, target_name, command, workdir, exit_code, duration_ms, "
                        + "stdout, stderr, truncated, timed_out, ok, source, created_at "
                        + "FROM term_command ORDER BY id DESC LIMIT ?",
                TerminalStore::mapCommand, limit);
    }

    /** 裁掉最旧的审计：只留最近 keep 条（每次执行顺带清理，不另起定时任务） */
    public int trimCommands(int keep) {
        return jdbc.update(
                "DELETE FROM term_command WHERE id NOT IN "
                        + "(SELECT id FROM term_command ORDER BY id DESC LIMIT ?)", keep);
    }

    public long commandCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM term_command", Long.class);
        return n == null ? 0 : n;
    }

    // ---------- 映射 ----------

    private static TargetRow mapTarget(ResultSet rs, int rowNum) throws SQLException {
        return new TargetRow(rs.getString("id"), rs.getString("name"), rs.getString("kind"),
                rs.getInt("enabled") == 1, rs.getString("workdir"),
                (Long) rs.getObject("timeout_ms"), (Integer) rs.getObject("max_output"),
                rs.getString("allow_patterns"), rs.getString("deny_patterns"),
                rs.getString("docker_host"), rs.getString("image"), rs.getString("container"),
                rs.getString("ssh_host"), (Integer) rs.getObject("ssh_port"),
                rs.getString("ssh_user"), rs.getString("ssh_auth"), rs.getString("ssh_password"),
                rs.getString("ssh_private_key"), rs.getString("ssh_passphrase"),
                rs.getString("host_key_check"), rs.getString("note"), rs.getString("host_workdir"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static CommandRow mapCommand(ResultSet rs, int rowNum) throws SQLException {
        return new CommandRow(rs.getLong("id"), rs.getString("target_id"),
                rs.getString("target_name"), rs.getString("command"), rs.getString("workdir"),
                (Integer) rs.getObject("exit_code"), rs.getLong("duration_ms"),
                rs.getString("stdout"), rs.getString("stderr"), rs.getInt("truncated") == 1,
                rs.getInt("timed_out") == 1, rs.getInt("ok") == 1, rs.getString("source"),
                rs.getLong("created_at"));
    }
}
