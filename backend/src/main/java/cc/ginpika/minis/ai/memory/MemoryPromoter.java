package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 提升层（memory-v6 §9.3 · 大纲点名的 {@code MemoryPromoter}）：把 **L2 私有经验** 变成
 * **L4 发布候选**，让「一个 Agent 反复验证过的做法」有机会成为可共享的知识。
 *
 * <p>为什么是候选而不是直接发布：发布是显式动作（I7），自动流程不得自行跨层。
 * 扫描只**生成候选**，落地由人（管理页 `/promotions/{id}/apply`）或 Agent 确认。
 *
 * <p>判据（可配置）：L2 / 仍有效 / 非受保护（画像·pinned·open_issue·全局规则）/ 未被提升过 /
 * 且满足「证据等级 explicit|repeated」或「置信度 ≥ 阈值」。
 * 频次由调用方控制（水位 / 定时），本类不自己调度——与巩固器的分工一致。
 */
@Service
public class MemoryPromoter {

    private static final Logger log = LoggerFactory.getLogger(MemoryPromoter.class);

    private final MemoryItemStore items;
    private final MemoryPromotionStore promotions;
    private final MemoryProps props;

    public MemoryPromoter(MemoryItemStore items, MemoryPromotionStore promotions, MemoryProps props) {
        this.items = items;
        this.promotions = promotions;
        this.props = props;
    }

    /**
     * 扫一轮，生成 L2 → L4 的发布候选。
     *
     * @return 新建候选数（已有候选 / 已提升的不重复建）
     */
    public int scan() {
        int cap = Math.max(1, props.getPromote().getMaxPerScan());
        double minConfidence = props.getPromote().getMinConfidence();
        List<MemoryItemStore.ItemRow> candidates = items.active(cap).stream()
                .filter(it -> "L2".equals(it.layer()))
                .filter(it -> !MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(),
                        it.retention()))
                .filter(it -> it.promotedFrom() == null)
                .filter(it -> qualifies(it, minConfidence))
                .toList();
        int created = 0;
        for (MemoryItemStore.ItemRow it : candidates) {
            boolean already = promotions.listByItem(it.id()).stream()
                    .anyMatch(p -> "candidate".equals(p.state()) || "applied".equals(p.state()));
            if (already) {
                continue;
            }
            promotions.add(it.id(), it.layer(), MemoryVocabulary.LAYER_L4, "auto",
                    it.evidenceLevel() == null ? 1 : 1, "自动提升候选（" + it.scope() + "）");
            created++;
        }
        if (created > 0) {
            log.info("记忆提升扫描：生成 {} 条 L4 发布候选", created);
        }
        return created;
    }

    /** 提升判据：证据等级够硬，或置信度够高 */
    private static boolean qualifies(MemoryItemStore.ItemRow it, double minConfidence) {
        String level = it.evidenceLevel() == null ? "" : it.evidenceLevel();
        return MemoryVocabulary.EVIDENCE_EXPLICIT.equals(level)
                || MemoryVocabulary.EVIDENCE_REPEATED.equals(level)
                || it.confidence() >= minConfidence;
    }
}
