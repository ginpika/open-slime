package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.store.MemoryEventEmbeddingStore;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆向量轴的写入门面（与 {@link MemoryWriter} 平级：一个写事件，一个写事件的向量）。
 *
 * <p>两条入口：
 * <ul>
 *   <li><b>push 后异步补齐</b>——{@link MemoryWriter} 落库成功后调 {@link #complementAsync}，
 *       虚拟线程后台算，失败/未配置跳过，不重试不阻塞主流程（记忆是旁路，向量更是旁路的旁路）；</li>
 *   <li><b>扫描补齐</b>——{@link #backfill(int)} 找出缺当前模型向量的事件补一批，
 *       由 backfill_memory_vectors 工具暴露给记忆维护的任务计划（端点刚配好、或历史欠账时用）。</li>
 * </ul>
 *
 * <p>向量化一律走 {@link EmbeddingClient}；文本 = 标题 + 正文投影（与 rerank 送模型的拼法一致）。
 * 事件本身只追加不改写，这里的覆盖只发生在向量投影行上。
 */
@Component
public class MemoryEmbeddingWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingWriter.class);

    /** 记忆侧单任务线程池（虚拟线程：补向量是短任务，无需池化复用） */
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** 送 embedding 的文本上限：payload 投影本身 ≤500 字，标题另计，600 封顶 */
    private static final int EMBED_TEXT_LIMIT = 600;

    /** 补齐入口单次扫描的条数上限（工具参数再大也封顶，防止一次心跳里嵌爆） */
    public static final int BACKFILL_MAX = 64;

    private final MemoryEventStore events;
    private final MemoryEventEmbeddingStore vectors;
    private final EmbeddingClient embedding;

    public MemoryEmbeddingWriter(MemoryEventStore events, MemoryEventEmbeddingStore vectors,
                                 EmbeddingClient embedding) {
        this.events = events;
        this.vectors = vectors;
        this.embedding = embedding;
    }

    /** push 后异步补一条事件的向量：任何失败只留日志，绝不重试、绝不冒泡回聊天/执行主流程 */
    public void complementAsync(String eventKey) {
        if (!embedding.configured()) {
            return;
        }
        POOL.submit(() -> {
            try {
                embedEvent(events.byKey(eventKey));
            } catch (RuntimeException e) {
                log.warn("记忆事件向量补齐失败（跳过，不重试）：{} {}", eventKey, e.getMessage());
            }
        });
    }

    /**
     * 扫描缺当前模型向量的事件并补齐（时间倒序取最近的）：返回成功补齐的条数。
     * 单条失败跳过继续下一条（部分成功也算进度）；未配置直接返回 0。
     */
    public int backfill(int limit) {
        if (!embedding.configured() || limit <= 0) {
            return 0;
        }
        int done = 0;
        for (MemoryEventStore.EventRow row
                : vectors.eventsMissing(embedding.model(), Math.min(limit, BACKFILL_MAX))) {
            if (embedEvent(row)) {
                done++;
            }
        }
        return done;
    }

    /** 向量化一条事件并落库：正文全空/调用失败/未配置返回 false（已留日志），不抛错 */
    private boolean embedEvent(MemoryEventStore.EventRow row) {
        if (row == null) {
            return false;
        }
        String text = embedText(row);
        if (text.isBlank()) {
            return false;
        }
        try {
            return embedding.embed(text)
                    .map(vec -> {
                        vectors.save(row.id(), embedding.model(), vec);
                        return true;
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("记忆事件向量化失败（跳过，不重试）：{} {}", row.eventKey(), e.getMessage());
            return false;
        }
    }

    /** 送模型的文本：标题 + 正文投影（与 rerank 的 docText 拼法一致），截断防超长 */
    private static String embedText(MemoryEventStore.EventRow row) {
        String title = row.title() == null ? "" : row.title().strip();
        String payload = row.payload() == null ? "" : row.payload();
        String text = title.isEmpty() ? payload : title + "\n" + payload;
        return text.length() <= EMBED_TEXT_LIMIT ? text : text.substring(0, EMBED_TEXT_LIMIT);
    }
}
