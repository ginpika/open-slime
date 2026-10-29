package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemorySnapshotStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.stats.TokenUsageService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 快照构建器（memory-v6 §4.3 / §5 · 大纲点名的 {@code MemorySnapshotBuilder}）：把「本轮该看见什么」
 * 变成一份只读切片——长期记忆条目（按层分桶）+ 本会话工作集（L3）。
 *
 * <p>顺序即标准：**先作用域收窄 → 再相关性打分 → 再 token 预算填充**（§6.1 按需读取）。
 * <ul>
 *   <li>受保护条目（pinned / 正式画像 / `open_issue` / 全局规则 `directive`）**优先注入但受总预算约束**——
 *       这正是「全局规则实时生效」的机制：每轮重新读、恒定注入（I10、T24）；</li>
 *   <li>预算**按层分桶**（L1 共享 / L2 私有 / L3 任务 / L4 发布 / episode），替代 v5 的按 scope 分桶；</li>
 *   <li>工作集走 L3 的 task 桶——工作状态与长期记忆分表，但在同一份快照里呈现；</li>
 *   <li>命中结果落 {@code memory_snapshot} 引用，可回放「这一轮模型看见了什么」（I12、T17）。</li>
 * </ul>
 *
 * <p>召回失败**不抛错**：降级为「本轮不注入记忆」，绝不打断对话主流程。
 */
