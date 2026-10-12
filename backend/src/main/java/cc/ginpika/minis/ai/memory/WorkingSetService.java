package cc.ginpika.minis.ai.memory;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 工作集（memory-v6 §13 · 大纲点名的 {@code WorkingSetService}）：**工作状态**的一等公民。
 *
 * <p>与长期记忆的分工（三概念分离，§2.3 法三）：
 * <ul>
 *   <li><b>对话历史</b> = {@code chat_message}（还原过程）；</li>
 *   <li><b>工作状态</b> = 本服务（{@code memory_work}）+ 任务检查点 + 子代理黑板（临时、随会话）；</li>
 *   <li><b>长期记忆</b> = {@code memory_item}（跨会话仍有价值）。</li>
 * </ul>
 *
 * <p>硬规则（§7.3）：
 * <ul>
 *   <li>临时结论**先落工作集**，只有「收尾蒸馏」才升为长期记忆——避免把一段对话里的临时假设直接沉淀；</li>
 *   <li>工作项必须绑定会话或任务（没有绑定的「工作状态」无从检索），TTL 到期不再注入；</li>
 *   <li>蒸馏只读工作集、只写长期记忆，不产事件（防递归，§9.3）。</li>
 * </ul>
 */
@Service
public class WorkingSetService {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetService.class);

    /** 工作项类型（写入侧可扩展；未知类型按 {@code progress} 处理） */
    public static final String KIND_PROGRESS = "progress";
    public static final String KIND_TEMP_CONCLUSION = "temp_conclusion";
    public static final String KIND_TOOL_RESULT = "tool_result";
    public static final String KIND_DECISION = "decision_in_flight";

    private final MemoryWorkStore works;
    private final MemoryIngest ingest;
    private final MemoryProps props;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.ai.memory.store.MemoryItemStore items;

    public WorkingSetService(MemoryWorkStore works, MemoryIngest ingest, MemoryProps props) {
        this.works = works;
        this.ingest = ingest;
        this.props = props;
    }

    /**
     * 记一条工作状态。作用域取执行入口圈定的 {@link MemoryScopeCtx}；没有会话 / 任务绑定时拒绝
     * （工作状态必须可归属，见 memory-v6 §3.3 的 L3 约束）。
     *
     * @return 工作项 id；无作用域或无绑定返回 0（不落库）
     */
    public long record(MemoryExec exec, String kind, String title, String body, String refType, Long refId) {
        return record(exec, kind, title, body, refType, refId, null);
    }

    /** 同 {@link #record(MemoryExec, String, String, String, String, Long)}，但可显式指定到期时刻（人工新增用） */
    public long record(MemoryExec exec, String kind, String title, String body, String refType,
                       Long refId, Long expiresAt) {
        MemoryExec scope = ScopeGuard.resolve(exec);
        if (scope == null || body == null || body.isBlank()) {
            return 0;
        }
        String sessionId = scope.sessionId();
        Long taskId = scope.taskId();
        if ((sessionId == null || sessionId.isBlank()) && taskId == null) {
            log.debug("工作项未落库：作用域没有会话 / 任务绑定（memory-v6 L3 约束）");
            return 0;
        }
        Long expiry = expiresAt != null ? expiresAt
                : (props.getWork().getTtlMs() > 0
                        ? System.currentTimeMillis() + props.getWork().getTtlMs() : null);
        return works.add(sessionId, taskId, scope.agentOrBuiltin(), scope.projectId(),
                kind == null || kind.isBlank() ? KIND_PROGRESS : kind.trim(),
                title == null || title.isBlank() ? null : title.trim(), body.trim(),
                refType, refId, expiry);
    }

    /** 当前工作集：本会话（+ 本任务）未关闭、未过期的工作项；人工作用域看全量 */
    public List<MemoryWorkStore.WorkRow> open(MemoryExec exec, int limit) {
        MemoryExec scope = ScopeGuard.resolve(exec);
        long now = System.currentTimeMillis();
        int cap = Math.max(1, Math.min(limit, 200));
        if (scope == null) {
            return List.of();
        }
        if (scope.human()) {
            return works.openAll(now, cap);
        }
        List<MemoryWorkStore.WorkRow> out = new java.util.ArrayList<>();
        if (scope.sessionId() != null && !scope.sessionId().isBlank()) {
            out.addAll(works.openBySession(scope.sessionId(), now, cap));
        }
        if (scope.taskId() != null) {
            for (MemoryWorkStore.WorkRow row : works.openByTask(scope.taskId(), now, cap)) {
                boolean dup = out.stream().anyMatch(r -> r.id() == row.id());
                if (!dup) {
                    out.add(row);
                }
            }
        }
        return out.stream().filter(w -> java.util.Objects.equals(scope.projectId(), w.projectId()))
                .limit(cap).toList();
    }

    /** 关闭一条工作项（人工 / 流程收尾） */
    public int close(long id) {
        return works.setStatus(id, "closed");
    }

    public List<MemoryWorkStore.WorkRow> bySession(String sessionId, int limit) {
        return works.bySession(sessionId, Math.max(1, limit));
    }

    public int countOpen() {
        return works.countOpen(System.currentTimeMillis());
    }

    /**
     * 收尾蒸馏（memory-v6 §9.3 · L3 → L2）：把当前工作集合并成一段文本交提炼，然后标记
     * {@code distilled} 并归档——「临时的」到此为止，「值得留的」才进长期记忆。
     *
     * @return 被蒸馏的工作项数
     */
    public int distill(MemoryExec exec) {
        return distill(exec, Long.MAX_VALUE);
    }

    /**
     * 收尾蒸馏（可限定截止时刻）：只整理 {@code createdAt < createdBeforeMs} 的工作项——
     * 由轮次调用方传入「本轮开始时刻」，于是**本轮刚记的临时结论不会被立刻蒸馏掉**，
     * 真正收尾的是更早那些已经沉淀过的工作状态。
     *
     * @return 被蒸馏的工作项数
     */
    public int distill(MemoryExec exec, long createdBeforeMs) {
        if (exec == null || (exec.sessionId() == null && exec.taskId() == null)) {
            throw new IllegalArgumentException("收尾必须指定会话或任务");
        }
        long completedBefore = Math.min(createdBeforeMs, System.currentTimeMillis());
        List<MemoryWorkStore.WorkRow> open = open(exec, 100).stream()
                .filter(w -> w.createdAt() < createdBeforeMs)
                .toList();
        if (open.isEmpty()) {
            if (items != null) { items.archiveTask(exec, completedBefore); }
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryWorkStore.WorkRow w : open) {
            sb.append("- [").append(w.kind()).append("] ")
                    .append(w.title() == null ? "" : w.title() + "：")
                    .append(w.body() == null ? "" : w.body().replaceAll("\\s+", " ")).append('\n');
        }
        List<MemoryWorkStore.WorkRow> claimed = open.stream()
                .filter(w -> works.transition(w.id(), "open", "distilling") == 1).toList();
        if (claimed.isEmpty()) { return 0; }
        String refs = "[]";
        for (var w : claimed) { refs = MemoryEvidence.merge(refs, MemoryEvidence.ref("memory_work", w.id(), w.body())); }
        // 必须确认提炼成功才关闭；失败或模型未配置时恢复 open，保留重试机会。
        try {
            ingest.distill(exec, sb.toString(), refs).whenComplete((ok, error) -> {
                for (var w : claimed) {
                    works.transition(w.id(), "distilling", error == null && Boolean.TRUE.equals(ok) ? "distilled" : "open");
                }
                if (error == null && Boolean.TRUE.equals(ok) && items != null) {
                    items.archiveTask(exec, completedBefore);
                }
            });
        } catch (RuntimeException e) {
            claimed.forEach(w -> works.transition(w.id(), "distilling", "open"));
            throw e;
        }
        return claimed.size();
    }

    /**
     * 工作集摘要（供任务检查点持久化，memory-v6 §4.4 / D11）：调用方（agent 侧）把它写进
     * {@code task.memory_checkpoint}——本类不依赖 agent 包，故只产出文本，不落 task 表。
     */
    public String checkpointDigest(MemoryExec exec) {
        List<MemoryWorkStore.WorkRow> open = open(exec, 20);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sessionId\":\"").append(nz(exec == null ? null : exec.sessionId()))
                .append("\",\"turnId\":\"").append(nz(exec == null ? null : exec.turnId()))
                .append("\",\"updatedAt\":").append(System.currentTimeMillis()).append(",\"work\":[");
        boolean first = true;
        for (MemoryWorkStore.WorkRow w : open) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"kind\":\"").append(escape(w.kind())).append("\",\"title\":\"")
                    .append(escape(w.title())).append("\",\"body\":\"")
                    .append(escape(clip(w.body(), 200))).append("\"}");
        }
        return sb.append("]}").toString();
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
