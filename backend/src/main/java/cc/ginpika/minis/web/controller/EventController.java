package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.SessionEventBus;
import cc.ginpika.minis.agent.chat.SessionEventStore;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 会话事件流（STEP 4）：全局一条 SSE，把「哪个会话有新动静」即時推给所有在线标签页，
 * 取代前端原先每 4~5 秒一次 {@code GET /api/sessions} 的轮询。
 *
 * <p>协议：每条事件是一个 SSE 帧 {@code id: <游标> / event: session / data: <JSON>}，data 形如
 * {@code {"sessionId":"…","kind":"delivered|answered","unread":true,"title":"…"}}。前端把最近一条的
 * {@code id} 记下，断线重连时放进标准的 {@code Last-Event-ID} 请求头，后端据此把断线期间漏掉的补推一遍。
 *
 * <p>与 {@code /api/sessions/{id}/live/stream} 的分工：那条是「某一会话本次生成的逐字增量」（断点续传），
 * 本流是「哪个会话有新消息 / 答完了」的轻量信号——不带正文，只够触发前端去取一次历史。
 */
@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    /** SSE 流最长保持：与 nginx 的 proxy_read_timeout（300s）对齐；到点由前端重连（带 Last-Event-ID 续上） */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final SessionEventBus events;

    public EventController(SessionEventBus events) {
        this.events = events;
    }

    @GetMapping(value = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        Runnable unsubscribe = events.subscribe(parseId(lastEventId),
                row -> send(emitter, alive, row));
        emitter.onCompletion(() -> {
            alive.set(false);
            unsubscribe.run();
        });
        emitter.onTimeout(() -> {
            alive.set(false);
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(e -> {
            alive.set(false);
            unsubscribe.run();
        });
        return emitter;
    }

    /** 推一帧；写失败即客户端已断开，标记失效并返回 false（总线据此摘除本订阅） */
    private static boolean send(SseEmitter emitter, AtomicBoolean alive, SessionEventStore.EventRow row) {
        if (!alive.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(row.id()))
                    .name("session")
                    .data(row.payload()));
            return true;
        } catch (Exception e) {
            alive.set(false);
            return false;
        }
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 客户端断开导致写流失败：常态（切页 / 关标签），静默收尾即可，不打 ERROR 刷屏（同 ChatController） */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("事件流客户端已断开：{}", e.getMessage());
    }
}
