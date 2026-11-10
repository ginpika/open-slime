package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.agent.context.ContextFragment.Layer;
import cc.ginpika.minis.agent.context.ContextFragment.Ref;
import cc.ginpika.minis.agent.context.ContextFragment.Trust;
import cc.ginpika.minis.ai.core.tool.ToolOutput;
import cc.ginpika.minis.ai.core.tool.chat.RequestTodoCompletionTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Single-chat pilot selection. Canonical input remains available to steering and the transcript. */
public final class ContextPlan {
    private static final Logger log = LoggerFactory.getLogger(ContextPlan.class);
    public static final String CONTINUATION_SOURCE = "context_continuation";
    public static final String CONTINUATION_TOOL = "save_context_continuation";
    private static final String STRATEGY = "personal-chat-p1/v1";
    private static final String USER_HISTORY = "history/USER_INPUT";
    private static final String READBACK = "read_context_source";
    private static final int OMISSION_LIMIT = 64;
    private static final int TOOL_PREVIEW_CHARS = 1000;
    private static final int ARCHIVE_HINTS = 8;
    private static final int RECENT_IMAGES = 2;

    public record Scope(String sessionId, String agentId, String projectId) {}

    /**
     * R5 工作窗口与 R7 场景对照：当前段的默认回放上限（最多多少个已完成 Turn、历史正文估算上限），
     * 以及本次装配的任务段与授权代号（进 Manifest）。maxTurns 与 tokenCap 均非正表示未启用窗口。
     */
    public record Window(int maxTurns, long tokenCap, long sceneId, long scopeRevision) {
        public Window {
            maxTurns = Math.max(0, maxTurns);
            tokenCap = Math.max(0, tokenCap);
            sceneId = Math.max(0, sceneId);
            scopeRevision = Math.max(0, scopeRevision);
        }

        boolean enabled() {
            return maxTurns > 0 || tokenCap > 0;
        }
    }

    public record Stage(long turnId, long stageTriggerId, long epochTurnId, long cutoff,
                        ContextFragment continuation, int keepTurns, long currentTurnId) {}

    /** The cutoff uses the trigger of the last excluded historical user turn. */
    public record Epoch(long id, long stageTurnId, long cutoff, String reason) {}

    public record Entry(String source, Scope scope, Layer layer, Trust trust, List<Ref> refs,
                        Integer from, Integer to, List<String> reasons) {}

    public record Selection(String strategy, int contextWindow, long reservedOutputTokens, long safetyTokens,
                            long estimatedInputTokens, long historyWatermark,
                            Long sceneId, Long scopeRevision,
                            List<Entry> selected, List<Entry> omitted, int omittedCount, Epoch epoch) {}

    public record Fitted(ObjectNode body, Selection selection) {}

    private final ObjectNode body;
    private final List<ContextFragment> fragments;
    private final Scope scope;
    private final Window bounds;
    private final String strategy;
    private final int contextWindow;
    private final long outputReserve;
    private final long safety;
    private final Stage stage;
    private final Map<JsonNode, List<String>> sourceByMessage = new IdentityHashMap<>();
    private final Map<JsonNode, Long> historyTriggerByMessage = new IdentityHashMap<>();
    private final Set<JsonNode> optionalSystem = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile Epoch epoch;
    private boolean mainFitted;

    ContextPlan(ObjectNode body, List<ContextFragment> fragments, Scope scope, int window, int outputReserve) {
        this(body, fragments, scope, window, outputReserve, null);
    }

    ContextPlan(ObjectNode body, List<ContextFragment> fragments, Scope scope, int window, int outputReserve,
                Stage stage) {
        this(body, fragments, scope, null, window, outputReserve, stage,
                stage == null ? STRATEGY : "personal-chat-p3/v1");
    }

    ContextPlan(ObjectNode body, List<ContextFragment> fragments, Scope scope, Window bounds,
                int window, int outputReserve, Stage stage) {
        this(body, fragments, scope, bounds, window, outputReserve, stage,
                stage == null ? STRATEGY : "personal-chat-p3/v1");
    }

    public static ContextPlan delegated(ObjectNode body, List<ContextFragment> fragments, Scope scope,
                                        int window, int outputReserve) {
        return new ContextPlan(body, fragments, scope, null, window, outputReserve, null,
                "personal-chat-subagent-p2/v1");
    }

