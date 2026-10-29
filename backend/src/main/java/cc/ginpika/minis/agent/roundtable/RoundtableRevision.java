package cc.ginpika.minis.agent.roundtable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 会话级发言版本（revision）——设计一 §五-1 的工程落地。
 *
 * <p>「每个任务维护一个递增的 revision / generation。旧的生成即使稍后返回，也必须经过版本校验：
 * 版本不符就标记为过期，不允许直接覆盖当前决策。」
 *
 * <p>圆桌里的用法：用户明确调整工作或重新进入规划时使会话 revision +1；普通对话保留版本。一次成员发言在
 * <b>开始生成时</b>记下当时的 baseline，生成结束、准备提交时比对——
 * <ul>
 *   <li>一致：正常落成正式发言，进入本轮讨论上下文；</li>
 *   <li>已变：说明这一轮已被用户的新要求取代，这次结果<b>丢弃</b>（不落成正式发言、不进上下文），
 *       本轮作废并基于最新状态重新调度。</li>
 * </ul>
 *
 * <p>这是单机单用户下的轻量实现：计数器放内存即可（进程重启后 revision 归零，而重启后本就没有
 * 「在跑的旧执行」，不存在需要跨重启比对的过期结果）。真正的持久化版本留给需要恢复的场景。
 */
@Component
public class RoundtableRevision {

    /** 会话 id → 当前版本 */
    private final Map<String, AtomicInteger> revisions = new ConcurrentHashMap<>();

    /** 取某会话当前版本（从未 bump 过 = 0） */
    public int current(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        AtomicInteger v = revisions.get(sessionId);
        return v == null ? 0 : v.get();
    }

    /**
     * 使某会话版本 +1（人的一句新话，或一次会改变约束的中断）。
     *
     * @return 新版本
     */
    public int bump(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        return revisions.computeIfAbsent(sessionId, k -> new AtomicInteger()).incrementAndGet();
    }

    /** 会话删除时清理计数 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            revisions.remove(sessionId);
        }
    }
}
