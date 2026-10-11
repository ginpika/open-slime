package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 一次会话执行的<b>可替换点</b>：会话从哪来、消息落到哪、请求体怎么组装、工具归谁办。
 *
 * <p>从 {@link ChatTurnRunner} 那 13 个 {@code protected} 覆盖点提炼而来。提炼之前，多智能体那条流
 * 靠<b>继承</b>换掉这些点；提炼之后，它只需<b>实现本接口</b>再交给同一个执行器跑——执行循环、流式、
 * 停止、留痕一字不改，而两者之间不再有继承关系（{@code RoundtableChatService} 从「是」一个执行器，变成
 * 「用」一个执行器）。
 *
 * <p>{@link ChatTurnRunner} 自己就是首页对话的实现：不传本接口时，它默认拿自己做执行者。
 */
public interface ChatExecution {

    /** 取会话：给定 id 则按 id 取（不存在=404），缺省取最近一个（无则先建） */
    HubRow resolveOrCreate(String hubId);

    /** 新建一个会话并返回它 */
    HubRow insertNew();

    /** 某会话的既存消息（组装上下文与总结共用） */
    List<MessageRow> history(HubRow row);

    /** 落一条用户输入，返回消息行 id */
    long recordUserInput(HubRow row, String input);

    /** 落一条助手回复，返回消息行 id */
    long recordReply(HubRow row, String content, String reasoning, String trace, String rounds);

    /** 会话有动静（新消息落库后）：刷新时间戳并按需回填标题 */
    void touchSession(HubRow row, String title);

    /** 记住本次使用的模型 / 身份版本（会话级配置，下次进入该会话自动回显） */
    void rememberConfig(HubRow row, String modelId, String promptId);

    /**
     * 组装一次对话补全的请求体（model + system + 历史回放 + 工具面）。
     *
     * @param contextWindow 本次模型的上下文窗口（token 数）：交给上下文平面判断是否该裁历史
     */
    ObjectNode requestBody(HubRow row, ModelTarget target, String promptId, int contextWindow);

    /** 执行一次工具调用，返回要回给模型的 tool 消息内容 */
    String runTool(JsonNode call, String batchRunId, String hubId);

    /** 这次工具调用来自哪条流（RAG 召回留痕的 source） */
    String toolSource();

    /**
     * 这次生成是否落一条会话任务（见 {@link ChatTaskRunner}）：任务有身份、状态、可恢复，
     * 于是「这一次执行为什么没成功」有处可查（{@code task.error} / {@code task.status}）。
     *
     * <p><b>可替换点</b>：多智能体那条流也跟踪任务——它的一次成员发言同样是一次真实执行，
     * 失败时应当留痕。默认（不覆写）与 {@link #turnTracked()} 同源。
     */
    boolean taskTracked();

    /**
     * 这次生成是否记一条 Turn 正本（见 {@code agent.chat.turn.TurnStore}）并推「答完了」通知。
     *
     * <p>Turn 是按「提问 → 回答」配对的：{@code turnStore.begin} 需要一条触发端消息作为一端。
     * 首页对话满足这个形状；圆桌的一次成员发言不满足（人的话由 {@code RoundtableTopicService}
     * 落，成员的发言是一段一段的），故圆桌覆写为 {@code false}——它跟踪任务，但不记 Turn。
     *
     * <p>默认与 {@link #taskTracked()} 一致：两条既有流（首页对话 = 都做）零行为变化。
     */
    default boolean turnTracked() {
        return taskTracked();
    }

    /** 这次生成是否受「同一会话不并发」的在途锁约束 */
    boolean singleFlight();
}
