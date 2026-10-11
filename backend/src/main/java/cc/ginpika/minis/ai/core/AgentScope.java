package cc.ginpika.minis.ai.core;

import java.util.function.Supplier;

/**
 * 「当前正在执行的是哪个 Agent」的线程内作用域。
 *
 * <p>为什么需要它：一次执行在入口处（首页对话 = 内置 Agent；圆桌 = 某位成员；心跳 = 某位 Agent）
 * 知道自己是「谁」，但真正产生副作用的地方埋得很深——比如 RAG 召回留痕落在
 * {@code KnowledgeToolbox} 的工具分发里，离执行入口隔着好几层。与其把 agentId 一路透传，不如在执行
 * 的最外层用本类圈出当前 Agent，深层写点在需要时 {@link #get()} 一下即可。
 *
 * <p>放在 {@code ai.core}（而非 {@code agent.*}）是为了守住分层：知识库那侧（{@code ai.knowledge}）
 * 需要读它，而 {@code ai.*} 不依赖 {@code agent.*}。它是一个跨层的运行时上下文，不属于任何一侧。
 *
 * <p>语义：{@link #run(String, Supplier)} 在 body 期间把当前值置为给定 id（非空才置），结束后恢复
 * 之前的值（支持嵌套；传 null 表示不改动，等价于直接执行 body）。ThreadLocal 只对当前线程可见——
 * 各处执行都跑在自己的线程上（对话请求线程 / 虚拟线程 / 心跳扫描派生的虚拟线程），互不串扰。
 */
public final class AgentScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AgentScope() {
    }

    /** 当前线程正在执行的 Agent id；不在任何执行作用域内时为 null */
    public static String get() {
        return CURRENT.get();
    }

    /** 直接设置当前 Agent id（空串按清除处理） */
    public static void set(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(agentId);
        }
    }

    /** 清除当前 Agent id */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在给定 Agent 作用域内执行 body：agentId 非空时置为当前值，执行完恢复之前的值；
     * agentId 为空时原样执行 body（不改动当前作用域）。
     */
    public static <T> T run(String agentId, Supplier<T> body) {
        if (agentId == null || agentId.isBlank()) {
            return body.get();
        }
        String previous = CURRENT.get();
        CURRENT.set(agentId);
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
}
