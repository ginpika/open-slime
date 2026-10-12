package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevocationStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 显式遗忘（purge，memory-v6 §12.5）：用户/管理员按条件撤回记忆的**唯一入口**。
 *
 * <p>两条硬规则：
 * <ul>
 *   <li><b>先预览后执行</b>：{@link #preview} 先给出影响面（命中数 / 其中有效条目 / 其中正式画像 / 样例），
 *       由调用方确认后再 {@link #purge}——尤其是命中画像时必须让人看到；</li>
 *   <li><b>可回滚、防重建</b>：purge 不物理删除，而是置 {@code revoked} + 写**墓碑**
 *       （{@code allow_relearn=0}），同指纹不会再被自动学习回来。</li>
 * </ul>
 *
 * <p>与「关闭共享」「归档」的区别：那是范围内收敛可见性，purge 是用户意图层面的「忘掉它」。
 */
@Service
public class MemoryPurgeService {

    private static final Logger log = LoggerFactory.getLogger(MemoryPurgeService.class);

    /** 单次 purge 的条目上限（防止一次误操作扫全库） */
    private static final int LIMIT_MAX = 500;

    /** 遗忘条件（全部可空；itemIds 给了就按 id 收窄） */
    public record Filter(List<Long> itemIds, String owner, String agentId, String projectId,
                         String scope, Long before, String reason) {
    }

    /** 预览结果：影响面 */
    public record Preview(int matched, int active, int userProfile,
                          List<MemoryItemStore.ItemRow> sample) {
    }

    private final MemoryItemStore items;
    private final MemoryRevocationStore revocations;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.ai.memory.store.MemoryShareStore shares;

    public MemoryPurgeService(MemoryItemStore items, MemoryRevocationStore revocations) {
        this.items = items;
        this.revocations = revocations;
    }

    /** 预览影响面（sampleLimit = 返回的样例条数） */
    public Preview preview(Filter filter, int sampleLimit) {
        List<MemoryItemStore.ItemRow> matched = items.findForPurge(ids(filter), owner(filter),
                agentId(filter), projectId(filter), scope(filter), before(filter), LIMIT_MAX);
        int active = 0;
        int profile = 0;
        for (MemoryItemStore.ItemRow it : matched) {
            if (MemoryVocabulary.STATUS_ACTIVE.equals(it.status())) {
                active++;
            }
            if (MemoryVocabulary.OWNER_USER.equals(it.owner())) {
                profile++;
            }
        }
        int cap = Math.max(0, sampleLimit);
        return new Preview(matched.size(), active, profile,
                cap == 0 ? List.of() : matched.subList(0, Math.min(cap, matched.size())));
    }

    /**
     * 执行遗忘：逐条置 {@code revoked} 并写墓碑。
     *
     * @return 实际处理的条数
     */
    @org.springframework.transaction.annotation.Transactional
    public int purge(Filter filter) {
        List<MemoryItemStore.ItemRow> matched = items.findForPurge(ids(filter), owner(filter),
                agentId(filter), projectId(filter), scope(filter), before(filter), LIMIT_MAX);
        int n = 0;
        for (MemoryItemStore.ItemRow it : matched) {
            if (shares != null) { shares.revokeByItem(it.id()); }
            items.setStatus(it.id(), MemoryVocabulary.STATUS_REVOKED);
            revocations.add(it.id(), it.fingerprint(), reason(filter), false);
            n++;
        }
        if (n > 0) {
            log.info("显式遗忘：{} 条记忆已撤销并写入墓碑（原因：{}）", n, reason(filter));
        }
        return n;
    }

    private static List<Long> ids(Filter f) {
        return f == null ? null : f.itemIds();
    }

    private static String owner(Filter f) {
        return f == null ? null : f.owner();
    }

    private static String agentId(Filter f) {
        return f == null ? null : f.agentId();
    }

    private static String projectId(Filter f) {
        return f == null ? null : f.projectId();
    }

    private static String scope(Filter f) {
        return f == null ? null : f.scope();
    }

    private static Long before(Filter f) {
        return f == null ? null : f.before();
    }

    private static String reason(Filter f) {
        return f == null || f.reason() == null ? "用户显式遗忘" : f.reason();
    }
}
