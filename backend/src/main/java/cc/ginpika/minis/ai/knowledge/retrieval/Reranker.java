package cc.ginpika.minis.ai.knowledge.retrieval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cc.ginpika.minis.ai.knowledge.store.ChunkStore;

/**
 * RRF 候选精排：取前 candidate 条送 rerank 模型按相关度重排，
 * 未进候选池的尾部按原序追加，保证召回完整性不因精排而下降。
 *
 * <p>读路径永不因精排失败：未配置（empty）、调用失败（异常）、响应畸形一律 log.warn
 * 并回退 RRF 原序。这与 EmbeddingClient「已配置但失败即抛错」的写路径语义相反——
 * 灌入方需感知失败，检索方则须始终给出结果。
 */
public final class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    /** 送模型的单条正文截断长度（多数 rerank 接口对单条长度有限制） */
    private static final int DOC_CHAR_LIMIT = 1000;

    private final RerankScorer scorer;
    private final int candidate;

    public Reranker(RerankScorer scorer, int candidate) {
        this.scorer = scorer;
        this.candidate = candidate;
    }

    /** 精排；任何不可用情况均原序返回 */
    public List<ChunkStore.Row> rerank(String query, List<ChunkStore.Row> rows) {
        int pool = Math.min(rows.size(), Math.max(1, candidate));
        if (pool <= 1) {
            return rows;
        }
        List<ChunkStore.Row> head = new ArrayList<>(rows.subList(0, pool));
        List<String> documents = head.stream().map(Reranker::docText).toList();

        long start = System.nanoTime();
        Optional<double[]> scored;
        try {
            scored = scorer.score(query, documents);
        } catch (RuntimeException e) {
            log.warn("rerank 调用失败，回退 RRF 原序: {}", e.getMessage());
            return rows;
        }
        if (scored.isEmpty()) {
            log.debug("rerank 未生效（未配置或响应畸形），保持 RRF 原序");
            return rows;
        }
        if (scored.get().length != head.size()) {
            log.warn("rerank 分数组长度 {} 与候选数 {} 不符，回退 RRF 原序",
                    scored.get().length, head.size());
            return rows;
        }
        log.info("rerank 精排 {} 条候选，耗时 {} ms",
                pool, (System.nanoTime() - start) / 1_000_000);
        return reorder(rows, head, pool, scored.get());
    }

    /** 按分降序重排候选池（同分保持原序），再接上未进池的尾部 */
    private static List<ChunkStore.Row> reorder(List<ChunkStore.Row> rows,
                                                List<ChunkStore.Row> head, int pool, double[] scores) {
        Integer[] order = new Integer[head.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(rank(scores[b]), rank(scores[a])));

        List<ChunkStore.Row> out = new ArrayList<>(rows.size());
        for (int i : order) {
            out.add(head.get(i));
        }
        out.addAll(rows.subList(pool, rows.size()));
        return out;
    }

    /** 非有限分（NaN/Inf）视为最低，避免污染排序 */
    private static double rank(double v) {
        return Double.isFinite(v) ? v : Double.NEGATIVE_INFINITY;
    }

    /** 送模型的文档文本：标题 + 正文，截断防超长 */
    private static String docText(ChunkStore.Row row) {
        String title = row.title() == null ? "" : row.title().strip();
        String content = row.content() == null ? "" : row.content();
        String text = title.isEmpty() ? content : title + "\n" + content;
        return text.length() <= DOC_CHAR_LIMIT ? text : text.substring(0, DOC_CHAR_LIMIT);
    }
}
