package cc.ginpika.minis.ai.memory.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 撤销墓碑（memory_revocation）：画像被撤销 / 拒绝后写墓碑，防止同指纹被自动重建（memory-v5 §5.4）。
 *
 * <p>{@code allow_relearn=1} 表示用户解除了封锁，允许重新学习（不阻断新候选）。
 */
@Repository
public class MemoryRevocationStore {

    private final JdbcTemplate jdbc;

    public MemoryRevocationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写一条墓碑 */
    public void add(Long targetItemId, String fingerprint, String reason, boolean allowRelearn) {
        jdbc.update("INSERT INTO memory_revocation(target_item_id, fingerprint, reason, allow_relearn, "
                        + "created_at) VALUES(?,?,?,?,?)",
                targetItemId, fingerprint, reason, allowRelearn ? 1 : 0, System.currentTimeMillis());
    }

    /** 是否存在「未解除」的墓碑（allow_relearn=0）——存在即禁止同指纹再生成候选 */
    public boolean blocked(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM memory_revocation "
                + "WHERE fingerprint = ? AND allow_relearn = 0", Integer.class, fingerprint);
        return n != null && n > 0;
    }

    /** 解除封锁：把该指纹的墓碑改为 allow_relearn=1 */
    public int release(String fingerprint) {
        return jdbc.update("UPDATE memory_revocation SET allow_relearn = 1 WHERE fingerprint = ?",
                fingerprint);
    }

    /** 清空全部墓碑（设置页「重置知识库」连带清除记忆时用） */
    public int clearAll() {
        return jdbc.update("DELETE FROM memory_revocation");
    }
}
