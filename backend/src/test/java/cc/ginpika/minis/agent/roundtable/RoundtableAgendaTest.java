package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * 回归：会话议程必须「投递即返回」且「同会话串行」。
 *
 * <p>它替代了 P0 的会话闸门（{@code RoundtableSessionGate}）。原先调用方要抢锁、等上一轮退出，
 * HTTP 线程被占着；现在投递立刻返回，执行跑在议程线程上——串行由队列保证，不再靠调用方等待。
 */
class RoundtableAgendaTest {

    @Test
    void sameSessionRunsSerially() throws Exception {
        RoundtableAgenda agenda = new RoundtableAgenda();
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);

        agenda.submit("s1", () -> {
            log.add("a:start");
            firstStarted.countDown();
            sleepQuietly(120);
            log.add("a:end");
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        /* 第二条在第一条还没跑完时提交：它必须排队，不能插进去 */
        agenda.submit("s1", () -> log.add("b:start"));
        awaitUntil(() -> log.contains("b:start"), 2_000);

        assertEquals(List.of("a:start", "a:end", "b:start"), List.copyOf(log),
                "同会话的两次推进不得交错");
    }

    @Test
    void submitReturnsImmediately() {
        RoundtableAgenda agenda = new RoundtableAgenda();
        CountDownLatch release = new CountDownLatch(1);
        agenda.submit("s1", () -> awaitQuietly(release, 3_000));

        long startedAt = System.nanoTime();
        agenda.submit("s1", () -> {
        });
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        release.countDown();
        assertTrue(elapsedMillis < 500, "投递不得等待前一次推进跑完（实际 " + elapsedMillis + "ms）");
    }

    @Test
    void failureDoesNotStallTheQueue() throws Exception {
        RoundtableAgenda agenda = new RoundtableAgenda();
        CountDownLatch second = new CountDownLatch(1);

        agenda.submit("s1", () -> {
            throw new IllegalStateException("模型报错");
        });
        agenda.submit("s1", second::countDown);

        assertTrue(second.await(2, TimeUnit.SECONDS), "一次推进抛异常后，该会话的队列必须继续");
    }

    @Test
    void differentSessionsRunInParallel() throws Exception {
        RoundtableAgenda agenda = new RoundtableAgenda();
        CountDownLatch bothInside = new CountDownLatch(2);
        /* 两个会话各自一条队列：一个会话卡住不该拖住另一个 */
        agenda.submit("s1", () -> bothInside.countDown());
        agenda.submit("s2", () -> bothInside.countDown());
        assertTrue(bothInside.await(2, TimeUnit.SECONDS));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(10);
        }
    }
}