    private ContextPlan(ObjectNode body, List<ContextFragment> fragments, Scope scope, Window bounds,
                        int window, int outputReserve, Stage stage, String strategy) {
        this.body = body;
        this.fragments = List.copyOf(fragments);
        this.scope = scope;
        this.bounds = bounds;
        this.strategy = strategy;
        this.contextWindow = window;
        this.outputReserve = Math.max(0L, outputReserve);
        this.safety = Math.max(1024L, (window + 19L) / 20L);
        this.stage = stage;
        if (stage != null && stage.cutoff() > 0) {
            this.epoch = new Epoch(stage.epochTurnId(), stage.turnId(), stage.cutoff(), "persisted");
        }
        ArrayNode messages = body.putArray("messages");
        for (ContextFragment fragment : fragments) {
            fragment.messages().forEach(messages::add);
            if (!fragment.layer().required()) {
                fragment.messages().stream().filter(message -> "system".equals(role(message)))
                        .forEach(optionalSystem::add);
            }
            indexReferences(fragment);
        }
    }

    private void indexReferences(ContextFragment fragment) {
        if (!fragment.source().startsWith("history/")) {
            return;
        }
        var user = fragment.refs().stream().filter(ref -> "user".equals(ref.kind())).findFirst();
        var reply = fragment.refs().stream().filter(ref -> "reply".equals(ref.kind())).findFirst();
        var interjections = fragment.refs().stream().filter(ref -> "interjection".equals(ref.kind())).iterator();
        var tools = fragment.refs().stream().filter(ref -> "tool".equals(ref.kind())).iterator();
        List<JsonNode> messages = fragment.messages();
        long trigger = userHistoryTrigger(fragment.source(), fragment.layer(), fragment.refs());
        if (stage != null && trigger > 0) {
            messages.forEach(message -> historyTriggerByMessage.put(message, trigger));
        }
        for (int i = 0; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            Ref ref = null;
            if (i == 0 && user.isPresent() && "user".equals(role(message))) {
                ref = user.get();
            } else if (i == messages.size() - 1 && reply.isPresent() && "assistant".equals(role(message))
                    && !hasCalls(message)) {
                ref = reply.get();
            } else if ("tool".equals(role(message)) && tools.hasNext()) {
                ref = tools.next();
            } else if (("user".equals(role(message)) || "assistant".equals(role(message))) && !hasCalls(message)
                    && message.path("content").isTextual() && interjections.hasNext()) {
                ref = interjections.next();
            }
            if (ref != null) {
                sourceByMessage.put(message, List.of(ref.value()));
            }
        }
    }

    public ObjectNode body() {
        return body;
    }

    public List<String> references(JsonNode canonicalMessage) {
        return sourceByMessage.getOrDefault(canonicalMessage, List.of());
    }

    public Set<String> delegatedReportReferences() {
        Set<String> reports = new HashSet<>();
        for (ContextFragment fragment : fragments) {
            if ("history/SUBAGENT_REPORT".equals(fragment.source())) {
                fragment.refs().forEach(ref -> reports.add(ref.value()));
            }
        }
        return Set.copyOf(reports);
    }

    /** The interim prompt incorporates only mandatory system fragments from the main plan. */
    public boolean requiredForInterim(JsonNode canonicalMessage) {
        return !optionalSystem.contains(canonicalMessage);
    }

    /** Interim replies use the same history boundary and project the continuation separately. */
    public boolean includedInInterim(JsonNode canonicalMessage) {
        if (stage == null) {
            return true;
        }
        Epoch active = epoch;
        long trigger = historyTriggerByMessage.getOrDefault(canonicalMessage, 0L);
        return !covered(active, trigger)
                && stage.continuation().messages().stream().noneMatch(message -> message == canonicalMessage);
    }

    public Fitted fit(ObjectNode canonical) {
        return fit(canonical, (result, name) -> null, Map.of());
    }

    public Fitted fit(ObjectNode canonical, BiFunction<JsonNode, String, String> archive,
                      Map<JsonNode, List<String>> extraRefs) {
        List<Draft> drafts = new ArrayList<>();
        Epoch active = epoch;
        int index = 0;
        for (ContextFragment fragment : fragments) {
            List<Ref> refs = new ArrayList<>(fragment.refs());
            for (JsonNode message : fragment.messages()) {
                addRefs(refs, extraRefs.get(message));
            }
            Draft draft = new Draft(fragment.layer(), fragment.source(), fragment.trust(), refs, fragment.messages());
            if (covered(active, draft.userHistoryTrigger())) {
                draft.omit("history_epoch");
            } else if (draft.isHistory()) {
                draft.stripHistoricalTools(false);
            }
            drafts.add(draft);
            index += fragment.messages().size();
        }
        List<JsonNode> messages = new ArrayList<>();
        canonical.path("messages").forEach(messages::add);
        addLiveDrafts(drafts, messages, index, extraRefs);
        boolean propose = stage != null && stage.currentTurnId() > 0 && !mainFitted;
        Fitted fitted = fit(canonical, drafts, archive, strategy, active, propose);
        mainFitted = true;
        epoch = fitted.selection().epoch();
        return fitted;
    }

