package cc.ginpika.minis.agent.peer;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 发送记录与接收成员答复组成的双向会话，范围限定在点击来源的会话。 */
@Service
public class AgentMessageConversation {
    public record Agent(String id, String name, String avatar) {}

    public record Message(String id, long executionId, String fromAgentId, String toAgentId,
                          String content, long createdAt, String kind, String status) {}

    public record View(Agent agentA, Agent agentB, List<Message> messages) {}

    private final ExecStore execs;
    private final AgentDirectory directory;

    public AgentMessageConversation(ExecStore execs, AgentDirectory directory) {
        this.execs = execs;
        this.directory = directory;
    }

    public Optional<View> find(String sessionId, long executionId) {
        var exchanges = execs.agentMessageExchanges(sessionId, executionId);
        var anchor = exchanges.stream().filter(exchange -> exchange.id() == executionId).findFirst().orElse(null);
        if (anchor == null) {
            return Optional.empty();
        }

        List<Message> messages = new ArrayList<>();
        for (var exchange : exchanges) {
            long id = exchange.id();
            String sender = exchange.senderId();
            String recipient = exchange.recipientId();
            String status = exchange.status();
            messages.add(new Message(id + ":request", id, sender, recipient, exchange.message(),
                    exchange.createdAt(), "request", status));
            long finishedAt = Objects.requireNonNullElse(exchange.finishedAt(), exchange.updatedAt());
            switch (status) {
                case TaskStore.ST_DONE -> messages.add(new Message(id + ":reply", id, recipient, sender,
                        Objects.requireNonNullElse(exchange.output(), ""), finishedAt, "reply", status));
                case TaskStore.ST_FAILED, TaskStore.ST_CANCELLED, TaskStore.ST_INTERRUPTED ->
                        messages.add(new Message(id + ":failure", id, recipient, sender,
                                Objects.requireNonNullElse(exchange.error(), failureText(status)),
                                finishedAt, "failure", status));
                default -> { /* The request remains visible while it is in progress. */ }
            }
        }
        // Stable ordering keeps the request before its reply even when both times fall in the same millisecond.
        messages.sort(Comparator.comparingLong(Message::createdAt));
        return Optional.of(new View(agent(anchor.senderId()), agent(anchor.recipientId()), List.copyOf(messages)));
    }

    private Agent agent(String id) {
        return directory.peer(id).map(peer -> new Agent(id, peer.name(), peer.avatar()))
                .orElseGet(() -> new Agent(id, id, null));
    }

    private static String failureText(String status) {
        return switch (status) {
            case TaskStore.ST_CANCELLED -> "接收 Agent 的执行已取消。";
            case TaskStore.ST_INTERRUPTED -> "接收 Agent 的执行已中断。";
            default -> "接收 Agent 执行失败。";
        };
    }
}
