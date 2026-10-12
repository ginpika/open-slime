package cc.ginpika.minis.ai.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆运行指标（memory-v6 §17.2）：把「不变量是否被守住、预算有没有挤出、快照有没有覆盖」变成可观测数字。
 *
 * <p>刻意做成**进程内静态计数器**而不是引入指标框架：这些量的用途是「出问题时一眼能看到在漏」，不承担
 * 聚合与持久化；接入 Prometheus / Micrometer 时只需把这些 static 换成对应实现，调用点一行不动。
 * 重启清零（原始数据都在库里，需要历史可查表）。
 */
public final class MemoryMetrics {

    /** 无作用域写入被拒（I1）：正常应为 0，一旦增长说明有写点没圈定作用域 */
    private static final AtomicLong SCOPE_MISSING = new AtomicLong();
    /** 归属矩阵违例被拒（I3–I6）：正常应为 0，增长说明有调用方在造非法归属 */
    private static final AtomicLong INVARIANT_VIOLATION = new AtomicLong();
    /** 乐观锁冲突（I9）：说明出现了并发写同一记忆，应观察是否需要人工裁决 */
    private static final AtomicLong WRITE_CONFLICT = new AtomicLong();
    /** 被 token 预算挤出的条目数（§8.3）：持续偏高说明预算与信息量不匹配 */
    private static final AtomicLong BUDGET_EVICTED = new AtomicLong();
    /** 构建过的快照数（分母） */
    private static final AtomicLong SNAPSHOT_BUILT = new AtomicLong();
    /** 落库的回放记录数（分子）：覆盖率低说明回放没开或写入失败 */
    private static final AtomicLong SNAPSHOT_SAVED = new AtomicLong();
    /** 跨域 / 越权写入被拦（I7、role-play 硬规则）：增长是好事（闸门在干活），但要能看见 */
    private static final AtomicLong CROSS_DOMAIN_BLOCKED = new AtomicLong();

    private MemoryMetrics() {
    }

    public static void scopeMissing() {
        SCOPE_MISSING.incrementAndGet();
    }

    public static void invariantViolation() {
        INVARIANT_VIOLATION.incrementAndGet();
    }

    public static void writeConflict() {
        WRITE_CONFLICT.incrementAndGet();
    }

    public static void budgetEvicted(int count) {
        if (count > 0) {
            BUDGET_EVICTED.addAndGet(count);
        }
    }

    public static void snapshotBuilt() {
        SNAPSHOT_BUILT.incrementAndGet();
    }

    public static void snapshotSaved() {
        SNAPSHOT_SAVED.incrementAndGet();
    }

    public static void crossDomainBlocked() {
        CROSS_DOMAIN_BLOCKED.incrementAndGet();
    }

    /** 当前计数快照（含派生值）：供 `GET /api/memory/metrics` 与管理页展示 */
    public static Map<String, Object> snapshot() {
        long built = SNAPSHOT_BUILT.get();
        long saved = SNAPSHOT_SAVED.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scopeMissing", SCOPE_MISSING.get());
        out.put("invariantViolation", INVARIANT_VIOLATION.get());
        out.put("writeConflict", WRITE_CONFLICT.get());
        out.put("budgetEvicted", BUDGET_EVICTED.get());
        out.put("crossDomainBlocked", CROSS_DOMAIN_BLOCKED.get());
        out.put("snapshotBuilt", built);
        out.put("snapshotSaved", saved);
        out.put("snapshotCoverage", built == 0 ? 0d : Math.round((double) saved / built * 1000) / 1000d);
        return out;
    }

    /** 仅供测试：清零 */
    static void reset() {
        SCOPE_MISSING.set(0);
        INVARIANT_VIOLATION.set(0);
        WRITE_CONFLICT.set(0);
        BUDGET_EVICTED.set(0);
        SNAPSHOT_BUILT.set(0);
        SNAPSHOT_SAVED.set(0);
        CROSS_DOMAIN_BLOCKED.set(0);
    }
}
