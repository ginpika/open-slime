package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore;
import cc.ginpika.minis.ai.memory.store.MemoryCandidateStore.CandidateRow;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevocationStore;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户画像服务（memory-v5 §5.4）：把用户画像从「普通共享记忆」独立出来，做成**受保护数据域**。
 *
 * <p>它不是普通 Agent 随时可写的信息库，而是需要证据与规则才能更新的数据域：
 * <ul>
 *   <li>普通 Agent / 自动提炼**只能提交候选**（{@link #submit}），不得直写正式画像；</li>
 *   <li>候选经审批（{@link #approve}）方由本服务落为 {@code owner=USER × USER_PROFILE × L1} 的 active 条目；</li>
 *   <li>拒绝（{@link #reject}）与撤销（{@link #revoke}）都写**墓碑**，同指纹不再自动重建。</li>
 * </ul>
 *
 * <p><b>role-play 硬规则</b>：创作类项目（{@code narrative}）产出的画像一律 {@code source_kind=fiction}，
 * 本服务直接拒收——虚构设定永不进用户画像。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final MemoryCandidateStore candidates;
    private final MemoryRevocationStore revocations;
    private final MemoryItemStore items;
    private final MemoryItemEmbeddingWriter embeddings;

    public UserProfileService(MemoryCandidateStore candidates, MemoryRevocationStore revocations,
                              MemoryItemStore items, MemoryItemEmbeddingWriter embeddings) {
        this.candidates = candidates;
        this.revocations = revocations;
        this.items = items;
        this.embeddings = embeddings;
    }

    /**
     * 提交一条画像候选（唯一入口）。命中以下任一即拒收：非画像 scope / 创作来源（fiction）/ 有未解除墓碑。
     *
     * @param exec         产生该信息的执行作用域（决定 source_kind：narrative → fiction）
     * @param scope        profile | directive
     * @param evidenceLevel explicit | repeated | inferred
     * @param evidenceRefs 可验证证据（chat_message.id 数组 JSON）；无证据最多 inferred
     * @return 候选 id；被拒收返回 null
     */
    public Long submit(MemoryExec exec, String scope, String title, String body,
                       String evidenceLevel, String evidenceRefs) {
        return submit(exec, scope, title, body, evidenceLevel, evidenceRefs, null);
    }

    public Long submit(MemoryExec exec, String scope, String title, String body,
                       String evidenceLevel, String evidenceRefs, Long validUntil) {
        if (!MemoryVocabulary.PROFILE_SCOPES.contains(scope)) {
            return null;
        }
        boolean narrative = exec == null || exec.narrative()
                || exec.identityOrInferred() == MemoryIdentity.PROJECT_PREF;
        String sourceKind = exec != null && exec.identityOrInferred() == MemoryIdentity.REAL
                ? MemoryVocabulary.SRC_REAL : MemoryVocabulary.SRC_INFERRED;
        if (narrative) {
            /* 创作类项目：不是「关于现实用户」的信息，不进画像候选（memory-v6 §3.6）；
               这是一次「跨域写入被拦」，计入指标（§17.2）——闸门在干活，但要看得到 */
            MemoryMetrics.crossDomainBlocked();
            log.debug("画像候选拒收（创作来源）：{}", body);
            return null;
        }
        String fp = MemoryVocabulary.fingerprint(MemoryVocabulary.OWNER_USER, null, null,
                MemoryVocabulary.DOMAIN_USER_PROFILE, scope, body);
        if (revocations.blocked(fp)) {
            log.debug("画像候选拒收（存在未解除墓碑）：{}", body);
            return null;
        }
        String level = (MemoryEvidence.count(evidenceRefs) == 0)
                ? MemoryVocabulary.EVIDENCE_INFERRED
                : (evidenceLevel == null ? MemoryVocabulary.EVIDENCE_INFERRED : evidenceLevel);
        long id = candidates.upsertPending(fp, exec == null ? null : exec.userId(), scope, title, body,
                sourceKind, level, evidenceRefs, validUntil);
        log.debug("画像候选已受理：id={} level={}", id, level);
        return id;
    }

    /**
     * 审批候选 → 正式画像（单事务语义：INSERT 条目 + 候选置 approved）。
     * 需通过升级闸门：证据等级 ≠ inferred 且来源非创作。
     */
    @org.springframework.transaction.annotation.Transactional
    public long approve(long candidateId) {
        CandidateRow c = candidates.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在：" + candidateId));
        if (!"pending".equals(c.status())) {
            throw new IllegalStateException("候选状态非 pending：" + c.status());
        }
        if (items.authorityConflict("USER", null, c.scope(), c.title(), c.body())) {
            throw new IllegalStateException("已存在同名画像，请明确撤销或更新原画像后再批准");
        }
        if (MemoryVocabulary.EVIDENCE_INFERRED.equals(c.evidenceLevel())
                || !"real".equals(c.sourceKind()) || MemoryEvidence.count(c.evidenceRefs()) == 0
                || (c.validUntil() != null && c.validUntil() <= System.currentTimeMillis())
                || revocations.blocked(c.fingerprint())) {
            throw new IllegalStateException("证据不足（inferred），不可升级为正式画像");
        }
        long itemId = items.insert(
                new MemoryItemStore.Ownership(c.userId(), MemoryVocabulary.OWNER_USER,
                        MemoryVocabulary.DOMAIN_USER_PROFILE, MemoryVocabulary.LAYER_L1,
                        null, null, null, null),
                new MemoryItemStore.Governance(null, c.sourceKind(), c.evidenceLevel(),
                        MemoryVocabulary.RETENTION_LONG,
                        MemoryIdentity.ofSourceKind(c.sourceKind())),
                c.scope(), c.title(), c.body(), 0.9, MemoryItemStore.SOURCE_MANUAL, c.validUntil(), c.evidenceRefs());
        candidates.setStatus(candidateId, "approved");
        embeddings.complementAsync(itemId);
        log.info("画像候选已升级为正式画像：candidate={} item={}", candidateId, itemId);
        return itemId;
    }

    /** 拒绝候选：置 rejected + 写墓碑（同指纹不再自动重建） */
    @org.springframework.transaction.annotation.Transactional
    public int reject(long candidateId, String reason) {
        CandidateRow c = candidates.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("候选不存在：" + candidateId));
        candidates.setStatus(candidateId, "rejected");
        revocations.add(null, c.fingerprint(), reason, false);
        return 1;
    }

    /** 撤销一条正式画像：置 revoked + 写墓碑 */
    @org.springframework.transaction.annotation.Transactional
    public int revoke(long itemId, String reason) {
        MemoryItemStore.ItemRow it = items.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("条目不存在：" + itemId));
        items.setStatus(itemId, MemoryVocabulary.STATUS_REVOKED);
        revocations.add(itemId, it.fingerprint(), reason, false);
        return 1;
    }

    /** 解除对某指纹的封锁（用户允许重新学习） */
    public int releaseRelearn(String fingerprint) {
        return revocations.release(fingerprint);
    }

    public List<CandidateRow> pending() {
        return candidates.listByStatus("pending");
    }

    public List<CandidateRow> list(String status) {
        return candidates.listByStatus(status);
    }

    public Optional<CandidateRow> find(long id) {
        return candidates.findById(id);
    }
}
