package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.config.MemoryProps;
import java.util.ArrayList;
import java.util.List;

/**
 * 轮内写缓冲（memory-v6 §4.4）：一轮执行期间发生的记忆写入先入此缓冲，**轮末统一提交**。
 *
 * <p>解决的是「自我强化」：本轮 Agent 刚记住的东西如果立刻可见，同一轮的后续召回就会读到它，
 * 形成闭环放大。缓冲后语义变成——**本轮写入不回改本轮注入，下一轮才生效**（与快照配合，
 * 见 {@link MemorySnapshotBuilder}）。
 *
 * <p>边界（刻意保守）：
 * <ul>
 *   <li>没有圈定缓冲区的线程（后台心跳扫描、管理页、迁移）→ 直接执行，不引入隐式延迟；</li>
 *   <li>{@code minis.memory.staging.enabled=false} → 全部立即执行（本地排障用）；</li>
 *   <li>提交失败不抛给调用方（记忆是旁路），只留日志。</li>
 * </ul>
 */
public final class MemoryStage {

    private static final ThreadLocal<List<Runnable>> PENDING = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();
    private static final ThreadLocal<Long> STARTED = new ThreadLocal<>();
    public static void invalidateAll() { GENERATION.incrementAndGet(); }

    private MemoryStage() {
    }

    /** 开启本轮的写缓冲区（执行入口调用；重复 begin 复用缓冲，提交责任由执行入口管理） */
    public static void begin() {
        if (PENDING.get() == null) { PENDING.set(new ArrayList<>()); STARTED.set(GENERATION.get()); }
    }

    public static boolean active() { return PENDING.get() != null; }

    /**
     * 把一个写入动作放进本轮缓冲；已在缓冲区内返回 true（表示「已延后」），
     * 否则立即执行并返回 false（后台 / 管理页 / 关闭缓冲时）。
     */
    public static boolean defer(Runnable action, MemoryProps props) {
        if (action == null) {
            return false;
        }
        List<Runnable> list = PENDING.get();
        if(list!=null && !java.util.Objects.equals(STARTED.get(),GENERATION.get())) return true;
        if (list == null || (props != null && !props.getStaging().isEnabled())) {
            action.run();
            return false;
        }
        list.add(action);
        return true;
    }

    /** 本轮缓冲内待提交的动作数（0 = 无） */
    public static int pendingCount() {
        List<Runnable> list = PENDING.get();
        return list == null ? 0 : list.size();
    }

    /**
     * 轮末提交：按入队顺序执行并清空缓冲。
     *
     * @return 提交的动作数
     */
    public static int commit() {
        List<Runnable> list = PENDING.get();
        Long generation=STARTED.get(); STARTED.remove();
        PENDING.remove();
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (Runnable action : list) {
            if(!java.util.Objects.equals(generation,GENERATION.get())) break;
            try {
                action.run();
                done++;
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(MemoryStage.class).warn("记忆缓冲提交失败：{}", error.getMessage());
            }
        }
        return done;
    }

    /** 丢弃本轮缓冲（异常路径用：不留半截写入） */
    public static void clear() {
        PENDING.remove();
        STARTED.remove();
    }
}
