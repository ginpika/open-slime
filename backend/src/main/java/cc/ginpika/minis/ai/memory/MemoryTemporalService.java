package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryEdgeStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 双时态点查（memory-v6 §7.1 / §12.4）：回答「**某一时刻我知道什么**」。
 *
 * <p>这是 v6 对 Zep/Graphiti 双时态的具体落点：条目与知识边都带
 * {@code valid_at（事实成立）/ invalid_at（事实失效）}，矛盾时只**关闭有效期**、从不物理删除，
 * 于是「上周我坚持的方案是什么」「那条并发风险是什么时候被推翻的」都能点查。
 *
 * <p>取代链（{@link #history}）则是同一条记忆自身的演化：旧 → 新逐版本列出，配合 revision 审计可完整回溯。
 */
@Service
public class MemoryTemporalService {

    /** 某时刻的世界视图：该时刻仍有效的条目 + 知识边 */
    public record AsOfView(long at, List<MemoryItemStore.ItemRow> items,
                           List<MemoryEdgeStore.EdgeRow> edges) {
    }

    private static final int LIMIT_MAX = 500;

    private final MemoryItemStore items;
    private final MemoryEdgeStore edges;

    public MemoryTemporalService(MemoryItemStore items, MemoryEdgeStore edges) {
        this.items = items;
        this.edges = edges;
    }

    /** 点查某时刻仍有效的条目与知识边 */
    public AsOfView asOf(long at, int limit) {
        int cap = Math.max(1, Math.min(limit, LIMIT_MAX));
        return new AsOfView(at, items.asOf(at, cap), edges.asOf(at, cap));
    }

    /** 某条目的取代链（旧 → 新）；条目不存在时返回空列表 */
    public List<MemoryItemStore.ItemRow> history(long itemId) {
        return items.history(itemId);
    }
}
