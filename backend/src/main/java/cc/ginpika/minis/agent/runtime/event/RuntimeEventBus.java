package cc.ginpika.minis.agent.runtime.event;

import cc.ginpika.minis.agent.runtime.event.RuntimeEventStore.EventRow;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 运行时事件总线：把事件<b>落成正本</b>再广播给订阅者，并做「相关性路由 + 事件合并」。
 *
 * <p>设计一 §五-3 的三条防事件风暴约定都落在这里：
 * <ol>
 *   <li><b>事件订阅</b>——只有订阅某会话的订阅者才被唤醒；</li>
 *   <li><b>事件合并</b>——同一会话同一目标同一类型的一串普通通知（{@link RuntimeEventType#coalescible()}）
 *       在短窗口内只广播一次（正本仍逐条落库，审计不丢）；</li>
 *   <li><b>去重</b>——同一 {@code event_id} 重复投递不产生第二条，也不重复广播。</li>
 * </ol>
 *
 * <p>单机单用户，订阅者就是几个标签页 / 议程线程，故用内存集合即可；不做跨进程、不引入消息队列
 * （M0 约定）。事件正本落 SQLite，断线按游标补推——内存队列不替代持久化与恢复。
 */
@Component
public class RuntimeEventBus {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEventBus.class);

    /** 普通通知的合并窗口（毫秒）：窗口内的同类通知只广播一次 */
    private static final long COALESCE_WINDOW_MS = 300L;

    /** 断线补推的条数上限 */
    private static final int REPLAY_LIMIT = 500;

    /**
     * 订阅者：收到一条事件。
     *
     * @return false = 连接已失效（由总线就地摘除）
     */
    @FunctionalInterface
    public interface Listener {
        boolean onEvent(EventRow row);
    }

    private final RuntimeEventStore store;
    /** 会话 id → 订阅者集合 */
    private final Map<String, Set<Listener>> listeners = new ConcurrentHashMap<>();
    /** 全局订阅者：收到<b>所有会话</b>的事件（协调器 / 执行运行时据此把控制信号转成动作） */
    private final Set<Listener> global = ConcurrentHashMap.newKeySet();
    /** 合并窗口水位：{@code sessionId|target|type} → 上次广播时间 */
    private final Map<String, Long> lastBroadcast = new ConcurrentHashMap<>();

    public RuntimeEventBus(RuntimeEventStore store) {
        this.store = store;
    }

    /**
     * 订阅<b>所有会话</b>的事件。
     *
     * <p>协调器 / 执行运行时用它在全局层面接收控制信号（{@code WORK_STARTED}、{@code DISCUSSION_REQUESTED}、
     * {@code AGENT_REPORT}）——这些信号往往在「当前没有一轮在跑」时到达，按会话订阅只在推进期间有效，
     * 接不到它们。
     */
    public Runnable subscribeAll(Listener listener) {
        global.add(listener);
        return () -> global.remove(listener);
    }

    /**
     * 发布一条事件：先落正本（幂等），再按合并规则决定要不要广播。
     *
     * <p>落库失败不广播（宁可少推一次，也不让审计与推送不一致），记一行 warn。
     *
     * @return 事件正本的行 id；落库失败返回 -1
     */
    public long publish(RuntimeEvent event) {
        RuntimeEventStore.AppendResult appended;
        try {
            appended = store.append(event);
        } catch (RuntimeException e) {
            log.warn("运行时事件落库失败，跳过广播：session={} type={} error={}",
                    event.sessionId(), event.type(), e.toString());
            return -1L;
        }
        if (appended.id() <= 0) {
            return appended.id();
        }
        /* 重复投递（同 event_id）：幂等命中，不重复广播——「同一事件重复投递不能重复执行副作用」 */
        if (!appended.created()) {
            return appended.id();
        }
        long id = appended.id();
        /* 合并判定：只有「普通通知」且窗口内有同类广播才跳过。用户消息 / 报告 / 打断 / 取消不合并 */
        if (event.type().coalescible() && coalesced(event)) {
            return id;
        }
        EventRow row = new EventRow(id, event.eventId(), event.sessionId(), event.type().name(),
                event.source(), event.target(), event.taskId(), event.priority(),
                event.correlationId(), null, event.createdAt());
        broadcast(row);
        return id;
    }

    /** 订阅某会话的事件流：先补推游标之后的存量（断线重连不漏），再挂上实时订阅 */
    public Runnable subscribe(String sessionId, Long afterId, Listener listener) {
        if (afterId != null && afterId > 0) {
            for (EventRow row : store.since(sessionId, afterId, REPLAY_LIMIT)) {
                if (!listener.onEvent(row)) {
                    return () -> {
                    };
                }
            }
        }
        listeners.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> {
            Set<Listener> set = listeners.get(sessionId);
            if (set != null && set.remove(listener) && set.isEmpty()) {
                listeners.remove(sessionId, set);
            }
        };
    }

    /** 广播一条已落库的事件；写失败（订阅者已断开）的就地摘除 */
    public void broadcast(EventRow row) {
        Set<Listener> set = listeners.get(row.sessionId());
        if (set != null && !set.isEmpty()) {
            set.removeIf(listener -> !listener.onEvent(row));
            if (set.isEmpty()) {
                listeners.remove(row.sessionId(), set);
            }
        }
        if (!global.isEmpty()) {
            global.removeIf(listener -> !listener.onEvent(row));
        }
    }

    /**
     * 合并判定：窗口内已广播过同类通知则跳过（并刷新水位）。
     *
     * <p>只对 {@link RuntimeEventType#coalescible()} 生效——调用方已在 {@link #publish} 里判过。
     */
    private boolean coalesced(RuntimeEvent event) {
        String key = event.sessionId() + "|" + event.target() + "|" + event.type();
        long now = System.currentTimeMillis();
        Long last = lastBroadcast.get(key);
        lastBroadcast.put(key, now);
        return last != null && now - last < COALESCE_WINDOW_MS;
    }
}
