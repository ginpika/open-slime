package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.agent.context.ContextFragment.Ref;
import cc.ginpika.minis.ai.runtime.ModelLoop.PreparedRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Single-chat pilot state, scoped to one Turn and independent of the model protocol loop. */
final class ChatContextRun {
    private static final Logger log = LoggerFactory.getLogger(ChatContextRun.class);
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SUBAGENT_REPORT = "history/SUBAGENT_REPORT";
    private final HubRow session;
    private final String agentId;
    private final String actorId;
    private final long taskId;
    private final long turnId;
    private final ContextPlan plan;
    private final ChatContextSources sources;
    private final ChatContextManifests manifests;
    private final ChatContextContinuation continuation;
    private final Map<JsonNode, String> archived = new IdentityHashMap<>();
    private final Map<String, List<String>> toolReferences = new ConcurrentHashMap<>();
    private final Set<String> reportReferences;
    private final Set<String> sentReferences = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean usedScopedEvidence = new AtomicBoolean();
    private final AtomicBoolean usedContinuation = new AtomicBoolean();
    private final AtomicLong submittedCutoff = new AtomicLong();

    ChatContextRun(HubRow session, String actorId, long taskId, long turnId, ContextPlan plan,
                   ChatContextSources sources, ChatContextManifests manifests,
                   ChatContextContinuation continuation) {
        this.session = session;
        this.agentId = ChatSessionService.ownerOf(session.agentId());
        this.actorId = actorId;
        this.taskId = taskId;
        this.turnId = turnId;
        this.plan = plan;
        this.reportReferences = plan.delegatedReportReferences();
        this.sources = sources;
        this.manifests = manifests;
        this.continuation = continuation;
    }

    PreparedRequest prepare(int round, ObjectNode body, Map<JsonNode, List<String>> references) {
        var allRefs = new IdentityHashMap<>(references);
        for (JsonNode message : body.path("messages")) {
            List<String> refs = toolReferences.get(message.path("tool_call_id").asText());
            if (refs != null) {
                allRefs.put(message, refs);
            }
        }
        return prepared(Long.toString(taskId), round, plan.fit(body, this::archive, allRefs));
    }

    PreparedRequest prepareInterim(ChatSteering.InterimRequest interim) {
        return prepared(UUID.randomUUID().toString(), 0,
                plan.fitInterim(interim.body(), interim.initialUserIndex(), interim.references()));
    }

    String readSource(JsonNode call) {
        requireCurrent();
        String result = sources.run(session.id(), agentId, session.projectId(), call);
        JsonNode value = toolResponse(result);
        if (value != null && "ok".equals(value.path("status").asText())) {
            List<String> refs = new ArrayList<>();
            if (value.path("source").isTextual()) {
                refs.add(value.path("source").asText());
            }
            value.path("sources").forEach(source -> refs.add(source.path("source").asText()));
            remember(call, refs);
        }
        return result;
    }

    String saveContinuation(JsonNode call) {
        requireCurrent();
        return continuation.save(call);
    }

    void settleContinuation() {
        try {
            continuation.settle(turnId, submittedCutoff.get());
        } catch (DataAccessException error) {
            log.warn("阶段续接保存失败: session={}, turn={}", session.id(), turnId, error);
        }
    }

    void verifyContinuation() {
        continuation.requireCurrent(usedContinuation.get());
    }

    String runSubagent(JsonNode call, Supplier<String> dispatch) {
        requireCurrent();
        String result = dispatch.get();
        JsonNode value = toolResponse(result);
        if (value != null && value.path("id").isIntegralNumber() && value.path("id").asLong() > 0) {
            remember(call, List.of("subagent:" + value.path("id").asLong()));
        }
        return result;
    }

    boolean usedScopedEvidence() {
        return usedScopedEvidence.get();
    }

    private void remember(JsonNode call, List<String> refs) {
        String callId = call.path("id").asText();
        if (!refs.isEmpty() && !callId.isBlank()) {
            toolReferences.put(callId, List.copyOf(refs));
        }
    }

    private static JsonNode toolResponse(String result) {
        try {
            return result == null ? null : JSON.readTree(result);
        } catch (java.io.IOException invalid) {
            return null;
        }
    }

    /** Save native tool pairing for replay without keeping a second copy of a permission-checked source. */
    static List<JsonNode> replayableRounds(List<JsonNode> rounds) {
        List<JsonNode> replay = new ArrayList<>(rounds.size());
        Set<String> readbacks = new HashSet<>();
        for (JsonNode message : rounds) {
            String role = message.path("role").asText();
            if ("assistant".equals(role)) {
                readbacks.clear();
                for (JsonNode call : message.path("tool_calls")) {
                    if (!ChatContextSources.preservesToolResult(call.path("function").path("name").asText())) {
                        readbacks.add(call.path("id").asText());
                    }
                }
            } else if ("tool".equals(role) && readbacks.contains(message.path("tool_call_id").asText())
                    && message instanceof ObjectNode result) {
                replay.add(result.deepCopy().put("content", "〔来源回读正文已省略，需要时请重新查询来源〕"));
                continue;
            }
            replay.add(message);
        }
        return replay;
    }

    private String archive(JsonNode message, String name) {
        if (name.isBlank() || !ChatContextSources.preservesToolResult(name)) { return null; }
        return archived.computeIfAbsent(message, node -> sources.archiveToolResult(session.id(),
                agentId, session.projectId(), taskId, name,
                node.path("content").asText()));
    }

    private PreparedRequest prepared(String executionId, int round, ContextPlan.Fitted fitted) {
        var selection = fitted.selection();
        var selected = selection.selected();
        boolean usesStage = selected.stream().anyMatch(entry -> ContextPlan.CONTINUATION_SOURCE.equals(entry.source()));
        var epoch = selection.epoch();
        boolean activatesEpoch = epoch != null && epoch.id() == turnId && executionId.equals(Long.toString(taskId));
        Set<String> protectedRefs = new HashSet<>();
        for (var entry : selected) {
            if (SUBAGENT_REPORT.equals(entry.source()) || "live_tool_exchange".equals(entry.source())
                    || ("interim_dialogue".equals(entry.source())
                        && entry.refs().stream().map(Ref::value).anyMatch(reportReferences::contains))) {
                entry.refs().stream().map(Ref::value).forEach(protectedRefs::add);
            }
        }
        return new PreparedRequest(fitted.body(), retry -> {
            requireCurrent();
            if (continuation != null) {
                continuation.requireCurrent(usesStage);
            }
            Set<String> required = new HashSet<>(sentReferences);
            required.addAll(protectedRefs);
            if (required.stream().anyMatch(ref ->
                    !sources.referenceAvailable(session.id(), agentId, session.projectId(), ref))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮引用的证据已失效，请刷新会话后重试");
            }
            if (usesStage || !protectedRefs.isEmpty()) {
                usedScopedEvidence.set(true);
                sentReferences.addAll(protectedRefs);
            }
            if (usesStage) {
                usedContinuation.set(true);
            }
            var listener = manifests.listener(session, actorId, turnId, executionId, round, retry, selection);
            if (!activatesEpoch) {
                return listener;
            }
            return (body, bytes) -> {
                submittedCutoff.set(epoch.cutoff());
                if (listener != null) {
                    listener.submitted(body, bytes);
                }
            };
        });
    }

    private void requireCurrent() {
        sources.requireCurrent(session.id(), agentId, session.projectId(), taskId);
    }
}
