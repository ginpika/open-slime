package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryRevocationStore;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 侧的记忆改写/遗忘（memory-v6 §9.1 · 「自己的记忆自己负责」）：
 * {@code update_memory} / {@code forget_memory} 两个工具的执行落点。
 *
 * <p>权限边界（**这是本类存在的理由**）：Agent 只能动**自己有权的条目**——
 * 自己的 L2 私有经验、当前项目的记忆、当前会话的任务记忆；**画像（{@code owner=USER}）一律拒**（I2），
 * 别人的私有记忆与已发布的 L4 知识也拒（改已发布内容等于悄悄改别人看到的东西）。
 *
 * <p>两条硬约束：
 * <ul>
 *   <li><b>定位要唯一</b>：按 {@code match} 定位命中多条时**不猜**，把候选列回去让模型重挑（避免误改）；</li>
 *   <li><b>改不覆盖</b>：更新走乐观锁（version），冲突就如实回「请重读后再改」，不 last-write-wins（I9）。</li>
 * </ul>
 */
@Service
public class MemoryEditService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEditService.class);

    /** 一次改写/遗忘的结果：ok=false 时 message 是给模型的可读理由 */
    public record Outcome(boolean ok, String message, Long itemId) {

        static Outcome fail(String message) {
            return new Outcome(false, message, null);
        }

        static Outcome done(String message, long itemId) {
            return new Outcome(true, message, itemId);
        }
    }

    private final MemoryItemStore items;
    private final MemoryItemRecall recall;
    private final MemoryRevocationStore revocations;
    private final MemoryPublisher publisher;

    public MemoryEditService(MemoryItemStore items, MemoryItemRecall recall,
                             MemoryRevocationStore revocations, MemoryPublisher publisher) {
        this.items = items;
        this.recall = recall;
        this.revocations = revocations;
        this.publisher = publisher;
    }

    // ---------------------------------------------------------------- 改写

    /** 改写一条记忆的标题 / 正文（乐观锁 + 权限判定 + 审计） */
    @org.springframework.transaction.annotation.Transactional
    public Outcome update(MemoryExec exec, Long itemId, String match, String title, String body) {
        if (body == null || body.isBlank()) {
            return Outcome.fail("缺少 new_content：请给出改写后的正文");
        }
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (scope == null) {
            return Outcome.fail("当前执行没有记忆作用域，无法改写（memory-v6 I1）");
        }
        Located located = locate(scope, itemId, match);
        if (located.error() != null) {
            return Outcome.fail(located.error());
        }
        MemoryItemStore.ItemRow it = located.item();
        if (!canEdit(scope, it)) {
            return Outcome.fail("无权改写该条目（只允许改写自己的私有记忆 / 当前项目 / 当前会话的记忆；"
                    + "用户画像与已发布知识不可由 Agent 改写）：item=" + it.id() + " owner=" + it.owner()
                    + " layer=" + it.layer());
        }
        String newTitle = title == null || title.isBlank() ? it.title() : title.trim();
        long version = it.version() == null ? 0L : it.version();
        int n = items.updateContentCas(it.id(), newTitle, body.trim(), version,
                MemoryItemStore.SOURCE_AUTO);
        if (n == 0) {
            return Outcome.fail("这条记忆刚被别的写入改过（版本冲突），请重新读取后再改：item=" + it.id());
        }
        log.debug("Agent 改写记忆：item={} by {}（turn={}）", it.id(), scope.agentOrBuiltin(),
                scope.turnId());
        return Outcome.done("已更新记忆 item=" + it.id() + "（标题：" + (newTitle == null ? "未变" : newTitle)
                + "）", it.id());
    }

    // ---------------------------------------------------------------- 遗忘

    /** 遗忘一条记忆：撤销共享 + 置 revoked + 写墓碑（同指纹不再自动重建） */
    @org.springframework.transaction.annotation.Transactional
    public Outcome forget(MemoryExec exec, Long itemId, String match, String reason) {
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (scope == null) {
            return Outcome.fail("当前执行没有记忆作用域，无法遗忘（memory-v6 I1）");
        }
        Located located = locate(scope, itemId, match);
        if (located.error() != null) {
            return Outcome.fail(located.error());
        }
        MemoryItemStore.ItemRow it = located.item();
        if (!canEdit(scope, it)) {
            return Outcome.fail("无权遗忘该条目（只允许遗忘自己的私有记忆 / 当前项目 / 当前会话的记忆；"
                    + "用户画像只能由用户在记忆页撤销）：item=" + it.id() + " owner=" + it.owner());
        }
        /* 先撤共享：否则「忘了」但它还挂在别人的可见面上 */
        publisher.revokeByItem(it.id());
        items.setStatus(it.id(), MemoryVocabulary.STATUS_REVOKED);
        revocations.add(it.id(), it.fingerprint(),
                reason == null || reason.isBlank() ? "Agent 主动遗忘" : reason.trim(), false);
        log.info("Agent 遗忘记忆：item={} by {}", it.id(), scope.agentOrBuiltin());
        return Outcome.done("已遗忘 item=" + it.id() + "（写墓碑：同内容不会再被自动记住）", it.id());
    }

    // ---------------------------------------------------------------- 权限与定位

    /**
     * 权限判定（可测的硬边界）：Agent 只能动自己的 L2、当前项目的记忆、当前会话的任务记忆。
     * 画像（{@code owner=USER}）一律拒（I2）；已发布的 L4 知识也不许悄悄改。
     */
    public static boolean canEdit(MemoryExec exec, MemoryItemStore.ItemRow it) {
        if (exec == null) {
            return false;
        }
        if (exec.human()) {
            return true;
        }
        String owner = it.owner() == null ? "" : it.owner();
        if (MemoryVocabulary.LAYER_L1.equals(it.layer()) || MemoryVocabulary.LAYER_L4.equals(it.layer())) {
            return false;
        }
        if (MemoryVocabulary.LAYER_L3.equals(it.layer())
                && !eq(exec.sessionId(), it.sessionId())
                && !(it.taskId() != null && it.taskId().equals(exec.taskId()))) {
            return false;
        }
        return switch (owner) {
            case MemoryVocabulary.OWNER_AGENT ->
                    eq(exec.agentOrBuiltin(), it.agentId()) && (it.projectId() == null || eq(exec.projectId(), it.projectId()));
            case MemoryVocabulary.OWNER_PROJECT -> eq(exec.projectId(), it.projectId())
                    && (MemoryVocabulary.LAYER_L3.equals(it.layer())
                        || eq(exec.agentOrBuiltin(), it.agentId()));
            case MemoryVocabulary.OWNER_TASK -> eq(exec.sessionId(), it.sessionId())
                    || (it.taskId() != null && it.taskId().equals(exec.taskId()));
            default -> false;
        };
    }

    private record Located(MemoryItemStore.ItemRow item, String error) {
    }

    /** 定位目标条目：给了 id 就用 id；否则按 match 在当前作用域里匹配（命中多条不猜，让模型重挑） */
    private Located locate(MemoryExec scope, Long itemId, String match) {
        if (itemId != null) {
            return items.findById(itemId)
                    .map(it -> new Located(it, null))
                    .orElseGet(() -> new Located(null, "条目不存在：item=" + itemId));
        }
        if (match == null || match.isBlank()) {
            return new Located(null, "请给出 item_id，或给出 match（要改哪条记忆的一句话描述）");
        }
        List<MemoryItemStore.ItemRow> candidates = byText(scope, match);
        if (candidates.isEmpty()) {
            candidates = bySemantic(scope, match);
        }
        if (candidates.isEmpty()) {
            return new Located(null, "没有找到匹配的记忆：match=\"" + match + "\"（可用 recall_memory 先确认）");
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("匹配到多条记忆，请指明 item_id：");
            for (MemoryItemStore.ItemRow it : candidates.subList(0, Math.min(5, candidates.size()))) {
                sb.append("\n- item=").append(it.id()).append("【").append(it.scope()).append("】")
                        .append(clip(it.title() == null ? it.body() : it.title(), 40));
            }
            return new Located(null, sb.toString());
        }
        return new Located(candidates.get(0), null);
    }

    /** 文本直配（不依赖向量）：标题 / 正文包含 match 即算候选 */
    private List<MemoryItemStore.ItemRow> byText(MemoryExec scope, String match) {
        String needle = match.toLowerCase(Locale.ROOT).trim();
        return items.visible(scope, null, 200).stream()
                .filter(it -> {
                    String title = it.title() == null ? "" : it.title().toLowerCase(Locale.ROOT);
                    String body = it.body() == null ? "" : it.body().toLowerCase(Locale.ROOT);
                    return title.contains(needle) || body.contains(needle);
                })
                .toList();
    }

    /** 语义兜底：向量召回（未配置向量时返回空，交由上一步的文本匹配决定） */
    private List<MemoryItemStore.ItemRow> bySemantic(MemoryExec scope, String match) {
        try {
            return recall.recall(scope, match, null, 3).stream()
                    .filter(h -> h.score() > 0)
                    .map(MemoryItemRecall.Hit::item)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static boolean eq(String a, String b) {
        return a != null && !a.isBlank() && a.equals(b);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
