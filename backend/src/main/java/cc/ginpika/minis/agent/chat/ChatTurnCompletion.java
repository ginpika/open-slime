package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.MemoryEvidence;
import cc.ginpika.minis.agent.runtime.TaskCheckpointService;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 回答完成后的记忆采集、任务检查点、命名与通知；不承担模型循环。 */
@Service
public class ChatTurnCompletion {
    private final HubSessionStore sessions;
    private final ProjectStore projects;
    private final MemoryIngest memoryIngest;
    private final WorkingSetService workingSet;
    private final TaskCheckpointService taskCheckpoints;
    private final SessionTitleService sessionTitles;
    private final SessionEventBus events;
    private static final Logger log = LoggerFactory.getLogger(ChatTurnCompletion.class);

    public ChatTurnCompletion(HubSessionStore sessions,
            ProjectStore projects,
            MemoryIngest memoryIngest,
            WorkingSetService workingSet,
            TaskCheckpointService taskCheckpoints,
            SessionTitleService sessionTitles,
            SessionEventBus events) {
        this.sessions = sessions;
        this.projects = projects;
        this.memoryIngest = memoryIngest;
        this.workingSet = workingSet;
        this.taskCheckpoints = taskCheckpoints;
        this.sessionTitles = sessionTitles;
        this.events = events;
    }

    public void complete(ChatExecution exec, HubRow row, MemoryExec memScope, long taskId,
                         long userMsgId, String input, String answer) {
        if (!exec.turnTracked()) {
            return;
        }
        if (!sessions.memoryOff(row.id())) {
            captureMemory(row, memScope, userMsgId, input, answer);
            saveCheckpoint(taskId, memScope);
        }
        nameSession(row, input);
        String title = sessions.findById(row.id()).map(HubRow::title).orElse(row.title());
        events.publish(new SessionEventBus.Event(row.id(), SessionEventBus.KIND_ANSWERED, false, title));
    }

    private void captureMemory(HubRow row, MemoryExec scope, long userMsgId, String input, String answer) {
        // 工具可能在本轮中切换项目：提炼按最新绑定归属，当前轮的读侧快照保持不变。
        HubRow latest = sessions.findById(row.id()).orElse(row);
        MemoryExec writeScope = MemoryExec.chat(scope.agentId(), latest.projectId(), row.id(), scope.taskId(),
                scope.turnId(), MemoryIdentity.ofNarrative(projects.isNarrative(latest.projectId())));
        memoryIngest.tap(row.id(), writeScope, input, answer,
                MemoryEvidence.ref("chat_message", userMsgId, input));
    }

    private void saveCheckpoint(long taskId, MemoryExec scope) {
        if (taskId <= 0) {
            return;
        }
        try {
            // 普通 Turn 只保存工作状态；长期记忆蒸馏由明确的会话收尾触发。
            taskCheckpoints.save(taskId, workingSet.checkpointDigest(scope));
        } catch (RuntimeException e) {
            log.debug("工作集检查点保存失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private void nameSession(HubRow row, String input) {
        String title = sessions.findById(row.id()).map(HubRow::title).orElse(null);
        if (title == null || title.isBlank()) {
            String stripped = input.strip();
            // 只用用户输入命名，不混入成员系统提示词和助手回答；不覆盖手动标题。
            sessionTitles.generateAsync(row.id(), input, stripped.substring(0, Math.min(20, stripped.length())));
        }
    }
}