    /** Interim replies use a separate no-tools projection with its own required-input boundary. */
    public Fitted fitInterim(ObjectNode interim, int initialUserIndex, Map<JsonNode, List<String>> extraRefs) {
        List<Draft> drafts = new ArrayList<>();
        Epoch active = epoch;
        int index = 0;
        for (JsonNode message : interim.path("messages")) {
            if (stage != null && index == initialUserIndex) {
                ContextFragment continuation = stage.continuation();
                drafts.add(new Draft(continuation.layer(), continuation.source(), continuation.trust(),
                        new ArrayList<>(continuation.refs()), continuation.messages()));
            }
            String role = role(message);
            boolean system = "system".equals(role);
            boolean current = "user".equals(role) && index >= initialUserIndex;
            List<Ref> refs = new ArrayList<>();
            addRefs(refs, extraRefs.get(message));
            drafts.add(new Draft(system ? Layer.INSTRUCTION : current ? Layer.TASK : Layer.WORK,
                    system ? "interim_instructions" : current ? "current_input" : "interim_dialogue",
                    system ? Trust.INSTRUCTION : Trust.TRANSCRIPT, refs, List.of(message)));
            index++;
        }
        return fit(interim, drafts, (result, name) -> null, strategy + "/interim", active, false);
    }

    private static void addLiveDrafts(List<Draft> drafts, List<JsonNode> messages, int index,
                                      Map<JsonNode, List<String>> extraRefs) {
        while (index < messages.size()) {
            JsonNode message = messages.get(index);
            List<Ref> refs = new ArrayList<>();
            addRefs(refs, extraRefs.get(message));
            if (hasCalls(message)) {
                Exchange exchange = exchangeAt(messages, index);
                for (int i = index + 1; i < exchange.end(); i++) {
                    addRefs(refs, extraRefs.get(messages.get(i)));
                }
                Draft draft = new Draft(Layer.WORK, "live_tool_exchange", Trust.DATA,
                        refs, messages.subList(index, exchange.end()));
                draft.toolNames = exchange.names();
                if (!exchange.complete()) {
                    draft.omit("incomplete_tool_exchange");
                }
                drafts.add(draft);
                index = exchange.end();
            } else {
                boolean user = "user".equals(role(message));
                Draft draft = new Draft(user ? Layer.TASK : Layer.WORK,
                        user ? "current_input" : "live_dialogue", Trust.TRANSCRIPT, refs, List.of(message));
                if ("tool".equals(role(message))) {
                    draft.omit("orphan_tool_result");
                }
                drafts.add(draft);
                index++;
            }
        }
    }

