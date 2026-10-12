package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.store.MemoryHandoffStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryPromotionStore;
import cc.ginpika.minis.ai.memory.store.MemoryShareStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布与交接（memory-v6 §9.2 / §9.3 · 大纲点名的 {@code MemoryPublisher}）：L4 的唯一写入门面。
 *
 * <p>把三张表收口成一组语义动作：
 * <ul>
 *   <li>{@link #publish} —— 授权共享（{@code memory_share}）：读侧 R5 据此发现，撤销即不可见；</li>
 *   <li>{@link #handoff} —— 交接（{@code memory_handoff}）：{@code offered → accepted → done}，
 *       接收方在收件箱按需发现（**不推送**，避免通知风暴）；</li>
 *   <li>{@link #promote} / {@link #applyPromotion} —— 提升（{@code memory_promotion}）：
 *       L2→L4 / L4→L1 必须留凭据（不变量 I6 的另一半）。</li>
 * </ul>
 *
 * <p>发布是**显式动作**：Agent 主动调用（工具 {@code publish_knowledge}）或人工在管理页操作；
 * 自动流程（提炼 / 巩固）不得自行发布（I7）。
 */
@Service
public class MemoryPublisher {

    private static final Logger log = LoggerFactory.getLogger(MemoryPublisher.class);

    /** 合法的接收方类型 */
    private static final Set<String> TO_KINDS = Set.of("user", "project", "agent", "session");

    /** 收件箱一条：交接给我的条目 + 交接状态 */
    public record InboxEntry(long handoffId, String toKind, String toRef, String state, long itemId,
                            String title, String body, String layer, String note, long createdAt) {
    }

    private final MemoryShareStore shares;
    private final MemoryHandoffStore handoffs;
    private final MemoryPromotionStore promotions;
    private final MemoryItemStore items;

    public MemoryPublisher(MemoryShareStore shares, MemoryHandoffStore handoffs,
                           MemoryPromotionStore promotions, MemoryItemStore items) {
        this.shares = shares;
        this.handoffs = handoffs;
        this.promotions = promotions;
        this.items = items;
    }

    // ---------------------------------------------------------------- 发布 / 撤销

    /** 授权共享：把条目发布给指定接收方；返回共享行 id */
    @Transactional
    public long publish(long itemId, String toKind, String toRef, String note) {
        requireKind(toKind);
        if (!"user".equals(toKind) && (toRef == null || toRef.isBlank())) {
            throw new IllegalArgumentException("发布目标必须有标识");
        }
        MemoryItemStore.ItemRow it = items.findById(itemId).orElseThrow();
        MemoryExec caller = cc.ginpika.minis.ai.core.MemoryScopeCtx.get();
        if (caller != null && !caller.human() && !MemoryEditService.canEdit(caller, it)
                && !("L4".equals(it.layer()) && caller.agentOrBuiltin().equals(it.producerAgent()))) {
            throw new IllegalArgumentException("无权发布别人的记忆");
        }
        if (!"active".equals(it.status()) || it.supersededBy() != null
                || (it.validUntil() != null && it.validUntil() <= System.currentTimeMillis())) {
            throw new IllegalStateException("不可发布已失效条目");
        }
        long id = shares.publish(itemId, it.owner(), it.agentId(), it.projectId(), toKind,
                blankToNull(toRef), note);
        items.recordControl(itemId, "publish", toKind + ":" + toRef);
        return id;
    }

    /** 撤销一条共享（读侧 R5 立即不再命中） */
    @Transactional
    public int revoke(long shareId) {
        var share = shares.findById(shareId).orElseThrow();
        int changed = shares.revoke(shareId);
        items.recordControl(share.itemId(), "unpublish", Long.toString(shareId));
        return changed;
    }

    /** 撤销某条目的全部共享（条目被删除时连带清理） */
    @Transactional
    public int revokeByItem(long itemId) {
        int changed = shares.revokeByItem(itemId);
        if (changed > 0) { items.recordControl(itemId, "unpublish", "全部共享撤销"); }
        return changed;
    }

    public List<MemoryShareStore.ShareRow> published() {
        return shares.listPublished();
    }

    public List<MemoryShareStore.ShareRow> sharesOf(long itemId) {
        return shares.byItem(itemId);
    }

    // ---------------------------------------------------------------- 交接

    /** 交接：发布给接收方（可见性）＋ 记一条 {@code offered} 交接（状态机） */
    @Transactional
    public long[] handoff(long itemId, String toKind, String toRef, String note) {
        requireKind(toKind);
        requireItem(itemId);
        long shareId = publish(itemId, toKind, toRef, note);
        long handoffId = handoffs.offer(itemId, toKind, blankToNull(toRef), note);
        log.info("记忆交接已发出：item={} → {}:{}（handoff={}）", itemId, toKind, toRef, handoffId);
        return new long[]{shareId, handoffId};
    }

    /** 交接状态机：accepted / rejected / done */
    public int move(long handoffId, String state) {
        MemoryHandoffStore.HandoffRow row = handoffs.findById(handoffId)
                .orElseThrow(() -> new IllegalArgumentException("交接不存在：" + handoffId));
        if (!Set.of("accepted", "rejected", "done").contains(state)) {
            throw new IllegalArgumentException("非法交接状态：" + state);
        }
        if ("rejected".equals(row.state()) || "done".equals(row.state())) {
            throw new IllegalStateException("交接已终结（" + row.state() + "）：" + handoffId);
        }
        if ("done".equals(state) && !"accepted".equals(row.state())) {
            throw new IllegalStateException("交接须先 accepted 再 done");
        }
        int changed = handoffs.transition(handoffId, row.state(), state);
        if (changed == 0) { throw new IllegalStateException("交接状态已变化，请重新读取"); }
        return changed;
    }

    /**
     * 收件箱（memory-v6 §9.2 · 拉取发现，不推送）：当前执行作用域能收到的 {@code offered} 交接。
     * 目标匹配 = agent（我）/ session（本会话）/ project（本项目）。
     */
    public List<InboxEntry> inbox(MemoryExec exec, int limit) {
        return inbox(exec, limit, "offered");
    }

    public List<InboxEntry> inbox(MemoryExec exec, int limit, String state) {
        MemoryExec e = exec == null ? MemoryExec.none() : exec;
        int cap = Math.max(1, Math.min(limit, 100));
        List<MemoryHandoffStore.HandoffRow> rows = new ArrayList<>();
        if (e.human()) {
            /* 管理页：人工作用域看全量待接收（不受 agent/session/project 绑定限制） */
            rows.addAll(handoffs.listByState(state, cap));
        } else {
            rows.addAll(handoffs.byTarget("agent", e.agentOrBuiltin(), state, cap));
            rows.addAll(handoffs.byTarget("user", null, state, cap));
            if (e.sessionId() != null && !e.sessionId().isBlank()) {
                rows.addAll(handoffs.byTarget("session", e.sessionId(), state, cap));
            }
            if (e.projectId() != null && !e.projectId().isBlank()) {
                rows.addAll(handoffs.byTarget("project", e.projectId(), state, cap));
            }
        }
        List<InboxEntry> out = new ArrayList<>(rows.size());
        for (MemoryHandoffStore.HandoffRow h : rows) {
            MemoryItemStore.ItemRow it = items.findById(h.itemId()).orElse(null);
            if (it == null || !"active".equals(it.status()) || it.supersededBy() != null
                    || (it.validUntil() != null && it.validUntil() <= System.currentTimeMillis())
                    || (it.expiresSoft() != null && it.expiresSoft() <= System.currentTimeMillis())
                    || shares.byItem(h.itemId()).stream().noneMatch(s -> "published".equals(s.status())
                        && h.toKind().equals(s.toKind()) && java.util.Objects.equals(h.toRef(), s.toRef()))) {
                continue;
            }
            out.add(new InboxEntry(h.id(), h.toKind(), h.toRef(), h.state(), it.id(), it.title(),
                    it.body(), it.layer(), h.note(), h.createdAt()));
        }
        return out.stream().distinct().sorted(java.util.Comparator.comparingLong(InboxEntry::createdAt).reversed())
                .limit(cap).toList();
    }

    @Transactional
    public int move(MemoryExec exec, long handoffId, String state) {
        MemoryExec e = exec == null ? MemoryExec.none() : exec;
        var h = handoffs.findById(handoffId).orElseThrow();
        var it = items.visibleById(e, h.itemId()).orElse(null);
        boolean target = e.human() || switch (h.toKind()) {
            case "agent" -> e.agentOrBuiltin().equals(h.toRef());
            case "project" -> e.projectId() != null && e.projectId().equals(h.toRef());
            case "session" -> e.sessionId() != null && e.sessionId().equals(h.toRef());
            case "user" -> true;
            default -> false;
        };
        boolean authorized = target && it != null && "active".equals(it.status())
                && shares.byItem(h.itemId()).stream().anyMatch(s -> "published".equals(s.status())
                    && h.toKind().equals(s.toKind()) && java.util.Objects.equals(h.toRef(), s.toRef()));
        if (!authorized) { throw new IllegalArgumentException("交接已撤销、失效或不属于当前接收方"); }
        return move(handoffId, state);
    }

    @Transactional
    public int confirmProject(long itemId, long version) {
        var caller = cc.ginpika.minis.ai.core.MemoryScopeCtx.get();
        if (caller != null && !caller.human()) { throw new IllegalArgumentException("项目共享权威必须由用户确认"); }
        var it = requireItem(itemId);
        if (!"PROJECT".equals(it.owner()) || !"L3".equals(it.layer()) || !"active".equals(it.status())
                || it.version() == null || it.version() != version
                || (it.validUntil() != null && it.validUntil() <= System.currentTimeMillis())
                || MemoryEvidence.count(it.sourceRefs()) == 0) {
            throw new IllegalStateException("确认须为当前版本的项目任务条目，并具有来源证据");
        }
        if (items.authorityConflict("PROJECT", it.projectId(), it.scope(), it.title(), it.body())) {
            throw new IllegalStateException("已存在同名项目事实，请以版本编辑原条目或撤销后再确认");
        }
        return items.confirmProject(itemId, version);
    }

    @Transactional
    public long[] publishNew(MemoryExec exec, String content, String title, String toKind,
                             String toRef, String note, boolean handoff) {
        String owner = exec.projectId() == null ? "AGENT" : "PROJECT";
        String domain = owner.equals("AGENT") ? "AGENT" : "PROJECT";
        String agent = exec.agentOrBuiltin();
        String fp = MemoryVocabulary.fingerprint(owner, exec.projectId(), agent, domain, "knowledge", content);
        if (items.relearnBlocked(fp)) { throw new IllegalStateException("用户已要求遗忘该内容"); }
        Long id = items.findByFingerprint(fp).map(MemoryItemStore.ItemRow::id).orElse(null);
        if (id == null) {
            id = items.insert(new MemoryItemStore.Ownership(exec.userId(), owner, domain, "L4",
                    exec.projectId(), exec.sessionId(), exec.taskId(), agent),
                    new MemoryItemStore.Governance(agent, exec.narrative() ? "fiction" : "inferred",
                            "inferred", "long", exec.identityOrInferred()), "knowledge", title, content,
                    0.8, "auto", null, null);
        }
        long itemId = id;
        return cc.ginpika.minis.ai.core.MemoryScopeCtx.run(exec, () -> handoff
                ? handoff(itemId, toKind, toRef, note) : new long[]{publish(itemId, toKind, toRef, note)});
    }

    public List<MemoryHandoffStore.HandoffRow> handoffsOf(long itemId) {
        return handoffs.byItem(itemId);
    }

    // ---------------------------------------------------------------- 提升

    /** 记一条提升候选（L2→L4 / L4→L1）；候选不改条目，等 {@link #applyPromotion} 落地 */
    public long promote(long itemId, String toLayer, int evidenceCount, String note) {
        MemoryItemStore.ItemRow it = requireItem(itemId);
        if (!"L4".equals(toLayer) && !"L1".equals(toLayer)) {
            throw new IllegalArgumentException("提升目标层只支持 L4 或 L1：" + toLayer);
        }
        if (toLayer.equals(it.layer())) {
            throw new IllegalStateException("条目已是 " + toLayer + "，无需提升：" + itemId);
        }
        return promotions.add(itemId, it.layer(), toLayer, "manual", evidenceCount, note);
    }

    /** 落地提升：改条目 layer 并记来源凭据（promoted_from）——L4 从此有可追溯来源 */
    @Transactional
    public int applyPromotion(long promotionId) {
        MemoryPromotionStore.PromotionRow row = promotions.findById(promotionId)
                .orElseThrow(() -> new IllegalArgumentException("提升记录不存在：" + promotionId));
        if (!"candidate".equals(row.state())) {
            throw new IllegalStateException("提升记录状态非 candidate：" + row.state());
        }
        MemoryItemStore.ItemRow item = requireItem(row.itemId());
        if (!"active".equals(item.status()) || !row.fromLayer().equals(item.layer())) {
            throw new IllegalStateException("条目状态或来源层已变化，请重新申请提升");
        }
        if (!("L2".equals(item.layer()) && "L4".equals(row.toLayer()))
                && !("L4".equals(item.layer()) && "L1".equals(row.toLayer()))) {
            throw new IllegalArgumentException("只支持 L2→L4 或 L4→L1");
        }
        if ("L1".equals(row.toLayer()) && (!"PROJECT".equals(item.owner())
                || item.sourceRefs() == null || item.sourceRefs().isBlank()
                || "inferred".equals(item.evidenceLevel()))) {
            throw new IllegalStateException("共享权威须为项目记忆且具备可核验证据");
        }
        int changed = items.promoteLayer(row.itemId(), row.toLayer(), promotionId);
        if (changed > 0) {
            promotions.setState(promotionId, "applied", true);
            log.info("记忆提升已落地：item={} {} → {}", row.itemId(), row.fromLayer(), row.toLayer());
        }
        return changed;
    }

    public List<MemoryPromotionStore.PromotionRow> promotionCandidates(int limit) {
        return promotions.listByState("candidate", limit);
    }

    // ---------------------------------------------------------------- 内部

    private MemoryItemStore.ItemRow requireItem(long itemId) {
        return items.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("条目不存在：" + itemId));
    }

    private static void requireKind(String toKind) {
        if (toKind == null || !TO_KINDS.contains(toKind.trim())) {
            throw new IllegalArgumentException("非法接收方类型（user|project|agent|session）：" + toKind);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
