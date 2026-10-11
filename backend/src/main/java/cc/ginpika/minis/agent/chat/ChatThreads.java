package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 会话投递：往首页 chat 的会话里开一个会话、投一条 assistant 消息。
 *
 * <p>专供外围模块（潜意识的任务计划）的 {@code open_thread} / {@code send_message} 两个工具落地使用：
 * 这两件事只碰会话表与消息表，不含任何模型调用或对话编排，因此从 {@link ChatService} 拆出来单独成件。
 *
 * <p><b>为什么必须拆</b>：{@code ChatService} 需要 {@link HeartbeatTool}（执行计划工具）而
 * 潜意识需要写会话，两个单例构造互指成环，此前只能靠 {@code @Lazy} 撑住。本组件只依赖两张 store 表，
 * 把「写会话」这条边从 ChatService 上摘下来，环即消失——潜意识侧因此不必再引用 ChatService。
 */
@Component
public class ChatThreads {

    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    /** Turn 正本：投递也要落一条 Turn，历史里才看得到这一段 */
    private final TurnStore turnStore;

    public ChatThreads(HubSessionStore sessions, ChatMessageStore messages, TurnStore turnStore) {
        this.sessions = sessions;
        this.messages = messages;
        this.turnStore = turnStore;
    }

    /**
     * open_thread 的执行：按会话名开/取一个会话（同名复用、无则新建），返回会话 id。
     */
    public String openOrCreate(String title) {
        return sessions.openOrCreate(title);
    }

    /**
     * send_message 的执行：往首页 chat 的会话投一条 assistant 消息并打未读点（首页「最近会话」提示）。
     * 会话不存在返回 empty；存在时返回会话标题（无标题用 id），供工具回执称呼。
     */
    public Optional<String> appendAssistantMessage(String threadId, String content) {
        HubRow session = sessions.findById(threadId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        long id = messages.insert(threadId, "assistant", content, null, null, null, null, null);
        /* 投递不是人问的：没有触发端，落一条「系统投递」的 Turn，历史配对才完整 */
        turnStore.record(threadId, TurnTrigger.Source.SYSTEM, id);
        sessions.touch(threadId, null);
        sessions.markUnread(threadId);
        return Optional.of(session.title() == null ? threadId : session.title());
    }
}