    private Fitted fit(ObjectNode canonical, List<Draft> drafts, BiFunction<JsonNode, String, String> archive,
                       String strategy, Epoch active, boolean propose) {
        ObjectNode outbound = wireSkeleton(canonical);
        long reserve = Math.max(outputReserve, Math.max(outbound.path("max_tokens").asLong(),
                outbound.path("max_completion_tokens").asLong()));
        long allowance = contextWindow - reserve - safety;
        long historyWatermark = watermark(drafts);
        int remainingImages = RECENT_IMAGES;
        for (Draft draft : drafts.reversed()) {
            if (!draft.omitted) {
                remainingImages = draft.retainImages(outbound, remainingImages);
            }
        }
        /* R5 工作窗口：不等预算告警，先按当前段的已完成 Turn 数与历史正文估算收敛；
           被裁的轮次进入证据索引（R6），授权回读不受影响。 */
        Set<String> evidenceIndex = applyWorkingWindow(drafts, active);
        long used = estimateRequest(outbound) + drafts.stream().mapToLong(Draft::cost).sum();
        if (used > allowance) {
            ObjectNode required = outbound.deepCopy();
            render(required, drafts.stream().filter(draft -> draft.layer.required()).toList());
            checkBudget(estimateRequest(required), allowance);
        }
        List<Draft> candidates = propose ? epochCandidates(drafts) : List.of();
        long highWater = allowance * 85 / 100;
        boolean canAdvance = !candidates.isEmpty() && used > highWater
                && estimateRequest(outbound) + drafts.stream().filter(draft -> draft.layer.required())
                        .mapToLong(Draft::cost).sum() <= highWater;
        long cleanupTarget = canAdvance ? highWater : allowance;
        for (Draft draft : drafts) {
            if (used > allowance && draft.layer == Layer.EVIDENCE && !draft.omitted) {
                used -= draft.omit("evidence_over_budget");
            }
        }
        for (Draft draft : drafts) {
            if (used > cleanupTarget && draft.isHistory() && !draft.layer.required() && !draft.omitted) {
                long before = draft.cost();
                draft.stripHistoricalTools(true);
                used -= before - draft.cost();
                // R6：历史工具交换被裁时保留可定位引用，进证据索引。
                draft.removedToolRefValues().stream().filter(value -> !evidenceIndex.contains(value))
                        .forEach(evidenceIndex::add);
            }
        }
        if (canAdvance && used > highWater) {
            long cutoff = 0;
            long lowWater = allowance * 65 / 100;
            for (Draft candidate : candidates) {
                if (used <= lowWater) {
                    break;
                }
                used -= candidate.omit("history_epoch");
                cutoff = candidate.userHistoryTrigger();
            }
            active = new Epoch(stage.currentTurnId(), stage.turnId(), cutoff, "history_high_watermark");
        }
        Draft latestTool = drafts.stream().filter(Draft::isLiveTool).filter(draft -> !draft.omitted)
                .reduce((previous, next) -> next).orElse(null);
        Set<String> hints = new LinkedHashSet<>();
        for (Draft draft : drafts) {
            if (used <= allowance) {
                break;
            }
            if (!draft.layer.required() && draft != latestTool && !draft.omitted) {
                if (draft.isLiveTool()) {
                    draft.archiveTools(archive).forEach(hints::add);
                }
                used -= draft.omit(draft.isLiveTool() ? "older_tool_exchange_over_budget" : "work_over_budget");
            }
        }
        if (used > allowance && latestTool != null) {
            long before = latestTool.cost();
            latestTool.clipToolResults(archive, TOOL_PREVIEW_CHARS);
            used -= before - latestTool.cost();
            if (used > allowance) {
                long withPreview = latestTool.cost();
                latestTool.clipToolResults(archive, 0);
                used -= withPreview - latestTool.cost();
            }
        }
        if (used < allowance && !hints.isEmpty()) {
            addArchiveHints(outbound, drafts, hints, allowance - used);
        }
        if (used < allowance && !evidenceIndex.isEmpty()) {
            addEvidenceIndex(outbound, drafts, evidenceIndex, allowance - used);
        }
        List<Entry> selected = render(outbound, drafts);
        long estimated = estimateRequest(outbound);
        checkBudget(estimated, allowance);
        List<Entry> omissions = drafts.stream().flatMap(draft -> draft.omissions(scope).stream()).toList();
        List<Entry> omitted = omissions.subList(Math.max(0, omissions.size() - OMISSION_LIMIT), omissions.size());
        Long sceneId = bounds == null || bounds.sceneId() <= 0 ? null : bounds.sceneId();
        Long scopeRevision = bounds == null || bounds.scopeRevision() <= 0 ? null : bounds.scopeRevision();
        Selection selection = new Selection(strategy, contextWindow, reserve, safety, estimated,
                historyWatermark, sceneId, scopeRevision, selected, omitted, omissions.size(), active);
        trace(strategy, selection);
        return new Fitted(outbound, selection);
    }

    /** 每轮装配的调度结果：窗口、选中/裁剪数量、裁剪原因、Epoch、任务段与授权代号。 */
    private void trace(String strategy, Selection selection) {
        if (!log.isInfoEnabled()) {
            return;
        }
        log.info("[ctx] fit session={} turn={} strategy={} est={} window={} reserve={} safety={} "
                        + "selected={} omitted={} reasons={} epoch={} scene={} rev={} watermark={}",
                scope.sessionId(), stage == null ? 0 : stage.currentTurnId(), strategy,
                selection.estimatedInputTokens(), selection.contextWindow(), selection.reservedOutputTokens(),
                selection.safetyTokens(), selection.selected().size(), selection.omittedCount(),
                reasonCounts(selection.omitted()),
                selection.epoch() == null ? "none"
                        : selection.epoch().id() + "/" + selection.epoch().reason(),
                selection.sceneId() == null ? 0 : selection.sceneId(),
                selection.scopeRevision() == null ? 0 : selection.scopeRevision(),
                selection.historyWatermark());
        if (log.isDebugEnabled()) {
            for (Entry entry : selection.omitted()) {
                log.debug("[ctx] fit.omit session={} source={} reasons={} refs={}",
                        scope.sessionId(), entry.source(), entry.reasons(),
                        entry.refs().stream().map(Ref::value).toList());
            }
        }
    }

