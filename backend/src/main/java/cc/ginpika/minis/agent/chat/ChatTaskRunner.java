package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.runtime.TaskStore;
import org.springframework.stereotype.Component;

/** 会话任务的登记与收尾：一次生成对应一条 session 任务，保存状态与答复产物。
 * 模型和工具循环由 AgentRuntime 执行，流式与取消语义由 ChatTurnRunner 协调。 */
@Component
public class ChatTaskRunner {

    private final TaskStore tasks;

    public ChatTaskRunner(TaskStore tasks) {
        this.tasks = tasks;
    }

    /**
     * 开一次会话生成的任务（pending → running），返回任务 id。
     *
     * <p>调用方不跟踪任务时传 0：本类的收尾方法对非正数一律空转，调用点不必处处判分支。
     */
    public long begin(String sessionId, String agentId, String title, String input) {
        long id = tasks.insert(TaskStore.KIND_SESSION, null, title, sessionId, agentId, input);
        tasks.setStatus(id, TaskStore.ST_RUNNING);
        return id;
    }

    /** 正常答完：done，并把答复落成文本产物（结论文本） */
    public void done(long taskId, String reply) {
        if (taskId <= 0) {
            return;
        }
        addReply(taskId, reply);
        tasks.setStatus(taskId, TaskStore.ST_DONE);
    }

    /** 用户中途停止：cancelled；已有部分正文就照常作为产物留下（刷新后对话仍读得到） */
    public void cancelled(long taskId, String partial) {
        if (taskId <= 0) {
            return;
        }
        addReply(taskId, partial);
        tasks.setStatus(taskId, TaskStore.ST_CANCELLED);
    }

    /** 出错：failed（留在 running 会被下次启动误判成「进程重启打断」） */
    public void failed(long taskId) {
        if (taskId <= 0) {
            return;
        }
        tasks.setStatus(taskId, TaskStore.ST_FAILED);
    }

    private void addReply(long taskId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        tasks.addArtifact(taskId, null, TaskStore.ART_TEXT, null, reply, null);
    }
}
