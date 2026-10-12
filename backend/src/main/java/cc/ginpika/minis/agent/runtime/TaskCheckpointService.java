package cc.ginpika.minis.agent.runtime;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 任务记忆检查点服务（memory-v5 §9 · P5）：把「当前工作到哪一步」落成 task 级快照，供暂停 / 中断 / 重启后恢复。
 *
 * <p>与长期记忆的分工（memory-v5 §2.3 三概念分离）：检查点是 <b>Working State</b>——临时、随任务、
 * 不参与长期记忆检索；长期记忆是 <b>Long-term Memory</b>。两者不得混为一谈：检查点不因 Turn 结束清空，
 * 由任务状态机驱动其存取与归档。
 *
 * <p>快照内容（JSON）由调用方决定（如 messages 片段 / 进度 / 未完成事项）；本服务只负责持久化与读取，
 * 保持与业务无关。
 */
@Service
public class TaskCheckpointService {

    private final TaskStore tasks;

    public TaskCheckpointService(TaskStore tasks) {
        this.tasks = tasks;
    }

    /** 保存检查点（任务暂停 / 中断前调用） */
    public int save(long taskId, String checkpointJson) {
        return tasks.setCheckpoint(taskId, checkpointJson);
    }

    /** 读取检查点（无则空） */
    public Optional<String> load(long taskId) {
        return Optional.ofNullable(tasks.checkpointOf(taskId));
    }

    /** 上次进程遗留、带检查点的可恢复任务 id（interrupted 状态） */
    public List<Long> restorable() {
        return tasks.idsWithCheckpoint(TaskStore.ST_INTERRUPTED);
    }
}
