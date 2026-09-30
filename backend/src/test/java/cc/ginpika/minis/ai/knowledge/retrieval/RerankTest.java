package cc.ginpika.minis.ai.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import cc.ginpika.minis.ai.knowledge.store.ChunkStore;

/**
 * Reranker 回归（不起 Spring 上下文）：按分重排、候选池截断与尾部追加，
 * 以及未配置（empty）/ 调用失败（异常）/ 响应畸形三种情况的 RRF 原序回退。
 */
class RerankTest {

    private static ChunkStore.Row row(long id) {
        return new ChunkStore.Row(id, "uuid" + id, "doc", "ref" + id, "标题" + id, "正文" + id,
                null, null, null, null, 1L, "enabled", 0L);
    }

    private static List<Long> ids(List<ChunkStore.Row> rows) {
        return rows.stream().map(ChunkStore.Row::id).toList();
    }

    @Test
    void reordersByScore() {
        List<ChunkStore.Row> rows = List.of(row(1), row(2), row(3));
        /* 末位给最高分 → 应升到首位 */
        Reranker reranker = new Reranker((q, docs) -> Optional.of(new double[]{0.1, 0.5, 0.9}), 20);
        assertEquals(List.of(3L, 2L, 1L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void fallsBackWhenNotConfigured() {
        List<ChunkStore.Row> rows = List.of(row(1), row(2), row(3));
        Reranker reranker = new Reranker((q, docs) -> Optional.empty(), 20);
        assertEquals(List.of(1L, 2L, 3L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void fallsBackOnFailure() {
        List<ChunkStore.Row> rows = List.of(row(1), row(2), row(3));
        Reranker reranker = new Reranker((q, docs) -> {
            throw new IllegalStateException("rerank 调用失败");
        }, 20);
        assertEquals(List.of(1L, 2L, 3L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void fallsBackOnMalformedScores() {
        List<ChunkStore.Row> rows = List.of(row(1), row(2), row(3));
        /* 分数组长度与候选数不符 → 视为响应畸形，原序返回 */
        Reranker reranker = new Reranker((q, docs) -> Optional.of(new double[]{0.9}), 20);
        assertEquals(List.of(1L, 2L, 3L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void reranksHeadOnlyAndAppendsTail() {
        List<ChunkStore.Row> rows = List.of(row(1), row(2), row(3), row(4));
        /* 候选池 2：只精排前两条（升位为 2,1），后两条按原序追加 */
        Reranker reranker = new Reranker((q, docs) -> {
            assertEquals(2, docs.size());
            return Optional.of(new double[]{0.1, 0.9});
        }, 2);
        assertEquals(List.of(2L, 1L, 3L, 4L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void skipsCallForSingleCandidate() {
        List<ChunkStore.Row> rows = List.of(row(1));
        Reranker reranker = new Reranker((q, docs) -> {
            throw new AssertionError("候选池 ≤1 不应调用 rerank");
        }, 20);
        assertEquals(List.of(1L), ids(reranker.rerank("q", rows)));
    }

    @Test
    void truncatesLongDocument() {
        ChunkStore.Row big = new ChunkStore.Row(1L, "u", "doc", "ref", "长标题", "x".repeat(5000),
                null, null, null, null, 1L, "enabled", 0L);
        List<ChunkStore.Row> rows = List.of(big, row(2));
        Reranker reranker = new Reranker((q, docs) -> {
            assertTrue(docs.get(0).length() <= 1000, "单条正文应截断至 1000 字符内");
            return Optional.of(new double[]{0.2, 0.8});
        }, 20);
        assertEquals(List.of(2L, 1L), ids(reranker.rerank("q", rows)));
    }
}
