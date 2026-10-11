package cc.ginpika.minis.agent.roundtable;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 会话议程：把圆桌的推进从 HTTP 请求栈里搬出来，落到会话自己的串行队列上。
 *
 * <p><b>解决的问题</b>：{@code POST /api/agents/chat/stream} 原先在请求线程里一路跑完整轮
 * （选人 → 让某位成员发言 → 判定收场），于是 SSE 连接的寿命 = 整轮执行的寿命，300s 一到就被掐；
 * 而「人的新话打断上一轮」只能靠一把锁去等上一轮退出（见 P0 的 {@code RoundtableSessionGate}），
 * 调用方要阻塞。
 *
 * <p><b>现在的形态</b>：投递即返回。HTTP 线程只负责「订阅事件流 + 把这一轮排进议程」，
 * 执行跑在议程线程上，SSE 断开与否则与它无关（断线只意味着没人看，不代表停止）。
 * 同一会话的推进按<b>提交顺序串行</b>跑，所以不再需要调用方抢锁——P0 的闸门由此被取代。
 *
 * <p><b>与真正事件驱动的差距</b>：这里的队列是<b>内存</b>的，进程重启即丢（只影响「还没开始跑」
 * 的那几次推进，已落库的消息与事件不受影响）。要「重启后续跑」需要把调度意图也落库，
 * 那是 P2 的持久化与恢复（见 {@code docs/roundtable-v3-design.md} §7）。内存队列不能替代它。
 */
@Component
public class RoundtableAgenda {

    private static final Logger log = LoggerFactory.getLogger(RoundtableAgenda.class);

    /** 真正跑工作的线程：虚拟线程，一次任务一条，不占平台线程 */
    private final Executor workers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("roundtable-agenda-", 0).factory());

    /** 会话 id → 该会话的串行队列（同一会话的推进按提交顺序跑，不会交错） */
    private final Map<String, SerialExecutor> queues = new ConcurrentHashMap<>();

    /**
     * 把一次推进排进某会话的议程。
     *
     * <p><b>立即返回</b>：不等待它跑完，也不因为该会话已有推进在跑而阻塞——后到的那次排在队尾。
     */
    public void submit(String sessionId, Runnable work) {
        queues.computeIfAbsent(sessionId, k -> new SerialExecutor(workers)).execute(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                /* 一次推进崩了：记一行，队列照常往下走（见 SerialExecutor 的 finally）。
                   正常路径上调用方自己已处理并广播了 error，这里兜的是它没处理到的那一类 */
                log.warn("圆桌议程任务失败：session={} error={}", sessionId, e.toString(), e);
            }
        });
    }

    /**
     * 会话级串行执行器：任务按提交顺序一个接一个跑。
     *
     * <p>JDK {@code Executors} 文档里的标准写法——不额外占线程，靠「上一个任务跑完时顺手取下
     * 一个」来串行。{@code finally} 里推进队列，所以一次推进抛异常（模型报错、上游拒收）
     * <b>不会把这会话的队列卡死</b>，后续输入照常能被处理。
     */
    private static final class SerialExecutor implements Executor {

        private final Executor delegate;
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private Runnable active;

        SerialExecutor(Executor delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void execute(Runnable command) {
            tasks.offer(() -> {
                try {
                    command.run();
                } finally {
                    scheduleNext();
                }
            });
            if (active == null) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            if ((active = tasks.poll()) != null) {
                delegate.execute(active);
            }
        }
    }
}
