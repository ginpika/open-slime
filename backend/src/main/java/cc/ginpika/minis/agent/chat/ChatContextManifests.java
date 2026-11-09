package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.SubmissionListener;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 单聊试点的进程内诊断记录。只留出站请求的结构和引用，最多 500 条、24 小时。 */
@Service
public class ChatContextManifests {
    private static final int CAPACITY = 500;
    private static final long RETENTION_MS = Duration.ofHours(24).toMillis();

    public record Manifest(long turnId, String executionId, int round, int retry, int submission,
                           long submittedAt, String model, long bodyBytes, List<String> messageRoles,
                           List<String> toolNames, long estimatedInputTokens, ContextPlan.Selection selection) {}

    private record Stored(String sessionId, String agentId, String projectId, String actorId, Manifest manifest) {}

    private final HubSessionStore sessions;
    private final TurnStore turns;
    private final ArrayDeque<Stored> records = new ArrayDeque<>();

    public ChatContextManifests(HubSessionStore sessions, TurnStore turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    public SubmissionListener listener(HubRow row, String actorId, long turnId, String executionId,
                                       int round, int retry, ContextPlan.Selection selection) {
        if (actorId == null || actorId.isBlank() || turnId <= 0 || selection == null) return null;
        String agentId = ChatSessionService.ownerOf(row.agentId());
        AtomicInteger submissions = new AtomicInteger();
        return (body, bytes) -> {
            long now = System.currentTimeMillis();
            var manifest = new Manifest(turnId, executionId, round, retry, submissions.incrementAndGet(),
                    now, body.path("model").asText(), bytes,
                    fieldValues(body.path("messages"), "role"), fieldValues(body.path("tools"), "function", "name"),
                    ContextPlan.estimateRequest(body), selection);
            append(new Stored(row.id(), agentId, row.projectId(), actorId, manifest), now);
        };
    }

    public List<Manifest> recent(String sessionId, String actorId, Long turnId, int limit) {
        if (actorId == null || actorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        HubRow row = sessions.findById(sessionId).filter(session -> "ai".equals(session.mode()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "单聊会话不存在"));
        int resultLimit = Math.clamp(limit, 1, 100);
        List<Manifest> candidates = candidates(row, actorId, turnId);
        Map<Long, Boolean> present = new HashMap<>();
        return candidates.stream().filter(manifest -> present.computeIfAbsent(manifest.turnId(),
                        id -> turns.triggerPresent(sessionId, id.toString())))
                .limit(resultLimit).toList();
    }

    private synchronized List<Manifest> candidates(HubRow row, String actorId, Long turnId) {
        expire(System.currentTimeMillis());
        List<Manifest> matches = new ArrayList<>();
        String agentId = ChatSessionService.ownerOf(row.agentId());
        for (var iterator = records.descendingIterator(); iterator.hasNext();) {
            Stored stored = iterator.next();
            if (row.id().equals(stored.sessionId()) && actorId.equals(stored.actorId())
                    && agentId.equals(stored.agentId()) && Objects.equals(row.projectId(), stored.projectId())
                    && (turnId == null || turnId == stored.manifest().turnId())) {
                matches.add(stored.manifest());
            }
        }
        return matches;
    }

    private synchronized void append(Stored record, long now) {
        expire(now);
        if (records.size() == CAPACITY) records.removeFirst();
        records.addLast(record);
    }

    private void expire(long now) {
        records.removeIf(stored -> stored.manifest().submittedAt() < now - RETENTION_MS);
    }

    private static List<String> fieldValues(JsonNode array, String... path) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> {
            for (String key : path) node = node.path(key);
            return node.asText();
        }).toList();
    }
}
