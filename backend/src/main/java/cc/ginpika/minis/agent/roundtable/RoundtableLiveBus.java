package cc.ginpika.minis.agent.roundtable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 会话级运行时广播：一次推进产生的每一条流式事件（话题、谁在说、增量、分段、状态、收场），
 * 按<b>会话</b>广播给当前订阅者。
 *
 * <p><b>为什么要有它</b>：执行侧不该握着某个 HTTP 连接。原先 {@code AgentController} 把各自的
 * {@code SseEmitter} 直接做成下一步的出口，于是「谁在看」与「谁在跑」被绑成一体——连接断了、
 * 新的标签页打开了，都没法接上。现在执行侧只往总线上说，谁在听是订阅者的事：
 * 断线只是没人听（执行照常跑完落库），多个标签页可以同时听。
 *
 * <p><b>runId 的作用</b>：同会话可以并发发消息（人插话），每条消息各自是一次交互。事件带上
 * 发起方给的 runId，订阅者只转发自己关心的那一次——两条交互因此不会互相串台。
 * 它同时就是将来接上事件正本时的 {@code correlation_id}。
 *
 * <p>单机单用户，订阅者就是几个标签页，故用内存集合即可；不做跨进程，也不引入消息队列。
 * 这里广播的是<b>过程</b>（含逐字增量），不做持久化与补推——断线期间的过程看不回来，
 * 重连后拉一次历史即可（见 {@code docs/roundtable-v3-design.md} §4.2 的职责三分）。
 */
@Component
public class RoundtableLiveBus {

    /**
     * 订阅者：收到一条事件。
     *
     * @param runId   这条事件属于哪一次「人的一句话」（发起方生成）
     * @param name    事件名（topic / speaker / content / speech / state / done …）
     * @param payload 事件数据（由订阅者自行序列化）
     * @return false = 连接已失效，由总线就地摘除
     */
    @FunctionalInterface
    public interface Listener {
        boolean onEvent(String runId, String name, Object payload);
    }

    /** 会话 id → 订阅者集合 */
    private final Map<String, Set<Listener>> listeners = new ConcurrentHashMap<>();

    /**
     * 订阅某会话的运行时事件。
     *
     * @return 取消订阅的句柄（幂等）
     */
    public Runnable subscribe(String sessionId, Listener listener) {
        listeners.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> {
            Set<Listener> set = listeners.get(sessionId);
            if (set != null && set.remove(listener) && set.isEmpty()) {
                listeners.remove(sessionId, set);
            }
        };
    }

    /**
     * 广播一条事件。写失败（客户端已断开）的订阅者就地摘除——一次断线不该拖住别的订阅者，
     * 更不该影响正在跑的那一轮。
     */
    public void emit(String sessionId, String runId, String name, Object payload) {
        Set<Listener> set = listeners.get(sessionId);
        if (set == null || set.isEmpty()) {
            return;
        }
        set.removeIf(listener -> !listener.onEvent(runId, name, payload));
        if (set.isEmpty()) {
            listeners.remove(sessionId, set);
        }
    }
}
