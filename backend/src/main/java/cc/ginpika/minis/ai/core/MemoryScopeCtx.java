package cc.ginpika.minis.ai.core;

import java.util.function.Supplier;

/**
 * 「当前记忆作用域」的线程内绑定：给无法一路透传 {@link MemoryExec} 的深层读写点（如工具分发）
 * 一个 {@link #get()} 的取值点。手法与 {@link AgentScope} 一致，理由相同（跨层运行时上下文放 {@code ai.core}）。
 *
 * <p>作用域只对当前线程可见——各处执行都跑在自己的线程上（请求线程 / 虚拟线程），互不串扰。
 * 未圈定时 {@link #get()} 返回 null，读侧应回退为「无作用域」（保守：只能读画像与非项目绑定记忆）。
 */
public final class MemoryScopeCtx {

    private static final ThreadLocal<MemoryExec> CURRENT = new ThreadLocal<>();

    private MemoryScopeCtx() {
    }

    /** 当前作用域；未圈定返回 null */
    public static MemoryExec get() {
        return CURRENT.get();
    }

    /** 圈定当前作用域；exec 为 null 时等价于 {@link #clear()} */
    public static void set(MemoryExec exec) {
        if (exec == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(exec);
        }
    }

    /** 清除当前作用域 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 在给定作用域内执行 body，结束恢复之前的值（支持嵌套） */
    public static <T> T run(MemoryExec exec, Supplier<T> body) {
        if (exec == null) {
            return body.get();
        }
        MemoryExec previous = CURRENT.get();
        CURRENT.set(exec);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 无返回值的 {@link #run(MemoryExec, Supplier)} */
    public static void runVoid(MemoryExec exec, Runnable body) {
        run(exec, () -> {
            body.run();
            return null;
        });
    }
}
