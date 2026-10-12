package cc.ginpika.minis.agent.runtime;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 启动恢复：进程重启后，把上次遗留的「在途」收成「已中断」。
 *
 * <p>在途执行跑在进程内的线程里（子代理用虚拟线程池），进程一死线程就没了，而库里那几行
 * 状态还停在 running/pending——不处理的话，页面会显示一批永远进行中的幻影任务，且分不清
 * 「真在跑」与「早就没了」。启动时统一收成 interrupted，语义就清晰了：上次没跑完，可以重跑。
 *
 * <p>两张表一起收：{@code task}（任务身份与过程留痕）与 {@code heartbeat_run}（批次）。
 * 不存在自动续跑——「可恢复」在最小切片里是「可见 + 可重跑」，不是无声地接着跑。
 *
 * <p>时机：注入 DataSource 的 bean 构造时 schema.sql 已执行完（Spring Boot 保证），
 * 故 {@code @PostConstruct} 里直接读写真表。恢复只在启动那一次做，之后的新任务不受影响。
 */
@Component
public class TaskRecovery {

    private static final Logger log = LoggerFactory.getLogger(TaskRecovery.class);

    private final TaskStore tasks;
    private final ExecStore execs;
    private final TaskCheckpointService checkpoints;
    /** 工作状态恢复（memory-v6 §9.2）：把检查点还原成工作集条目，下一次执行接得上 */
    private final WorkingSetRestorer restorer;

    public TaskRecovery(TaskStore tasks, ExecStore execs, TaskCheckpointService checkpoints,
                        WorkingSetRestorer restorer) {
        this.tasks = tasks;
        this.execs = execs;
        this.checkpoints = checkpoints;
        this.restorer = restorer;
    }

    @PostConstruct
    void recover() {
        int interruptedTasks = tasks.interruptRunning();
        int interruptedExecs = execs.interruptRunning();
        if (interruptedTasks > 0 || interruptedExecs > 0) {
            log.info("启动恢复：{} 条任务、{} 条执行留痕由在途置为 interrupted（前端可见、可重跑）",
                    interruptedTasks, interruptedExecs);
        } else {
            log.info("启动恢复：没有残留的在途任务");
        }
        /* memory-v6 §9.2：带记忆检查点的中断任务——把「做到哪一步」还原进工作集（幂等，重启不重复还原） */
        var restorable = checkpoints.restorable();
        if (restorable.isEmpty()) {
            return;
        }
        int restored = restorer.restoreInterrupted();
        log.info("启动恢复：{} 条中断任务带记忆检查点，其中 {} 条已还原工作状态（可手动 /promote 触发重跑）",
                restorable.size(), restored);
    }
}
