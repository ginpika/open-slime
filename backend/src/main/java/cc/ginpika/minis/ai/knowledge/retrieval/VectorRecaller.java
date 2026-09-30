package cc.ginpika.minis.ai.knowledge.retrieval;

import java.util.List;

import cc.ginpika.minis.ai.knowledge.store.ChunkStore;

/** 向量召回：同模型全量余弦 top-k（M0 暴力；后续可换 vec1 不影响编排） */
public final class VectorRecaller {

    private final ChunkStore store;
    private final int topK;

    public VectorRecaller(ChunkStore store, int topK) {
        this.store = store;
        this.topK = topK;
    }

    /** model 未配置或该模型无向量时返回空 */
    public List<Long> recall(String model, float[] queryVec) {
        if (model == null || model.isBlank() || queryVec == null || queryVec.length == 0) {
            return List.of();
        }
        return store.topByCosine(model, queryVec, topK);
    }
}