@Component
public class MemorySnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(MemorySnapshotBuilder.class);

    private final MemoryItemRecall recall;
    private final MemorySnapshotStore snapshots;
    private final WorkingSetService workingSet;
    private final MemoryProps props;
    private cc.ginpika.minis.project.ProjectProgressStore progress;
    @org.springframework.beans.factory.annotation.Autowired
    public void setProgress(cc.ginpika.minis.project.ProjectProgressStore progress) { this.progress=progress; }

    public MemorySnapshotBuilder(MemoryItemRecall recall, MemorySnapshotStore snapshots,
                                 WorkingSetService workingSet, MemoryProps props) {
        this.recall = recall;
        this.snapshots = snapshots;
        this.workingSet = workingSet;
        this.props = props;
    }

    /**
     * 构建本轮快照。
     *
     * @param exec   本轮作用域（null → 退化为「只读正式画像」）
     * @param query  本轮输入（相关性依据；空则只出受保护条目 + 工作集）
     * @param turnId 本轮唯一 id（回放键；空则只构建不落回放记录）
     */
    public MemorySnapshot build(MemoryExec exec, String query, String turnId) {
        MemoryExec scope = ScopeGuard.resolveRead(exec);
        long watermark = safeWatermark();
        MemoryMetrics.snapshotBuilt();
        Budgets budgets = new Budgets(props.getBudget());
        List<MemoryItemStore.ItemRow> items = items(scope, query, budgets);
        List<MemoryWorkStore.WorkRow> work = work(scope, budgets);
        var continuation=continuation(scope);
        MemorySnapshot snapshot = new MemorySnapshot(turnId, scope, watermark, items, work,
                System.currentTimeMillis(), props.getSnapshot().getTtlMs(),continuation);
        if (props.getSnapshot().isReplay() && turnId != null && !turnId.isBlank()) {
            try {
                snapshots.save(snapshot, budgets.used()+continuation.stream().mapToInt(p->tokens(cc.ginpika.minis.project.ProjectProgressStore.render(p))).sum());
                MemoryMetrics.snapshotSaved();
            } catch (RuntimeException e) {
                /* 回放是审计能力，写不进去不能影响注入 */
                log.warn("快照回放记录写入失败（忽略）：{}", e.getMessage());
            }
        }
        return snapshot;
    }

    private List<cc.ginpika.minis.project.ProjectProgressStore.Progress> continuation(MemoryExec scope) {
        if (progress==null || !props.isEnabled() || props.getContinuationTokens()<=0) { return List.of(); }
        List<cc.ginpika.minis.project.ProjectProgressStore.Progress> result=new ArrayList<>();
        int remaining=props.getContinuationTokens();
        for (var p : progress.visible(scope)) {
            String body=p.body();
            var kept=p;
            while (tokens(cc.ginpika.minis.project.ProjectProgressStore.render(kept))>remaining && body.length()>160) {
                int length=Math.max(100,body.length()*3/4);
                body=body.substring(0,Math.min(100,length/3))+"\n[…续接摘录省略…]\n"+body.substring(body.length()-(length-Math.min(100,length/3)));
                kept=new cc.ginpika.minis.project.ProjectProgressStore.Progress(p.sourceSessionId(),p.title(),p.projectId(),
                        p.agentId(),p.messageIds(),body,p.observedAt());
            }
            int used=tokens(cc.ginpika.minis.project.ProjectProgressStore.render(kept));
            if (used>remaining) { break; }
            result.add(kept); remaining-=used;
        }
        return result;
    }

    // ---------------------------------------------------------------- 长期记忆条目

    private List<MemoryItemStore.ItemRow> items(MemoryExec scope, String query, Budgets b) {
        if (query == null || query.isBlank()) {
            /* 无查询（心跳初始拍 / 无议题圆桌）：不做相关性排序，只注入「恒定注入集」——
               全局规则、正式画像、pinned、未完成事项 */
            List<MemoryItemStore.ItemRow> always = recall.visible(scope,
                            Math.max(1, props.getSnapshot().getCandidateLimit())).stream()
                    .filter(it -> MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(),
                            it.retention()))
                    .toList();
            return fill(always.stream().map(it -> new MemoryItemRecall.Hit(it, 0d)).toList(), b);
        }
        List<MemoryItemRecall.Hit> hits = List.of();
        try {
            hits = recall.recall(scope, query, null,
                    Math.max(1, props.getSnapshot().getCandidateLimit()));
        } catch (RuntimeException e) {
            log.warn("记忆召回失败（本轮不注入）：{}", e.getMessage());
        }
        return fill(hits, b);
    }

    /** token 预算内贪心填充（按层分桶）：受保护条目优先但受总预算约束，单条超长先截断 */
    private List<MemoryItemStore.ItemRow> fill(List<MemoryItemRecall.Hit> hits, Budgets b) {
        int maxChars = Math.max(120, props.getBodyMaxChars());
        List<MemoryItemStore.ItemRow> kept = new ArrayList<>();
        int dropped = 0;
        var ordered=new ArrayList<>(hits);
        ordered.sort(java.util.Comparator.comparingInt((MemoryItemRecall.Hit h)->"directive".equals(h.item().scope())?0:h.item().pinned()?1:"USER".equals(h.item().owner())?2:3)
                .thenComparing(java.util.Comparator.comparingDouble(MemoryItemRecall.Hit::score).reversed()));
        for (MemoryItemRecall.Hit h : ordered) {
            MemoryItemStore.ItemRow it = h.item();
            int tokens = tokens(MemoryItemRecall.oneLine(it, maxChars));
            if (MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(), it.retention())) {
                if(b.canSpend(tokens)) { kept.add(it); b.spend(tokens, false); }
                else dropped++;
                continue;
            }
            if (!b.canSpend(tokens, bucketOf(it))) {
                dropped++;
                continue;
            }
            b.spend(tokens, false);
            b.spendBucket(tokens, bucketOf(it));
            kept.add(it);
        }
        MemoryMetrics.budgetEvicted(dropped);
        return kept;
    }

    /** 条目归桶（memory-v6 §8.3）：按层——L1 共享 / L2 私有 / L3 任务 / L4 发布；episode scope 单列一桶 */
    private static String bucketOf(MemoryItemStore.ItemRow it) {
        if (MemoryVocabulary.SCOPE_EPISODE.equals(it.scope())
                || MemoryVocabulary.SCOPE_CHECKPOINT.equals(it.scope())) {
            return "episode";
        }
        return switch (it.layer() == null ? "" : it.layer()) {
            case "L1" -> "shared";
            case "L3" -> "task";
            case "L4" -> "publish";
            default -> "agent";
        };
    }

    // ---------------------------------------------------------------- 工作集（L3）

    private List<MemoryWorkStore.WorkRow> work(MemoryExec scope, Budgets b) {
        List<MemoryWorkStore.WorkRow> open = workingSet.open(scope, 50);
        if (open.isEmpty()) {
            return List.of();
        }
        int maxChars = Math.max(120, props.getBodyMaxChars());
        List<MemoryWorkStore.WorkRow> kept = new ArrayList<>();
        int dropped = 0;
        for (MemoryWorkStore.WorkRow w : open) {
            int tokens = tokens(workLine(w, maxChars));
            if (!b.canSpend(tokens) || !b.canSpend(tokens, "task")) {
                dropped++;
                continue;
            }
            b.spend(tokens, false);
            b.spendBucket(tokens, "task");
            kept.add(w);
        }
        MemoryMetrics.budgetEvicted(dropped);
        return kept;
    }

    /** 工作项渲染成一行（注入与 token 估算共用；注入侧也用它，故为 public） */
    public static String workLine(MemoryWorkStore.WorkRow w, int maxChars) {
        String body = w.body() == null ? "" : w.body().trim();
        if (maxChars > 0 && body.length() > maxChars) {
            body = body.substring(0, maxChars) + "…";
        }
        return "- 【" + kindLabel(w.kind()) + "】"
                + (w.title() == null || w.title().isBlank() ? "" : w.title().trim() + "：") + body;
    }

    /** 工作项类型 → 人话标签 */
    static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case WorkingSetService.KIND_PROGRESS -> "进度";
            case WorkingSetService.KIND_TEMP_CONCLUSION -> "临时结论";
            case WorkingSetService.KIND_TOOL_RESULT -> "工具结果";
            case WorkingSetService.KIND_DECISION -> "在途决策";
            default -> kind == null || kind.isBlank() ? "工作" : kind;
        };
    }

    // ---------------------------------------------------------------- 预算与工具

    /** 按层分桶的剩余额度（memory-v6 §8.3） */
    private static final class Budgets {
        private int total;
        private int shared;
        private int agent;
        private int task;
        private int publish;
        private int episode;
        private final int startTotal;

        Budgets(MemoryProps.Budget b) {
            this.total = b.getTotal();
            this.shared = b.getShared();
            this.agent = b.getAgent();
            this.task = b.getTask();
            this.publish = b.getPublish();
            this.episode = b.getEpisode();
            this.startTotal = b.getTotal();
        }

        boolean canSpend(int tokens) {
            return tokens <= total;
        }

        boolean canSpend(int tokens, String bucket) {
            return tokens <= total && tokens <= bucketLeft(bucket);
        }

        void spend(int tokens, boolean exempt) {
            if (!exempt) {
                total -= tokens;
            }
        }

        void spendBucket(int tokens, String bucket) {
            switch (bucket) {
                case "shared" -> shared -= tokens;
                case "task" -> task -= tokens;
                case "publish" -> publish -= tokens;
                case "episode" -> episode -= tokens;
                default -> agent -= tokens;
            }
        }

        int bucketLeft(String bucket) {
            return switch (bucket) {
                case "shared" -> shared;
                case "task" -> task;
                case "publish" -> publish;
                case "episode" -> episode;
                default -> agent;
            };
        }

        int used() {
            return startTotal - total;
        }
    }

    private static int tokens(String text) {
        return (int) TokenUsageService.estimateTokens(text);
    }

    private long safeWatermark() {
        try {
            return snapshots.watermark();
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
