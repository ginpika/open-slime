package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.runtime.TaskStore;
import org.springframework.stereotype.Component;

/**
 * 会话任务的登记与收尾：前台每「一次生成」= 一条 {@code kind=session} 的任务。
 *
 * <p>于是三条旧流在任务体系里齐了：会话（这里）、子代理（{@code SubagentService}）、
 * 心跳编制（{@code HeartbeatService}）。会话任务没有 {@code origin} 过程留痕
 * （它的正本是 {@code chat_message}），故 {@code exec_id} 为空；结论照常落成一条文本产物。
 *
 * <p><b>为什么循环不在这里</b>：架构文档写的是「{@code ChatService.runChat} 变成
 * {@code ChatTaskRunner.execute}，循环逻辑原样搬运」。但那条循环（流式、停止标志、工具往返、
 * trace）被多智能体那条流（{@code RoundtableChatService}）以「重写可覆盖点、复用整条循环」的方式继承，
 * 搬走会把它的继承关系整个掀掉，与「旧行为零变化」这条硬指标冲突。STEP 1 因此只把
 * <b>任务生命周期</b>收在这里，循环原样留在 {@code ChatTurnRunner}（它从 {@code ChatService} 整体
 * 搬出后，{@code ChatService} 只剩门面转发）；等独立任务队列落地
 * （§9-1 拍板后）再谈把循环搬进来的事。
 */
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
