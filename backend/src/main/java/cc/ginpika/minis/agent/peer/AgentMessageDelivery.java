package cc.ginpika.minis.agent.peer;

import cc.ginpika.minis.agent.chat.ChatThreads;
import cc.ginpika.minis.agent.chat.SessionEventBus;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.roundtable.RoundtableChatService;
import cc.ginpika.minis.agent.roundtable.RoundtableMessageStore;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 把成员答复写回来源会话，沿用各会话已有的消息和刷新机制。 */
@Component
public class AgentMessageDelivery {
    private final HubSessionStore sessions;
    private final ChatThreads chats;
    private final TurnStore turns;
    private final RoundtableMessageStore roundtable;
    private final SessionEventBus sessionEvents;
    private final RuntimeEventBus roundtableEvents;
    private final TransactionTemplate transactions;

    public AgentMessageDelivery(HubSessionStore sessions, ChatThreads chats, TurnStore turns,
                                RoundtableMessageStore roundtable,
                                SessionEventBus sessionEvents, RuntimeEventBus roundtableEvents,
                                TransactionTemplate transactions) {
        this.sessions = sessions;
        this.chats = chats;
        this.turns = turns;
        this.roundtable = roundtable;
        this.sessionEvents = sessionEvents;
        this.roundtableEvents = roundtableEvents;
        this.transactions = transactions;
    }

    public void deliver(String hubId, String recipientId, String recipientName, AgentRunner.Outcome outcome,
                        String sourceTurnId) {
        var session = sessions.findById(hubId).orElse(null);
        if (session == null) return;
        String result = TaskStore.ST_DONE.equals(outcome.status()) ? outcome.output()
                : (outcome.error() == null ? "执行失败。" : outcome.error());
        String body = "【" + recipientName + " · 工作消息 #" + outcome.execId() + "】\n\n" + result;
        if (RoundtableChatService.MODE.equals(session.mode())) {
            Long messageId = transactions.execute(status -> {
                if (sessions.findById(hubId).isEmpty()) return null;
                long id = roundtable.insert(hubId, null, RoundtableMessageStore.ROLE_PEER, recipientId,
                        recipientName, body, null, null, null, null);
                sessions.touch(hubId, null);
                return id;
            });
            if (messageId != null) {
                roundtableEvents.publish(RuntimeEvent.of(RuntimeEventType.AGENT_MESSAGE, hubId, recipientId,
                        null, Long.toString(outcome.execId()), null, Map.of("messageId", messageId)));
            }
        } else {
            var title = transactions.execute(status -> {
                if (sourceTurnId != null && !turns.triggerPresent(hubId, sourceTurnId)) return null;
                return chats.appendAssistantMessage(hubId, body, ChatMessageStore.KIND_AGENT_MESSAGE);
            });
            if (title != null) {
                title.ifPresent(value -> sessionEvents.publish(new SessionEventBus.Event(hubId,
                        SessionEventBus.KIND_DELIVERED, true, value)));
            }
        }
    }
}
