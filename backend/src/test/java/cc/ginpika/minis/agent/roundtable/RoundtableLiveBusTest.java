package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 回归：会话级运行时广播的订阅语义。
 *
 * <p>它让「谁在说」与「谁在看」解耦——执行侧只往总线上说，SSE 连接是订阅者：
 * 断线只是摘掉一个订阅者（执行照常跑完落库），多个标签页可以同时听，
 * 而 runId 让同会话并发的两条交互互不串台（见 {@link RoundtableLiveBus}）。
 */
class RoundtableLiveBusTest {

    @Test
    void subscribersReceiveRunIdNameAndPayload() {
        RoundtableLiveBus bus = new RoundtableLiveBus();
        List<String> got = new ArrayList<>();
        bus.subscribe("s1", (runId, name, payload) -> {
            got.add(runId + ":" + name + ":" + payload);
            return true;
        });

        bus.emit("s1", "r1", "speech", "你好");
        bus.emit("s1", "r2", "done", 42);

        assertEquals(List.of("r1:speech:你好", "r2:done:42"), got);
    }

    @Test
    void failingSubscriberIsDroppedInPlace() {
        RoundtableLiveBus bus = new RoundtableLiveBus();
        AtomicInteger calls = new AtomicInteger();
        bus.subscribe("s1", (runId, name, payload) -> {
            calls.incrementAndGet();
            return false;    // 写失败 = 客户端已断开
        });

        bus.emit("s1", "r1", "speech", "a");
        bus.emit("s1", "r1", "speech", "b");

        assertEquals(1, calls.get(), "写失败的订阅者应被就地摘除，不该反复写");
    }

    @Test
    void unsubscribedListenerStopsReceiving() {
        RoundtableLiveBus bus = new RoundtableLiveBus();
        AtomicInteger calls = new AtomicInteger();
        Runnable unsubscribe = bus.subscribe("s1", (runId, name, payload) -> {
            calls.incrementAndGet();
            return true;
        });

        bus.emit("s1", "r1", "speech", "a");
        unsubscribe.run();
        bus.emit("s1", "r1", "speech", "b");

        assertEquals(1, calls.get());
    }

    @Test
    void sessionsAreIsolatedAndEmittingWithoutSubscribersIsHarmless() {
        RoundtableLiveBus bus = new RoundtableLiveBus();
        AtomicInteger delivered = new AtomicInteger();
        bus.subscribe("s1", (runId, name, payload) -> {
            delivered.incrementAndGet();
            return true;
        });

        bus.emit("s2", "r1", "speech", "x");    // 无人订阅：不抛、也不投给别人
        assertEquals(0, delivered.get());

        bus.emit("s1", "r1", "speech", "y");
        assertEquals(1, delivered.get());
    }
}
