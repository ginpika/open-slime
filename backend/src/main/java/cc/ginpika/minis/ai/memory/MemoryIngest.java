package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.config.MemoryProps;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆写入的攒批缓冲（STEP M2 · 成本优化）：把「每轮提炼」改成「攒够 N 轮或空闲 X 分钟后合并提炼一次」。
 *
 * <p><b>为什么</b>：逐轮提炼每轮都要付一次固定提示词（~300 token）与一次输出，短对话下开销占比很高。
 * 攒批后，N 轮对话只提炼一次，固定开销摊薄一个数量级；总输入 token 相近，但调用次数与输出 token 大降。
 *
 * <p><b>方向约束</b>：缓冲在 memory 侧、自建调度（分层测试禁止 memory 依赖 agent），
 * 会话侧只 {@link #tap} 推入——push 不是 pull。
 *
 * <p>两条触发：① 缓冲轮数达到 {@link MemoryProps#getBatchTurns()}；② 会话静默达到
 * {@link MemoryProps#getIdleMs()}。命中「记住」类指令时**立即**提炼（对齐显式记忆的即时性，不等攒批）。
 *
 * <p>缓冲在内存里，进程重启即丢——记忆本就可重建，不值得为它引入持久化队列。
 */
@Component
public class MemoryIngest {

    private static final Logger log = LoggerFactory.getLogger(MemoryIngest.class);

    /** 缓冲器扫描周期（毫秒）：判「攒够 / 空闲」到点没 */
    private static final long SCAN_MS = 30_000L;

    /** 单轮助手回复进缓冲时的截断长度（缓冲会拼多轮，控制总长） */
    private static final int ASSISTANT_CLIP = 600;

    private final MemoryProps props;
    private final MemoryExtractor extractor;

    /** 待提炼缓冲：sessionId → Buffer */
    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scanner;
    private final Executor work = Executors.newVirtualThreadPerTaskExecutor();

    public MemoryIngest(MemoryProps props, MemoryExtractor extractor) {
        this.props = props;
        this.extractor = extractor;
    }

    @PostConstruct
    void start() {
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-ingest");
            t.setDaemon(true);
            return t;
        });
        scanner.scheduleWithFixedDelay(this::scan, SCAN_MS, SCAN_MS, TimeUnit.MILLISECONDS);
        log.info("记忆攒批缓冲已就绪：batchTurns={} idleMs={} scanMs={}",
                props.getBatchTurns(), props.getIdleMs(), SCAN_MS);
    }

    @PreDestroy
    void stop() {
        if (scanner != null) {
            scanner.shutdownNow();
        }
    }

    /** 一轮对话结束：进该会话的缓冲；命中「记住」类指令则立即提炼（不等攒批） */
    public void tap(String sessionId, MemoryExec exec, String userText, String assistantText) {
        tap(sessionId, exec, userText, assistantText, null);
    }

    public void tap(String sessionId, MemoryExec exec, String userText, String assistantText, String refs) {
        if (!props.isEnabled() || sessionId == null || sessionId.isBlank()
                || userText == null || userText.isBlank()) {
            return;
        }
        /* memory-v6 §4.2 · I1：写入必须有执行作用域。显式传入优先，否则取执行入口圈定的当前作用域；
           都没有 → 按 minis.memory.scope.enforce 抛错（开发期）/ 告警丢弃（生产），绝不写无归属记忆 */
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (!ScopeGuard.allowWrite(props, scope, "MemoryIngest.tap")) {
            return;
        }
        boolean explicit = MemoryExtractor.isExplicit(userText);
        String key = sessionId + "|" + scope.agentOrBuiltin() + "|" + scope.projectId()
                + "|" + scope.identityOrInferred();
        buffers.compute(key, (k, current) -> {
            Buffer b = current == null ? new Buffer() : current;
            synchronized (b) {
                b.exec = scope;
                b.refs = MemoryEvidence.merge(b.refs, refs);
                b.turns.add(format(userText, assistantText));
                b.explicit = b.explicit || explicit;
                b.lastAt = System.currentTimeMillis();
            }
            return b;
        });
        /* 显式记忆：用户当场要求记住，立即提炼，不等攒批 */
        if (explicit) {
            work.execute(() -> flush(key));
        }
    }

    /**
     * 一次执行（子代理 / 心跳职责 / 计划）收尾：把**结论**直接送提炼（memory-v6 §7.3，修 D7）。
     *
     * <p>为什么与对话攒批分开：执行结论本身就是一段已成型的产出（不是逐轮闲聊），
     * 它没有「会话缓冲」这个容器，也不该等攒批——直接提炼，落在该执行的作用域里
     * （子代理 → 派发方 Agent × 本会话；心跳 → 该 Agent 自己）。
     *
     * <p>作用域取自执行入口圈定的 {@link ScopeGuard}；没有作用域就**不提炼**（I1），
     * 只留一条 debug 日志——不因记忆旁路影响执行主流程。
     */
    public void tapExec(String title, String output) {
        tapExec(null, title, output);
    }

    /** 同 {@link #tapExec(String, String)}，但作用域由调用方显式给出（如工作集蒸馏） */
    public void tapExec(MemoryExec explicit, String title, String output) {
        if (!props.isEnabled() || output == null || output.isBlank()) {
            return;
        }
        MemoryExec exec = ScopeGuard.resolve(explicit);
        if (exec == null) {
            log.debug("执行结论不提炼：当前线程没有记忆作用域（memory-v6 I1）");
            return;
        }
        String text = "【" + (title == null || title.isBlank() ? "执行结论" : title.strip()) + "】\n"
                + output.strip();
        extractor.extractConversationAsync(exec, text, false,
                exec.taskId() == null ? null : MemoryEvidence.ref("task", exec.taskId(), text));
    }

    public java.util.concurrent.CompletableFuture<Boolean> distill(MemoryExec exec, String text, String refs) {
        return extractor.distillAsync(exec, text, refs);
    }

    /** 扫描：攒够轮数或空闲到点，就把该会话的缓冲合并提炼 */
    private void scan() {
        if (!props.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        int batch = Math.max(1, props.getBatchTurns());
        long idle = Math.max(1_000L, props.getIdleMs());
        for (Map.Entry<String, Buffer> e : buffers.entrySet()) {
            boolean due;
            synchronized (e.getValue()) {
                Buffer b = e.getValue();
                due = !b.turns.isEmpty() && (b.turns.size() >= batch || now - b.lastAt >= idle);
            }
            if (due) {
                work.execute(() -> flush(e.getKey()));
            }
        }
    }

    /** 取出缓冲并合并提炼一次；缓冲已空则顺手清掉 key（避免 map 无界增长） */
    private void flush(String sessionId) {
        Buffer b = buffers.remove(sessionId);
        if (b == null) {
            return;
        }
        String text;
        String refs;
        MemoryExec exec;
        boolean explicit;
        int n;
        synchronized (b) {
            if (b.turns.isEmpty()) {
                // 保留空缓冲，避免 tap 已取得该对象时被移出 map 导致整轮材料丢失。
                return;
            }
            text = String.join("\n\n", b.turns);
            exec = b.exec;
            refs = b.refs; b.refs = null;
            explicit = b.explicit;
            n = b.turns.size();
            b.turns.clear();
            b.explicit = false;
        }
        extractor.extractConversationAsync(exec, text, explicit, refs);
        log.debug("记忆攒批提炼：session={} 轮数={}", sessionId, n);
    }

    private static String format(String userText, String assistantText) {
        StringBuilder sb = new StringBuilder("【用户】").append(userText.strip());
        if (assistantText != null && !assistantText.isBlank()) {
            String a = assistantText.strip();
            sb.append("\n【助手】").append(a.length() > ASSISTANT_CLIP
                    ? a.substring(0, ASSISTANT_CLIP) + "…" : a);
        }
        return sb.toString();
    }

    /** 某会话的待提炼缓冲 */
    private static final class Buffer {
        MemoryExec exec;
        String refs;
        final List<String> turns = new ArrayList<>();
        boolean explicit;
        long lastAt;
    }
}
