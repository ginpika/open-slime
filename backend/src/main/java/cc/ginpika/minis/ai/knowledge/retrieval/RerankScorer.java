package cc.ginpika.minis.ai.knowledge.retrieval;

import java.util.List;
import java.util.Optional;

/**
 * Rerank 打分器：对 query 与一组文档逐条打分。
 *
 * <p>返回数组与 documents 逐位对齐；未配置或响应畸形时返回 empty。
 * 调用失败（网络/HTTP）抛 RuntimeException，由 {@link Reranker} 统一兜底回退——
 * 读路径不可因精排失败而中断。
 */
@FunctionalInterface
public interface RerankScorer {

    Optional<double[]> score(String query, List<String> documents);
}
