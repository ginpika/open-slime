package cc.ginpika.minis.agent.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 变更集：把「写操作」从审计留痕升级为有结构、可 diff / 可回滚的一组变更。
 *
 * <p>一次执行（exec）对应一个 {@code changeset}，同一执行里的每次写工具调用追加一条
 * {@code changeset_item}。回滚以变更集为整体单位，而不是事后逐条猜哪几笔属于同一次动作。
 *
 * <p>现状（STEP 1）：只落 {@code after}（= 工具调用参数）与 {@code summary}；
 * {@code before} / {@code target} 是回滚真正落地时才填的（需要能力域在写入前取旧值），
 * 故此刻为空——本表的作用是先把形态定下来，见架构文档 STEP 1。
 */
@Repository
public class ChangeSetStore {

    /** 变更集状态：已落库 */
    public static final String STATUS_APPLIED = "applied";
    /** 变更集状态：已回滚（预留） */
    public static final String STATUS_REVERTED = "reverted";

    public record ChangeSetRow(long id, Long taskId, Long execId, String status, long createdAt,
                               long updatedAt) {
    }

    /** 一条变更项：after=变更后内容（工具调用参数）；before / target 见类注释（当前为空） */
    public record ItemRow(long id, long changeSetId, String tool, String target, String before,
                          String after, String summary, long createdAt) {
    }

    private final JdbcTemplate jdbc;

    public ChangeSetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记一次写操作：该执行还没有变更集就懒建一个（归属任务按 exec_id 查出来），再追加一条变更项。
     *
     * <p>由执行引擎在每次写工具的调用落地后调用一次（写库清单即变更集）。
     * 写失败不该牵连这次执行——调用点自行吞掉。
     */
    public void record(long execId, String tool, String args, String summary) {
        long id = changesetIdOfExec(execId).orElseGet(() -> open(execId));
        if (id <= 0) {
            return;
        }
        jdbc.update("INSERT INTO changeset_item(changeset_id, tool, after, summary, created_at) "
                        + "VALUES(?,?,?,?,?)",
                id, tool, args, summary, System.currentTimeMillis());
    }

    /** 某次执行的变更集 id（没有写操作就没有变更集） */
    public Optional<Long> changesetIdOfExec(long execId) {
        return jdbc.query("SELECT id FROM changeset WHERE exec_id = ? ORDER BY id DESC LIMIT 1",
                        (rs, n) -> rs.getLong("id"), execId)
                .stream().findFirst();
    }

    public Optional<ChangeSetRow> changeSet(long id) {
        return jdbc.query("SELECT id, task_id, exec_id, status, created_at, updated_at "
                        + "FROM changeset WHERE id = ?", ChangeSetStore::map, id)
                .stream().findFirst();
    }

    /** 某个变更集的变更项（按落库顺序） */
    public List<ItemRow> items(long changeSetId) {
        return jdbc.query("SELECT id, changeset_id, tool, target, before, after, summary, created_at "
                        + "FROM changeset_item WHERE changeset_id = ? ORDER BY id",
                (rs, n) -> new ItemRow(rs.getLong("id"), rs.getLong("changeset_id"),
                        rs.getString("tool"), rs.getString("target"), rs.getString("before"),
                        rs.getString("after"), rs.getString("summary"), rs.getLong("created_at")),
                changeSetId);
    }

    /** 懒建：一次执行一个变更集（执行就是任务行，所以 execId 即 taskId） */
    private long open(long execId) {
        long now = System.currentTimeMillis();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO changeset(task_id, exec_id, status, created_at, updated_at) "
                            + "VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, execId);
            ps.setLong(2, execId);
            ps.setString(3, STATUS_APPLIED);
            ps.setLong(4, now);
            ps.setLong(5, now);
            return ps;
        }, key);
        Number id = key.getKey();
        return id == null ? -1L : id.longValue();
    }

    private static ChangeSetRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ChangeSetRow(rs.getLong("id"), nullableLong(rs, "task_id"),
                nullableLong(rs, "exec_id"), rs.getString("status"), rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    /** 可空整型列：xerial sqlite-jdbc 对小整数返回 Integer，按 long 读再判空（同 TaskStore） */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