    private static String reasonCounts(List<Entry> omitted) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : omitted) {
            for (String reason : entry.reasons()) {
                counts.merge(reason, 1, Integer::sum);
            }
        }
        return counts.isEmpty() ? "{}" : counts.toString();
    }

    /**
     * R5：默认工作窗口。当前段的已完成用户 Turn 至多 {@code maxTurns} 个，历史正文估算另受
     * {@code tokenCap} 限制，取更紧的一项；从最早的轮次开始退出默认回放。低水位为目标，硬预算仍是
     * 最后的请求准入（R5.4）。
     */
    private Set<String> applyWorkingWindow(List<Draft> drafts, Epoch active) {
        if (bounds == null || !bounds.enabled()) {
            return Set.of();
        }
        List<Draft> history = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.isHistory() && !draft.omitted && draft.userHistoryTrigger() > 0
                    && !covered(active, draft.userHistoryTrigger())) {
                history.add(draft);
            }
        }
        long estimate = history.stream().mapToLong(Draft::cost).sum();
        Set<String> dropped = new LinkedHashSet<>();
        for (int i = 0; i < history.size(); i++) {
            Draft draft = history.get(i);
            boolean overTurns = history.size() - i > bounds.maxTurns();
            if (!overTurns && estimate <= bounds.tokenCap()) {
                break;
            }
            estimate -= draft.omit("working_window");
            String ref = draft.userRefValue();
            if (ref != null) {
                dropped.add(ref);
            }
        }
        return dropped;
    }

    /** R6：给退出默认回放的轮次与历史工具一个简短可定位索引（首批至多 8 条），不回放旧正文。 */
    private static void addEvidenceIndex(ObjectNode outbound, List<Draft> drafts, Set<String> refs,
                                         long available) {
        List<String> listed = refs.stream().filter(value -> value != null && !value.isBlank())
                .limit(ARCHIVE_HINTS).toList();
        if (listed.isEmpty()) {
            return;
        }
        JsonNode note = outbound.objectNode().put("role", "user").put("content",
                "【历史证据索引】以下较早的过程材料已退出默认回放，可用 read_context_source 按引用分页读取："
                        + String.join("、", listed));
        if (estimateNode(note) <= available) {
            Draft hint = new Draft(Layer.EVIDENCE, "history_index", Trust.DATA,
                    listed.stream().map(ref -> new Ref("source", ref)).toList(), List.of(note));
            int insertion = 0;
            while (insertion < drafts.size() && !drafts.get(insertion).layer.required()) {
                insertion++;
            }
            drafts.add(insertion, hint);
        }
    }

    private List<Draft> epochCandidates(List<Draft> drafts) {
        List<Draft> history = drafts.stream().filter(draft -> !draft.omitted && draft.userHistoryTrigger() > 0).toList();
        int end = Math.max(0, history.size() - Math.max(2, stage.keepTurns()));
        return history.subList(0, end).stream()
                .filter(draft -> draft.userHistoryTrigger() <= stage.stageTriggerId()).toList();
    }

    private static boolean covered(Epoch epoch, long trigger) {
        return epoch != null && trigger > 0 && trigger <= epoch.cutoff();
    }

    private static long userHistoryTrigger(String source, Layer layer, List<Ref> refs) {
        if (!USER_HISTORY.equals(source) || layer != Layer.WORK) {
            return 0;
        }
        return refs.stream().filter(ref -> "user".equals(ref.kind())).map(Ref::messageId)
                .filter(id -> id != null).findFirst().orElse(0L);
    }

    private void checkBudget(long estimated, long allowance) {
        if (estimated > allowance) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "上下文预算不足，无法容纳本轮输入、必要约束或当前工具往返（估算输入 " + estimated
                            + " token，可用 " + Math.max(0, allowance) + " token，模型窗口 "
                            + contextWindow + " token）");
        }
    }

    private static void addArchiveHints(ObjectNode outbound, List<Draft> drafts, Set<String> hints, long available) {
        List<String> recent = hints.stream().toList().reversed().stream().limit(ARCHIVE_HINTS).toList();
        JsonNode note = outbound.objectNode().put("role", "user").put("content",
                "【本轮工具记录索引】以下较早的工具输出可用 read_context_source 按引用分页读取："
                        + String.join("、", recent));
        if (estimateNode(note) <= available) {
            Draft hint = new Draft(Layer.EVIDENCE, "live_archive_index", Trust.DATA,
                    recent.stream().map(ref -> new Ref("artifact", ref)).toList(), List.of(note));
            int insertion = 0;
            while (insertion < drafts.size() && !drafts.get(insertion).layer.required()) {
                insertion++;
            }
            drafts.add(insertion, hint);
        }
    }

    private static ObjectNode wireSkeleton(ObjectNode canonical) {
        ObjectNode outbound = canonical.objectNode();
        canonical.fields().forEachRemaining(field -> {
            if (!"messages".equals(field.getKey())) {
                outbound.set(field.getKey(), field.getValue().deepCopy());
            }
        });
        outbound.putArray("messages");
        outbound.put("stream", true).putObject("stream_options").put("include_usage", true);
        return outbound;
    }

    private List<Entry> render(ObjectNode outbound, List<Draft> drafts) {
        ArrayNode messages = (ArrayNode) outbound.path("messages");
        StringBuilder system = new StringBuilder();
        List<Entry> selected = new ArrayList<>();
        for (Draft draft : drafts) {
            if (!draft.omitted && draft.isSystem()) {
                String text = draft.messages.getFirst().path("content").asText();
                if (!text.isBlank()) {
                    if (!system.isEmpty()) {
                        system.append(ContextBuilder.SYSTEM_SEPARATOR);
                    }
                    system.append(text);
                    selected.add(draft.entry(scope, 0, 1));
                }
            }
        }
        if (!system.isEmpty()) {
            messages.addObject().put("role", "system").put("content", system.toString());
        }
        for (Draft draft : drafts) {
            if (!draft.omitted && !draft.isSystem() && !draft.messages.isEmpty()) {
                int from = messages.size();
                draft.messages.forEach(message -> messages.add(wireMessage(message)));
                selected.add(draft.entry(scope, from, messages.size()));
            }
        }
        return List.copyOf(selected);
    }

    /** Count serialized field/structure overhead, all text, and bounded media estimates. */
    public static long estimateRequest(ObjectNode request) {
        return estimateNode(request);
    }

    private static JsonNode wireMessage(JsonNode message) {
        JsonNode copy = message.deepCopy();
        if (copy instanceof ObjectNode object) {
            object.remove("_minis_memory");
        }
        return copy;
    }

    private static long estimateNode(JsonNode node) {
        if ("image_url".equals(node.path("type").asText())) {
            return ToolOutput.estimateTokens(node) + 12;
        }
        if (node.isTextual()) {
            return 2 + Math.max(TokenUsageService.estimateTokens(node.textValue()), (node.textValue().length() + 2L) / 3L);
        }
        if (node.isValueNode()) {
            return 1 + TokenUsageService.estimateTokens(node.asText());
        }
        long estimate = 2;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                estimate += 3 + TokenUsageService.estimateTokens(field.getKey()) + estimateNode(field.getValue());
            }
        } else {
            for (JsonNode child : node) {
                estimate += 1 + estimateNode(child);
            }
        }
        return estimate;
    }

    private static long watermark(List<Draft> drafts) {
        long latest = 0;
        for (Draft draft : drafts) {
            for (Ref ref : draft.refs) {
                Long id = ref.messageId();
                if (id != null) {
                    latest = Math.max(latest, id);
                }
            }
        }
        return latest;
    }

    private static String role(JsonNode message) {
        return message.path("role").asText();
    }

    private static boolean hasCalls(JsonNode message) {
        return "assistant".equals(role(message)) && message.path("tool_calls").isArray()
                && !message.path("tool_calls").isEmpty();
    }

    private static boolean observation(JsonNode message, Set<String> ids) {
        if (!"user".equals(role(message)) || !message.path("content").isArray()) {
            return false;
        }
        String label = message.path("content").path(0).path("text").asText();
        return ids.stream().anyMatch(id -> label.startsWith("工具调用 " + id + " 返回的截图观察"));
    }

    private record Exchange(int end, Map<String, String> names, boolean complete) {}

    private static Exchange exchangeAt(List<JsonNode> messages, int start) {
        JsonNode calls = messages.get(start).path("tool_calls");
        Map<String, String> names = new HashMap<>();
        calls.forEach(call -> names.put(call.path("id").asText(), call.path("function").path("name").asText()));
        Set<String> waiting = new HashSet<>(names.keySet());
        boolean complete = !waiting.contains("") && waiting.size() == calls.size();
        int end = start + 1;
        while (end < messages.size() && "tool".equals(role(messages.get(end)))) {
            complete &= waiting.remove(messages.get(end++).path("tool_call_id").asText());
        }
        while (end < messages.size() && observation(messages.get(end), names.keySet())) {
            end++;
        }
        return new Exchange(end, names, complete && waiting.isEmpty());
    }

    private static void addRefs(List<Ref> refs, List<String> values) {
        if (values != null) {
            for (String value : values) {
                if (refs.stream().noneMatch(ref -> ref.value().equals(value))) {
                    int separator = value.indexOf(':');
                    refs.add(new Ref(separator > 0 ? value.substring(0, separator) : "source", value));
                }
            }
        }
    }

    private static final class Draft {
        private final Layer layer;
        private final String source;
        private final Trust trust;
        private final List<Ref> refs;
        private final Map<JsonNode, Ref> historicalTools = new IdentityHashMap<>();
        private final List<Ref> removedTools = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private final List<JsonNode> original;
        private List<JsonNode> messages;
        private Map<String, String> toolNames = Map.of();
        private boolean omitted;

        Draft(Layer layer, String source, Trust trust, List<Ref> refs, List<JsonNode> messages) {
            this.layer = layer;
            this.source = source;
            this.trust = trust;
            this.refs = refs;
            this.original = messages;
            this.messages = messages;
            if (isHistory()) {
                var tools = refs.stream().filter(ref -> "tool".equals(ref.kind())).iterator();
                for (JsonNode message : messages) {
                    if ("tool".equals(role(message)) && tools.hasNext()) {
                        historicalTools.put(message, tools.next());
                    }
                }
            }
        }

        boolean isHistory() { return source.startsWith("history/"); }
        long userHistoryTrigger() { return ContextPlan.userHistoryTrigger(source, layer, refs); }
        boolean isLiveTool() { return "live_tool_exchange".equals(source); }
        boolean isSystem() { return messages.size() == 1 && "system".equals(role(messages.getFirst())); }

        String userRefValue() {
            return refs.stream().filter(ref -> "user".equals(ref.kind())).map(Ref::value).findFirst().orElse(null);
        }

        List<String> removedToolRefValues() {
            return removedTools.stream().map(Ref::value).toList();
        }

        long cost() {
            if (omitted || isSystem() && messages.getFirst().path("content").asText().isBlank()) {
                return 0;
            }
            if (isSystem()) {
                // Independent framing conservatively covers the separator when all selected system text is merged.
                return 1 + estimateNode(JsonNodeFactory.instance.objectNode()
                        .put("role", "system").put("content", messages.getFirst().path("content").asText()));
            }
            return messages.stream().mapToLong(message -> 1 + estimateNode(message)).sum();
        }

        long omit(String reason) {
            long cost = cost();
            omitted = true;
            reasons.add(reason);
            return cost;
        }

        Entry entry(Scope scope, Integer from, Integer to) {
            return new Entry(source, scope, layer, trust, List.copyOf(refs), from, to, List.copyOf(reasons));
        }

        List<Entry> omissions(Scope scope) {
            List<Entry> entries = new ArrayList<>();
            if (omitted) {
                entries.add(entry(scope, null, null));
            }
            if (!removedTools.isEmpty()) {
                entries.add(new Entry(source + "/tools", scope, layer, Trust.DATA, List.copyOf(removedTools),
                        null, null, List.copyOf(reasons)));
            }
            return entries;
        }

        int retainImages(ObjectNode outbound, int remaining) {
            int images = 0;
            for (JsonNode message : messages) {
                for (JsonNode part : message.path("content")) {
                    if ("image_url".equals(part.path("type").asText())) {
                        images++;
                    }
                }
            }
            if (images > remaining) {
                ArrayNode copies = outbound.arrayNode();
                messages.forEach(message -> copies.add(message.deepCopy()));
                ToolOutput.retainRecentImages(copies, remaining);
                List<JsonNode> limited = new ArrayList<>(copies.size());
                for (int i = 0; i < copies.size(); i++) {
                    // Preserve source identity for all original non-media messages.
                    limited.add(messages.get(i).path("content").isArray() ? copies.get(i) : messages.get(i));
                }
                messages = limited;
                reasons.add("historical_media_limit");
            }
            return Math.max(0, remaining - images);
        }

        void stripHistoricalTools(boolean all) {
            List<JsonNode> kept = new ArrayList<>(messages.size());
            Set<String> removed = new LinkedHashSet<>();
            for (int index = 0; index < messages.size();) {
                JsonNode message = messages.get(index);
                if (hasCalls(message)) {
                    Exchange exchange = exchangeAt(messages, index);
                    List<JsonNode> batch = messages.subList(index, exchange.end());
                    if (!exchange.complete()) {
                        removed.add("incomplete_tool_exchange");
                    } else if (exchange.names().values().stream().anyMatch(name -> READBACK.equals(name)
                            || ReadSubagentRunTool.NAME.equals(name) || DelegateSubagentTool.NAME.equals(name)
                            || CONTINUATION_TOOL.equals(name) || RequestTodoCompletionTool.NAME.equals(name))) {
                        removed.add("source_readback_on_demand");
                    } else if (all) {
                        removed.add("historical_tool_exchange_over_budget");
                    } else {
                        kept.addAll(batch);
                        index = exchange.end();
                        continue;
                    }
                    batch.forEach(this::removeHistoricalRef);
                    index = exchange.end();
                } else {
                    if ("tool".equals(role(message))) {
                        removed.add("orphan_tool_result");
                        removeHistoricalRef(message);
                    } else {
                        kept.add(message);
                    }
                    index++;
                }
            }
            if (!removed.isEmpty()) {
                reasons.addAll(removed);
                messages = kept;
                omitted = kept.isEmpty();
            }
        }

        private void removeHistoricalRef(JsonNode message) {
            Ref ref = historicalTools.get(message);
            if (ref != null && refs.remove(ref)) {
                removedTools.add(ref);
            }
        }

        List<String> archiveTools(BiFunction<JsonNode, String, String> archive) {
            List<String> result = new ArrayList<>();
            for (JsonNode message : original) {
                String reference = archiveTool(message, archive);
                if (reference != null) {
                    result.add(reference);
                }
            }
            return result;
        }

        private String archiveTool(JsonNode message, BiFunction<JsonNode, String, String> archive) {
            if (!"tool".equals(role(message)) || !message.path("content").isTextual()) {
                return null;
            }
            String name = toolNames.get(message.path("tool_call_id").asText());
            if (name == null || READBACK.equals(name)) {
                return null;
            }
            String reference = archive.apply(message, name);
            if (reference != null) {
                var ref = new Ref("artifact", reference);
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
            return reference;
        }

        void clipToolResults(BiFunction<JsonNode, String, String> archive, int previewChars) {
            List<JsonNode> clipped = new ArrayList<>(original.size());
            boolean changed = false;
            for (JsonNode message : original) {
                if (observation(message, toolNames.keySet())) {
                    changed = true;
                    continue;
                }
                JsonNode text = message.path("content");
                if (!(message instanceof ObjectNode object) || !"tool".equals(role(message))
                        || !text.isTextual() || text.textValue().length() <= previewChars) {
                    clipped.add(message);
                    continue;
                }
                String reference = archiveTool(message, archive);
                boolean readback = READBACK.equals(toolNames.get(message.path("tool_call_id").asText()));
                String note = reference != null ? "〔原始工具输出可使用 read_context_source 读取 " + reference + "〕"
                        : readback ? "〔回读结果已裁剪，可用 read_context_source 缩小分页范围后再读〕" : null;
                if (note == null) {
                    clipped.add(message);
                    continue;
                }
                String value = text.textValue();
                int end = Math.min(value.length(), previewChars);
                if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
                    end--;
                }
                String compact = end > 0 ? value.substring(0, end) + "\n" + note : note;
                if (compact.length() < value.length()) {
                    clipped.add(object.deepCopy().put("content", compact));
                    changed = true;
                } else {
                    clipped.add(message);
                }
            }
            if (changed) {
                if (!reasons.contains("live_tool_result_over_budget")) {
                    reasons.add("live_tool_result_over_budget");
                }
                messages = clipped;
            }
        }
    }
}
