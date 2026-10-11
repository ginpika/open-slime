package cc.ginpika.minis.agent.subagent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 子代理共享黑板（scratchpad，STEP 4）：{@code subagent_scratch} 表的读写。
 *
 * <p>子代理是<b>一次性匿名 worker</b>：各拿各的交代、互不相见。一批并行派出的子代理看不到彼此，
 * 但「同一会话里先派后派」能接力——前面的人查过什么，写在这块黑板上，后面的人开工先读它，
 * 于是不必重复检索同一件事。这是「子代理协作」的最小落点：不追求并发内的实时共识（那要靠
 * 分布式锁与消息，与单用户 M0 的取舍不符），只做「结论沉淀、后人可读」。
 *
 * <p>写入由 {@code SubagentService} 在子代理收尾时自动完成（不新增模型工具，主对话因此不多背 schema）；
 * 读取由 {@code SubagentService.messages} 注进新子代理的 system。只增不改写。
 */
@Repository
public class ScratchpadStore {

    /** 黑板一条：某子代理的一条发现 */
    public record Entry(long id, String author, Long execId, String content, long createdAt) {
    }

    private final JdbcTemplate jdbc;

    public ScratchpadStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条发现（内容为空则不记）；sessionId 为空时忽略（无可归的会话） */
    public void append(String sessionId, String author, Long execId, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        jdbc.update("INSERT INTO subagent_scratch(session_id, author, exec_id, content, created_at) "
                        + "VALUES(?,?,?,?,?)",
                sessionId, author, execId, content.strip(), System.currentTimeMillis());
    }

    /**
     * 最近 limit 条发现（按时间正序返回：最早在前，读起来是「一路查到这」的次序）。
     *
     * <p>先按 id 倒序取最近 limit 条再翻转，避免把整张表读进内存。
     */
    public List<Entry> recent(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        List<Entry> rows = new ArrayList<>(jdbc.query(
                "SELECT id, author, exec_id, content, created_at FROM subagent_scratch "
                        + "WHERE session_id = ? ORDER BY id DESC LIMIT ?",
                ScratchpadStore::map, sessionId, limit));
        Collections.reverse(rows);
        return rows;
    }

    private static Entry map(ResultSet rs, int rowNum) throws SQLException {
        long exec = rs.getLong("exec_id");
        Long execId = rs.wasNull() ? null : exec;
        return new Entry(rs.getLong("id"), rs.getString("author"), execId,
                rs.getString("content"), rs.getLong("created_at"));
    }
}
