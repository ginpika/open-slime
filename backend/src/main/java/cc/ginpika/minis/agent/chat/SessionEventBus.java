package cc.ginpika.minis.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话事件总线（STEP 4）：把「某会话有新动静」从<b>轮询</b>改成<b>推送</b>。
 *
 * <p>原先前端每 4~5 秒轮一次 {@code GET /api/sessions}，只为发现「子代理的结论投回来了」——
 * 延迟高、请求多，短回答还可能被间歇漏掉。这里改为：事件发生时后端当场广播，订阅者（SSE 连接）
 * 即时收到；事件同时落库（见 {@link SessionEventStore}），断线重连按 {@code Last-Event-ID} 补推。
 *
 * <p>单机单用户，订阅者就几个标签页，故用内存集合 + 一把锁即可；不做跨进程，也不引入消息队列
 * （架构文档 §7：不在 M0 上分布式基础设施）。
 */
@Component
public class SessionEventBus {

    private static final Logger log = LoggerFactory.getLogger(SessionEventBus.class);

    /** 重连补推的条数上限：只补最近的，够覆盖一次刷新 / 切标签页的断线窗口 */
    private static final int REPLAY_LIMIT = 200;

    /** 一次会话事件：kind=delivered（有新消息投递，如子代理回报）| answered（生成答完）| todo（待办清单更新） */
    public static final String KIND_DELIVERED = "delivered";
    public static final String KIND_ANSWERED = "answered";
    public static final String KIND_AGENT_MESSAGE_SENT = "agent_message_sent";
    /** 个人待办清单（write_todos）更新：前端据此即时刷新会话页的 TODO 卡片 */
    public static final String KIND_TODO = "todo";
    /** 会话标题被自动生成：前端据此即时更新列表 / 会话头的标题 */
    public static final String KIND_TITLE = "title";

    /**
     * @param sessionId 归属会话
     * @param kind      {@link #KIND_DELIVERED} / {@link #KIND_ANSWERED}
     * @param unread    该会话此刻是否未读（前端据此决定要不要弹「有新消息」气泡）
     * @param title     会话标题（便于通知气泡直接显示，省一次列表请求）
     */
    public record Event(String sessionId, String kind, boolean unread, String title) {
    }

    /** 订阅者：收到一条事件；返回 false 表示连接已失效，由总线就地摘除 */
    @FunctionalInterface
    public interface Listener {
        boolean onEvent(SessionEventStore.EventRow row);
    }

    private final SessionEventStore store;
    private final ObjectMapper om;
    private final Set<Listener> listeners = ConcurrentHashMap.newKeySet();

    /** 发布与订阅共用的一把锁：保证「补推存量 + 挂上实时」与「落库 + 广播」不交错、不重不漏 */
    private final Object lock = new Object();

    public SessionEventBus(SessionEventStore store, ObjectMapper om) {
        this.store = store;
        this.om = om;
    }

    /**
     * 发布一条事件：先落库拿到游标 id，再广播给在线订阅者。
     *
     * <p>落库失败（返回 null）时跳过广播并记一行 warn——宁可少推一次，也不让一次审计失败中断对话。
     *
     * @return 事件游标 id；落库失败返回 -1
     */
    public long publish(Event event) {
        String payload = writeJson(event);
        synchronized (lock) {
            SessionEventStore.EventRow row = store.record(event.sessionId(), event.kind(), payload);
            if (row == null) {
                log.warn("会话事件落库失败，跳过推送：session={} kind={}", event.sessionId(), event.kind());
                return -1L;
            }
            listeners.removeIf((l) -> !l.onEvent(row));
            return row.id();
        }
    }

    /**
     * 订阅事件流：若给了 {@code afterId}，先把该游标之后的存量事件补推一遍（断线重连不漏），
     * 再挂上实时订阅。
     *
     * @param afterId  上次收到的最后一条事件 id（{@code Last-Event-ID}）；null / 非正表示从头（不补推）
     * @param listener 收到事件时的回调
     * @return 取消订阅的句柄
     */
    public Runnable subscribe(Long afterId, Listener listener) {
        synchronized (lock) {
            if (afterId != null && afterId > 0) {
                for (SessionEventStore.EventRow row : store.since(afterId, REPLAY_LIMIT)) {
                    if (!listener.onEvent(row)) {
                        return () -> {
                        };
                    }
                }
            }
            listeners.add(listener);
        }
        return () -> listeners.remove(listener);
    }

    private String writeJson(Event event) {
        try {
            return om.writeValueAsString(event);
        } catch (Exception e) {
            /* 值对象序列化失败几乎不可能（全是字符串 / 布尔），兜底成最小 JSON，别让推送断在这里 */
            return "{\"sessionId\":\"" + event.sessionId() + "\",\"kind\":\"" + event.kind() + "\"}";
        }
    }
}
