package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.store.MemoryItemEmbeddingStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆条目向量轴的写入门面（与 {@link MemoryWriter} / {@link MemoryEmbeddingWriter} 平级）。
 *
 * <p>两条入口，对齐 {@link MemoryEmbeddingWriter}：
 * <ul>
 *   <li><b>写入后异步补齐</b>——落条目成功后调 {@link #complementAsync}，虚拟线程后台算，
 *       失败 / 未配置跳过，不阻塞主流程；定时扫描补齐欠账；</li>
 *   <li><b>扫描补齐</b>——{@link #backfill(int)} 找出缺当前模型向量的条目补一批。</li>
 * </ul>
 *
 * <p>向量化文本 = 标题 + 正文（与记忆事件的拼法一致）。
 */
@Component
@org.springframework.scheduling.annotation.EnableScheduling
public class MemoryItemEmbeddingWriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryItemEmbeddingWriter.class);

    /** 单任务线程池（虚拟线程：补向量是短任务，无需池化复用） */
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** 送 embedding 的文本上限（标题 + 正文） */
    private static final int EMBED_TEXT_LIMIT = 600;

    /** 补齐入口单次扫描的条数上限 */
    public static final int BACKFILL_MAX = 64;

    private final MemoryItemStore items;
    private final MemoryItemEmbeddingStore vectors;
    private final EmbeddingClient embedding;

    public MemoryItemEmbeddingWriter(MemoryItemStore items, MemoryItemEmbeddingStore vectors,
                                     EmbeddingClient embedding) {
        this.items = items;
        this.vectors = vectors;
        this.embedding = embedding;
    }

    /** 落条目后异步补一条向量：任何失败只留日志，绝不重试、绝不冒泡回主流程 */
    public void complementAsync(long itemId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        public void afterCommit() { submit(itemId); }
                    });
            return;
        }
        submit(itemId);
    }

    private void submit(long itemId) {
        if (!embedding.configured()) {
            return;
        }
        POOL.submit(() -> {
            try {
                embedItem(items.findById(itemId).orElse(null));
            } catch (RuntimeException e) {
                log.warn("记忆条目向量补齐失败（跳过，不重试）：item={} {}", itemId, e.getMessage());
            }
        });
    }

    /** 扫描缺当前模型向量的条目并补齐（新建在前）：返回成功补齐的条数 */
    public int backfill(int limit) {
        if (!embedding.configured() || limit <= 0) {
            return 0;
        }
        int done = 0;
        for (MemoryItemStore.ItemRow row
                : vectors.itemsMissing(embedding.model(), Math.min(limit, BACKFILL_MAX))) {
            if (embedItem(row)) {
                done++;
            }
        }
        return done;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void repairMissing() {
        try { backfill(BACKFILL_MAX); }
        catch (RuntimeException e) { log.warn("条目向量重试失败：{}", e.getMessage()); }
    }

    /** 向量化一条条目并落库：正文全空 / 调用失败 / 未配置返回 false（已留日志），不抛错 */
    private boolean embedItem(MemoryItemStore.ItemRow row) {
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
                        return vectors.saveVersion(row.id(), embedding.model(), vec,
                                row.version() == null ? 0 : row.version());
                    })
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("记忆条目向量化失败（跳过，不重试）：item={} {}", row.id(), e.getMessage());
            return false;
        }
    }

    /** 送模型的文本：标题 + 正文，截断防超长 */
    private static String embedText(MemoryItemStore.ItemRow row) {
        String title = row.title() == null ? "" : row.title().strip();
        String body = row.body() == null ? "" : row.body();
        String text = title.isEmpty() ? body : title + "\n" + body;
        return text.length() <= EMBED_TEXT_LIMIT ? text : text.substring(0, EMBED_TEXT_LIMIT);
    }
}
