package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDtos.WorkflowEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 将已有公开记录与协作信号合并为有界的只读时间线，不引入第二份运行状态。 */
@Component
public class RoundtableActivity {
    private static final int LIMIT = 30;
    private static final Set<String> SIGNALS = Set.of("TASK_CREATED", "TASK_BLOCKED", "TASK_COMPLETED",
            "WORK_STARTED", "ALL_WORK_DONE", "DISCUSSION_REQUESTED", "AGENT_REPORT", "CANCEL_REQUESTED");
    private final RuntimeEventStore events;
    private final RoundtableMessageStore messages;
    private final ObjectMapper om;

    public RoundtableActivity(RuntimeEventStore events, RoundtableMessageStore messages, ObjectMapper om) {
        this.events = events;
        this.messages = messages;
        this.om = om;
    }

    public List<WorkflowEvent> recent(String sessionId) {
        List<WorkflowEvent> result = new ArrayList<>();
        for (var row : messages.recentActivity(sessionId, LIMIT)) {
            String kind = "peer".equals(row.role()) ? "speech"
                    : "user".equals(row.role()) ? "user" : row.kind();
            String text = row.content() == null ? "" : row.content();
            if ("speech".equals(kind) && row.peerName() != null) text = row.peerName() + "：" + text;
            result.add(new WorkflowEvent("message:" + row.id(), kind, row.peerId(), List.of(),
                    null, text, row.createdAt()));
        }
        for (var row : events.recent(sessionId, 100)) {
            if (!SIGNALS.contains(row.type())) continue;
            JsonNode payload;
            try {
                payload = om.readTree(row.payload() == null ? "{}" : row.payload());
                if (payload == null) payload = om.createObjectNode();
            } catch (Exception ignored) {
                payload = om.createObjectNode();
            }
            String target = row.target() == null ? payload.path("with").asText("") : row.target();
            List<String> targets = Arrays.stream(target.split(",")).map(String::strip)
                    .filter(s -> !s.isEmpty()).distinct().toList();
            String text = "";
            for (String key : List.of("reason", "content", "title", "note")) {
                if (payload.path(key).isTextual() && !payload.path(key).asText().isBlank()) {
                    text = payload.path(key).asText();
                    break;
                }
            }
            result.add(new WorkflowEvent("event:" + row.id(), row.type(), row.source(), targets,
                    row.taskId() == null ? payload.path("todoId").asText(null) : row.taskId(),
                    text.substring(0, Math.min(text.length(), 280)), row.createdAt()));
        }
        return result.stream().sorted(Comparator.comparingLong(WorkflowEvent::createdAt).reversed()
                .thenComparing(WorkflowEvent::id, Comparator.reverseOrder())).limit(LIMIT).toList();
    }
}
