package cc.ginpika.minis.ai.memory.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 提炼水位（memory_cursor，memory-v5 §7.2 · P4）：记录「某条流水线已经处理到哪」，避免重复处理。
 *
 * <p>{@code session_id} 列在此复用为**通用的游标键**（原件为会话水位）：既存「会话已提炼到哪条事件」，
 * 也存「语义巩固已处理到哪条条目」（键形如 {@code semantic:<domain>}）。本类只做存取。
 */
@Repository
public class MemoryCursorStore {

    /** 语义巩固游标的键前缀 */
    public static final String PREFIX_SEMANTIC = "semantic:";

    private final JdbcTemplate jdbc;

    public MemoryCursorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读水位（无记录返回 0） */
    public long get(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Long v = jdbc.query("SELECT last_event_id FROM memory_cursor WHERE session_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        return v == null ? 0L : v;
    }

    /** 写水位（upsert） */
    public void set(String key, long value) {
        if (key == null || key.isBlank()) {
            return;
        }
        jdbc.update("INSERT INTO memory_cursor(session_id, last_event_id, updated_at) VALUES(?,?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET last_event_id = excluded.last_event_id, "
                        + "updated_at = excluded.updated_at",
                key, value, System.currentTimeMillis());
    }

    /** 清空全部水位（设置页「重置知识库」连带清除记忆时用） */
    public int clearAll() {
        return jdbc.update("DELETE FROM memory_cursor WHERE session_id NOT LIKE 'reset:%'");
    }
}
