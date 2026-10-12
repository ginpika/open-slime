package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.config.MemoryProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 作用域守门（memory-v6 §4.2 · 不变量 I1「无作用域不得写库」）。
 *
 * <p>为什么需要它：v5 里 {@code MemoryScopeCtx} 定义了却零调用，深层写读点拿不到「我是谁、在哪个项目」，
 * 于是只能退化为「读全量」（工具面召回绕过谓词的真实原因）。v6 把作用域升为**执行契约**：
 * 写侧入口先在这里取作用域，取不到就拒绝并记审计告警；读侧取不到则退化为「只读正式画像」。
 *
 * <p>取值优先级：调用方显式传入的 {@link MemoryExec} → 执行入口用 {@link MemoryScopeCtx} 圈定的当前值。
 * 两者都空即视为缺失。开发/测试期由 {@code minis.memory.scope.enforce=true} 直接抛错，
 * 生产可置 false 只告警（行为等价于「丢弃这次写入」，绝不写入无归属的记忆）。
 */
public final class ScopeGuard {

    private static final Logger log = LoggerFactory.getLogger(ScopeGuard.class);

    private ScopeGuard() {
    }

    /**
     * 解析本次写入的作用域：显式 exec 优先，否则取线程内圈定的当前作用域。
     *
     * @param explicit 调用方显式传入的作用域（可空）
     * @return 解析到的作用域；两者皆空返回 {@code null}
     */
    public static MemoryExec resolve(MemoryExec explicit) {
        return explicit != null ? explicit : MemoryScopeCtx.get();
    }

    /**
     * 写入前的守门：作用域缺失时，按 {@code minis.memory.scope.enforce} 抛错或告警丢弃。
     *
     * @param props 记忆配置（enforce 开关）
     * @param exec  已解析的作用域（通常来自 {@link #resolve}）
     * @param where 写点标识（用于日志定位，如 {@code MemoryIngest.tap}）
     * @return true = 可以继续写入；false = 已拒绝（调用方直接返回）
     */
    public static boolean allowWrite(MemoryProps props, MemoryExec exec, String where) {
        if (exec != null) {
            return true;
        }
        boolean enforce = props == null || props.getScope().isEnforce();
        String message = "记忆写入缺少执行作用域（memory-v6 I1）：" + where;
        MemoryMetrics.scopeMissing();
        if (enforce) {
            throw new IllegalStateException(message);
        }
        log.warn("{}（已丢弃这次写入；如需严格模式请开启 minis.memory.scope.enforce）", message);
        return false;
    }

    /**
     * 读侧的作用域解析：缺失时退化为 {@link MemoryExec#none()}（只读正式画像），
     * **绝不**退化为「读全量」——这是 memory-v6 §4.2 对 v5 工具面漏洞的修复。
     */
    public static MemoryExec resolveRead(MemoryExec explicit) {
        MemoryExec e = resolve(explicit);
        return e != null ? e : MemoryExec.none();
    }
}
