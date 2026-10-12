package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作状态恢复（memory-v6 §9.2 · P5 / D11 的读取侧）：把任务检查点里的「上次做到哪一步」还原成工作集条目，
 * 让下一次执行真的**接得上**，而不只是库里躺着一段 JSON。
 *
 * <p>为什么放在 {@code agent.runtime}：写检查点的是 agent 侧（{@code task.memory_checkpoint}），
 * 而 {@code ai.memory} 不得依赖 agent 包（分层红线）；这里做「读 task 表 → 调 ai.memory 的写接口」的桥。
 *
 * <p>幂等：同一任务已有「{@link #RESTORE_TITLE}」工作项时跳过——重启不会把同一份检查点还原两遍。
 * 只在**任务中断**（interrupted）且有检查点时才恢复；正常结束的任务不需要「接着做」。
 */
@Component
public class WorkingSetRestorer {

    private static final Logger log = LoggerFactory.getLogger(WorkingSetRestorer.class);

    /** 恢复出来的工作项标题（幂等判据） */
    public static final String RESTORE_TITLE = "从检查点恢复";

    private final TaskStore tasks;
    private final WorkingSetService workingSet;
    private final MemoryWorkStore works;
    private final ObjectMapper om;

    public WorkingSetRestorer(TaskStore tasks, WorkingSetService workingSet, MemoryWorkStore works,
                              ObjectMapper om) {
        this.tasks = tasks;
        this.workingSet = workingSet;
        this.works = works;
        this.om = om;
    }

    /** 启动恢复：把上次遗留、带检查点的中断任务逐一还原，返回还原出的工作项数 */
    public int restoreInterrupted() {
        List<Long> restorable = tasks.idsWithCheckpoint(TaskStore.ST_INTERRUPTED);
        int restored = 0;
        for (Long taskId : restorable) {
            restored += restore(taskId);
        }
        if (restored > 0) {
            log.info("启动恢复：{} 条中断任务的工作状态已还原到工作集", restored);
        }
        return restored;
    }

    /**
     * 还原单个任务的工作状态（启动自动恢复与人工恢复共用）。
     *
     * @return 还原出的工作项数；无检查点 / 已还原过 / 检查点为空则 0
     */
    public int restore(long taskId) {
        if (works.existsByTaskAndTitle(taskId, RESTORE_TITLE)) {
            return 0;
        }
        TaskStore.TaskRow task = tasks.task(taskId).orElse(null);
        String checkpoint = tasks.checkpointOf(taskId);
        if (task == null || checkpoint == null || checkpoint.isBlank()) {
            return 0;
        }
        String body = summary(checkpoint);
        MemoryExec exec = new MemoryExec(null, task.agentId(), null, task.sessionId(), taskId,
                MemorySurface.SYSTEM, false, null, MemoryIdentity.REAL);
        long id = workingSet.record(exec, WorkingSetService.KIND_PROGRESS, RESTORE_TITLE,
                body.isBlank() ? "上次执行被中断（检查点无工作集内容）" : body, "checkpoint", null);
        return id > 0 ? 1 : 0;
    }

    /** 把检查点 JSON 里的工作集压成一段可读文本（解析失败则退化为原文截断） */
    private String summary(String checkpoint) {
        try {
            JsonNode root = om.readTree(checkpoint);
            JsonNode work = root.path("work");
            StringBuilder sb = new StringBuilder("上次中断时的进度：");
            boolean any = false;
            for (JsonNode w : work) {
                any = true;
                sb.append("\n- [").append(w.path("kind").asText("work")).append("] ")
                        .append(w.path("title").asText(""))
                        .append(w.path("body").asText(""));
            }
            return any ? sb.toString() : "";
        } catch (Exception e) {
            return checkpoint.length() <= 200 ? checkpoint : checkpoint.substring(0, 200) + "…";
        }
    }
}
