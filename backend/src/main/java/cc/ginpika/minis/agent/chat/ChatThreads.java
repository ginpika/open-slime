package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 后台消息的会话写入入口：创建会话并投递助手消息，不调用模型和工具。 */
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
        return appendAssistantMessage(threadId, content, null);
    }

    /** 带消息种类的后台投递，便于会话将回报与普通回答分开。 */
    public Optional<String> appendAssistantMessage(String threadId, String content, String kind) {
        HubRow session = sessions.findById(threadId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        long id = messages.insert(threadId, "assistant", content, null, null, null, kind, null);
        /* 投递不是人问的：没有触发端，落一条「系统投递」的 Turn，历史配对才完整 */
        turnStore.record(threadId, TurnTrigger.Source.SYSTEM, id);
        sessions.touch(threadId, null);
        sessions.markUnread(threadId);
        return Optional.of(session.title() == null ? threadId : session.title());
    }
}
