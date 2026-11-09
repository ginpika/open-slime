package cc.ginpika.minis.agent.peer;

import cc.ginpika.minis.agent.chat.SessionEventBus;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableMessageStore;
import cc.ginpika.minis.agent.roundtable.RoundtableSessionStore;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 在来源会话留下已发送标签，消息正文保留在 Agent 执行记录中。 */
@Component
public class AgentMessageReceipt {
    private static final Logger log = LoggerFactory.getLogger(AgentMessageReceipt.class);

    record Accepted(long id, String title) {}

    private final AgentRunner runner;
    private final HubSessionStore sessions;
    private final ChatMessageStore chats;
    private final RoundtableMessageStore roundtable;
    private final SessionEventBus events;
    private final ObjectMapper om;
    private final TransactionTemplate transactions;

    public AgentMessageReceipt(AgentRunner runner, HubSessionStore sessions, ChatMessageStore chats,
                               RoundtableMessageStore roundtable, SessionEventBus events, ObjectMapper om,
                               PlatformTransactionManager transactionManager) {
        this.runner = runner;
        this.sessions = sessions;
        this.chats = chats;
        this.roundtable = roundtable;
        this.events = events;
        this.om = om;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    Accepted open(AgentRunner.Request request, AgentRow recipient) {
        String hubId = request.sessionId();
        return Objects.requireNonNull(transactions.execute(status -> {
            long id = runner.openExec(request);
            if (id <= 0) throw new IllegalStateException("未生成 Agent 消息执行编号");
            var session = sessions.findById(hubId)
                    .orElseThrow(() -> new IllegalStateException("来源会话不可用"));
            String content = om.createObjectNode().put("id", id).put("recipientId", recipient.id())
                    .put("recipientName", recipient.name()).toString();
            long markerId = switch (session.mode()) {
                case "ai" -> chats.insert(hubId, "event", content, null, null, null,
                        ChatMessageStore.KIND_AGENT_MESSAGE_SENT, null);
                case RoundtableSessionStore.MODE -> roundtable.insertNotice(hubId, null, content,
                        RoundtableMessageStore.KIND_AGENT_MESSAGE_SENT);
                default -> throw new IllegalStateException("来源会话不支持 Agent 消息");
            };
            if (markerId <= 0) throw new IllegalStateException("未保存 Agent 消息发送记录");
            return new Accepted(id, session.title());
        }));
    }

    void publish(String hubId, Accepted accepted) {
        try {
            events.publish(new SessionEventBus.Event(hubId, SessionEventBus.KIND_AGENT_MESSAGE_SENT,
                    false, accepted.title()));
        } catch (RuntimeException error) {
            log.warn("Agent 消息 {} 的发送记录通知失败：{}", accepted.id(), error.getMessage());
        }
    }
}
