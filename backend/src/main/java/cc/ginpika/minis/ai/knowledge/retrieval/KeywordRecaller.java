package cc.ginpika.minis.ai.knowledge.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import cc.ginpika.minis.ai.knowledge.store.ChunkStore;

/**
 * 关键词召回（两路，先 meta 后 cn、各取 top-k）：
 * 英文/代码 token → chunks_fts_meta（unicode61）；中文短语窗口 → chunks_fts_cn（trigram）。
 */
public final class KeywordRecaller {

    private final ChunkStore store;
    private final int topK;

    public KeywordRecaller(ChunkStore store, int topK) {
        this.store = store;
        this.topK = topK;
    }

    /** 返回按分排序的 chunk id；无关键词时返回空 */
    public List<Long> recall(QueryTerms.Parsed q) {
        if (!q.hasKeyword()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        if (!q.enTokens().isEmpty()) {
            ids.addAll(store.metaMatch(q.enTokens(), topK));
        }
        if (!q.cnPhrases().isEmpty()) {
            ids.addAll(store.cnMatch(q.cnPhrases(), topK));
        }
        return dedupe(ids);
    }

    private static List<Long> dedupe(List<Long> ids) {
        List<Long> out = new ArrayList<>(ids.size());
        HashSet<Long> seen = new HashSet<>();
        for (Long id : ids) {
            if (seen.add(id)) {
                out.add(id);
            }
        }
        return out;
    }
}
