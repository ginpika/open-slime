package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import java.util.List;

/**
 * 记忆快照（memory-v6 §4.3）：**一次执行所使用的上下文视图**——本轮命中的条目 + 本会话工作集 +
 * 生成时刻 + 水位。
 *
 * <p>它不是「把记忆复制进 Agent」：只是这一轮注入用的一份只读切片；轮内写入不回改它（见 {@link MemoryStage}），
 * 下一轮重新构建（§4.4 一致性策略）。带上 {@code turnId} 与 {@code watermark} 后，可以回答
 * 「这一轮模型到底看见了什么」——{@link cc.ginpika.minis.ai.memory.store.MemorySnapshotStore}
 * 同时保存版本、正文与工作集用于回放。
 *
 * <p>{@code work} 是**工作状态**（L3，{@code memory_work}）：与长期记忆物理分离（三概念分离），
 * 但在注入时一并呈现——模型既要知道「长期记得什么」，也要知道「这次做到哪一步」。
 *
 * @param turnId    本轮唯一 id（回放键；无轮次概念时为空）
 * @param exec      本轮执行作用域
 * @param watermark 构建时刻的变更水位（memory_revision 的 max(id)）
 * @param items     预算内选中的长期记忆条目（按注入顺序）
 * @param work      预算内选中的工作集条目（L3，可为空）
 * @param builtAt   构建时刻（毫秒）
 * @param ttlMs     有效期（毫秒；≤0 = 不过期）
 */
public record MemorySnapshot(String turnId, MemoryExec exec, long watermark,
                             List<MemoryItemStore.ItemRow> items, List<MemoryWorkStore.WorkRow> work,
                             long builtAt, long ttlMs) {

    /** 快照在 {@code now} 此刻是否仍有效 */
    public boolean fresh(long now) {
        return ttlMs <= 0 || now - builtAt < ttlMs;
    }

    /** 回放用：本轮命中条目的 id（顺序即注入顺序） */
    public List<Long> itemIds() {
        return items.stream().map(MemoryItemStore.ItemRow::id).toList();
    }

    /** 回放用：本轮命中工作项的 id */
    public List<Long> workIds() {
        return work == null ? List.of() : work.stream().map(MemoryWorkStore.WorkRow::id).toList();
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return (items == null || items.isEmpty()) && (work == null || work.isEmpty());
    }
}
