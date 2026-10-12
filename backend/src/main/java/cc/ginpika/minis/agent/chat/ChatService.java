package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 会话门面（dispatcher）：Web 层的唯一入口，自身不含任何领域逻辑——只把请求转给三个领域处理者。
 *
 * <p>从「既是会话门面、又跑整条生成循环」的旧 ChatService 拆出（行为零变化）。拆分后三方各管一摊：
 * <ul>
 *   <li><b>{@link ChatTurnRunner}</b>——一次生成（执行侧）：模型往返、工具、trace、停止；</li>
 *   <li><b>{@link ChatSessionService}</b>——会话域：列表 / 增删 / 配置 / 历史 / 回退；</li>
 *   <li><b>{@link ChatLiveStore}</b>——在途生成仓：流式增量留痕与断点续传。</li>
 * </ul>
 * 本类因此只依赖这三个（外加对外契约里的 {@link StreamSink} / {@link ChatCancelledException}），
 * 不再握有模型客户端、工具管理器、能力平面等一整套执行依赖。
 *
 * <p><b>为什么留着</b>：控制器不该知道「一次生成」背后有哪些领域对象；它只发一种请求，由这里决定
 * 谁来办。要换实现（例如把执行侧换成 Agent 内核），改的是本类的转发行，控制器不必动。
 */
@Service
public class ChatService {

    private final ChatTurnRunner turns;
    private final ChatSessionService sessions;
    private final ChatLiveStore live;
    /** 个人待办清单（plan-of-record）：写由执行侧经 {@code TodoService} 落地，读经本门面暴露给会话页 */
    private final TodoService todo;

    public ChatService(ChatTurnRunner turns, ChatSessionService sessions, ChatLiveStore live,
                       TodoService todo) {
        this.turns = turns;
        this.sessions = sessions;
        this.live = live;
        this.todo = todo;
    }

    // ---------- 生成：转给执行侧 ----------

    /** 发问：sessionId 缺省 → 最近会话（无则先建）；modelId/promptId 缺省 → 设置页默认项 */
    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId) {
        return turns.chat(hubId, input, modelId, promptId);
    }

    /** 流式发问：各阶段回调见 {@link StreamSink} */
    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         StreamSink sink) {
        return turns.chatStream(hubId, input, modelId, promptId, sink);
    }

    /** 停止该会话进行中的流式生成 */
    public boolean requestStop(String hubId) {
        return turns.requestStop(hubId);
    }

    /** 会话的原始上下文预览（按与真实发送一致的规则组装，不发送、不落库；附逐 Turn 切片） */
    public ChatDtos.RawContext rawContext(String hubId) {
        return turns.rawContext(hubId);
    }

    /** 某条回答（Turn）的全部 LLM 调用留痕（每轮的请求体 + 响应体），供「查看原始上下文」里回看 */
    public ChatDtos.TurnCalls turnCalls(String hubId, long replyMessageId) {
        return turns.turnCalls(hubId, replyMessageId);
    }

    // ---------- 会话域：转给 ChatSessionService ----------

    public List<ChatDtos.SessionItem> list() {
        return sessions.list();
    }

    public ChatDtos.NewSession create() {
        return sessions.create();
    }

    /** 新建会话并绑定项目（memory-v6：项目是记忆归属与跨项目隔离的依据） */
    public ChatDtos.NewSession create(String projectId) {
        return sessions.create(projectId);
    }

    /** 绑定 / 解绑会话所属项目（记忆作用域切换） */
    public ChatDtos.NewSession setProject(String hubId, String projectId) {
        return sessions.setProject(hubId, projectId);
    }

    public ChatDtos.SessionDeleted delete(String hubId) {
        return sessions.delete(hubId);
    }

    public List<ChatDtos.MessageRow> messages(String hubId) {
        return sessions.messages(hubId);
    }

    /** 会话的个人待办清单（plan-of-record）：按该会话绑定的 Agent 定位（与写入侧同口径），
     *  会话页 TODO 卡片读取当前进度 */
    public List<ChatDtos.TodoItem> todos(String hubId) {
        return todo.list(hubId, sessions.owner(hubId));
    }

    public ChatDtos.SessionConfig updateConfig(String hubId, ChatDtos.ConfigRequest req) {
        return sessions.updateConfig(hubId, req);
    }

    public ChatDtos.Rewind rewind(String hubId, Long messageId, Integer keep) {
        return sessions.rewind(hubId, messageId, keep);
    }

    /** 分支：以某条消息为界，把到这条为止的转录复制进一个新会话（源会话不动） */
    public ChatDtos.NewSession branch(String hubId, Long messageId) {
        return sessions.branch(hubId, messageId);
    }

    // ---------- 在途生成仓：转给 ChatLiveStore（断点续传） ----------

    public ChatDtos.LiveRun live(String sessionId) {
        return live.snapshot(sessionId);
    }

    public boolean attachLive(String sessionId, ChatLiveStore.LiveListener listener) {
        return live.attach(sessionId, listener);
    }

    public void liveStart(String sessionId, long userMessageId, String userText) {
        live.start(sessionId, userMessageId, userText);
    }

    public void liveRound(String sessionId, int round) {
        live.round(sessionId, round);
    }

    public void liveDelta(String sessionId, String reasoning, String content) {
        live.delta(sessionId, reasoning, content);
    }

    public void liveTool(String sessionId, String name, String args) {
        live.tool(sessionId, name, args);
    }

    public void liveFinish(String sessionId, String finalContent) {
        live.finish(sessionId, finalContent);
    }

    public void liveFail(String sessionId, String error) {
        live.fail(sessionId, error);
    }

    // ---------- 对外契约（控制器实现它来接流式回调） ----------

    /** 流式阶段回调（按发生顺序调用）；默认空实现，调用方只覆写关心的阶段 */
    public interface StreamSink {
        /** user 消息已落库：sessionId=实际会话，messageId=该条提问的行 id */
        default void meta(String sessionId, long messageId) {
        }

        /** 进入第 round 轮补全（>0 为工具轮之后的新一轮，前端应清空已渲染的正文） */
        default void round(int round) {
        }

        /** 模型产出的增量片段：reasoning=思维链、content=正文，缺一为 null */
        default void delta(String reasoning, String content) {
        }

        /** 模型请求调用工具：name=工具名，args=调用参数（JSON 字符串） */
        default void tool(String name, String args) {
        }

        /**
         * 又开口说了一段：text 就是这一段话本身。
         *
         * <p><b>只有圆桌用得上</b>（默认空实现，对话页不会收到）：那里成员可以边说边想，
         * 一次发言由好几个段落组成——每段各占一个气泡，所以「一段的边界」得有个独立信号，
         * 不能混在 {@link #delta} 里靠换行去猜。它一旦出现，此前的正文就到此为止。
         */
        default void speech(String text) {
        }
    }

    /** 用户主动停止且无任何正文产出：向上抛出中断生成，Controller 静默收尾（不推 error 事件） */
    public static final class ChatCancelledException extends RuntimeException {
    }
}
