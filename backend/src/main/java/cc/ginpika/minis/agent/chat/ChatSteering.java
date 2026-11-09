package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.CallControl;
import cc.ginpika.minis.ai.memory.MemoryLiveContext;
import cc.ginpika.minis.ai.memory.MemorySnapshot;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Coordinates one turn's input, model cancellation, and replies while an already-started tool finishes. */
final class ChatSteering implements ModelLoop.Steering {
    private static final Logger log = LoggerFactory.getLogger(ChatSteering.class);
    private static final int MAX_PENDING = 20;
    private static final String INITIAL_PREFIX = "【本轮用户消息 1 · 初始请求】\n";
    private static final String SUPPLEMENT_GUIDANCE = """
            【处理本轮补充】
            编号的「本轮用户消息」共同构成本轮请求。后发消息默认补充前面的要求：新增问题与此前未答的问题一并处理，
            新增约束应用到相关事项。只有用户明确撤回、替换某项要求，或新旧要求确实冲突时，才以新的要求调整对应部分，
            其余事项继续完成。结束本轮前逐项检查，回应所有仍有效且未完成的请求。
            已发送的阶段回复只覆盖它实际回答的事项。被打断而撤下的临时输出不算已回复。
            """;
    private static final String INTERIM_PROMPT = """
            当前对话的主任务仍在执行工具。请用同一助手的身份，简短回应本次收到且可以先回答的追问或纠正。
            需要等待工具的事项留给主任务继续处理，已有完整答复的事项不必重复。
            可以说明已知进展，以及接下来会如何处理新要求。不要声称已经拿到仍在运行的工具结果，
            不要声称已撤销已执行的操作，也不要自行执行工具或替代主任务给出最终结论。
            """;

    private record Interjection(long messageId, String role, String content) {}
    record InterimRequest(ObjectNode body, int initialUserIndex, Map<JsonNode, List<String>> references,
                          MemorySnapshot memorySnapshot) {}

    private final long initialMessageId;
    private final String initialInput;
    private final ChatStream sink;
    private final AtomicBoolean stopped;
    private final BiFunction<String, String, Long> persist;
    private final List<Interjection> pending = new ArrayList<>();
    private final Map<JsonNode, List<String>> references = new IdentityHashMap<>();

    private boolean accepting = true;
    private long inputVersion;
    private long requestVersion;
    private CallControl request;
    private CallControl interim;
    private ObjectNode modelBody;
    private ObjectNode initialUserMessage;
    private ObjectNode lastUserMessage;
    private boolean supplementGuidanceInstalled;
    private InterimRequest toolContext;
    private ContextPlan contextPlan;
    private BiFunction<InterimRequest, CallControl, String> reply;
    private IntConsumer reset;

    ChatSteering(long initialMessageId, String initialInput, ChatStream sink, AtomicBoolean stopped,
                 BiFunction<String, String, Long> persist) {
        this.initialMessageId = initialMessageId;
        this.initialInput = initialInput;
        this.sink = sink;
        this.stopped = stopped;
        this.persist = persist;
    }

    void prepare(ObjectNode body, IntConsumer reset, ContextPlan contextPlan,
                 BiFunction<InterimRequest, CallControl, String> reply) {
        modelBody = body;
        this.reset = reset;
        this.contextPlan = contextPlan;
        this.reply = reply;
        ArrayNode messages = (ArrayNode) body.get("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof ObjectNode message
                    && "user".equals(message.path("role").asText())
                    && initialInput.equals(message.path("content").textValue())) {
                initialUserMessage = message;
                lastUserMessage = message;
                addReference(references, message, initialMessageId);
                break;
            }
        }
    }

    Map<JsonNode, List<String>> references() {
        return references;
    }

    synchronized long submit(long expectedMessageId, String actorId, String input) {
        if (!accepting || stopped.get() || expectedMessageId != initialMessageId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮回复已结束，请重新发送");
        }
        if (!Objects.equals(actorId, sink.actorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有本轮发起人可以补充消息");
        }
        if (pending.size() >= MAX_PENDING) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "待处理消息过多，请稍后发送");
        }
        long messageId = persist.apply("user", input);
        inputVersion++;
        pending.add(new Interjection(messageId, "user", "【本轮用户消息 " + (inputVersion + 1) + " · 补充】\n" + input));
        sink.steer(messageId, input);
        if (request != null) {
            request.cancel();
        }
        if (toolContext != null) {
            startInterim();
        }
        return messageId;
    }

    @Override
    public synchronized int applyInputs() {
        ensureRunning();
        if (pending.isEmpty()) {
            return 0;
        }
        ArrayNode messages = (ArrayNode) modelBody.get("messages");
        if (!supplementGuidanceInstalled) {
            installSupplementGuidance(messages);
        }
        lastUserMessage = appendPending(messages, lastUserMessage, references);
        int applied = pending.size();
        pending.clear();
        return applied;
    }

    private void installSupplementGuidance(ArrayNode messages) {
        if (messages.get(0) instanceof ObjectNode system && "system".equals(system.path("role").asText())) {
            system.put("content", system.path("content").asText() + "\n\n" + SUPPLEMENT_GUIDANCE);
        } else {
            messages.insert(0, messages.objectNode().put("role", "system").put("content", SUPPLEMENT_GUIDANCE));
        }
        if (initialUserMessage == null) {
            initialUserMessage = messages.addObject().put("role", "user");
            addReference(references, initialUserMessage, initialMessageId);
            lastUserMessage = initialUserMessage;
        }
        initialUserMessage.put("content", INITIAL_PREFIX + initialInput);
        supplementGuidanceInstalled = true;
    }

    /** Group consecutive user inputs without moving messages across replies or tool exchanges. */
    private ObjectNode appendPending(ArrayNode messages, ObjectNode previousUser, Map<JsonNode, List<String>> refs) {
        for (var input : pending) {
            if ("assistant".equals(input.role())) {
                var assistant = messages.addObject().put("role", "assistant").put("content", input.content())
                        .put("reasoning_content", "");
                addReference(refs, assistant, input.messageId());
                previousUser = null;
            } else if (previousUser != null && messages.get(messages.size() - 1) == previousUser) {
                previousUser.put("content", previousUser.path("content").asText() + "\n\n" + input.content());
            } else {
                previousUser = messages.addObject().put("role", "user").put("content", input.content());
            }
            if (previousUser != null) {
                addReference(refs, previousUser, input.messageId());
            }
        }
        return previousUser;
    }

    private static void addReference(Map<JsonNode, List<String>> refs, JsonNode node, long messageId) {
        var updated = new ArrayList<>(refs.getOrDefault(node, List.of()));
        updated.add("message:" + messageId);
        refs.put(node, List.copyOf(updated));
    }

    @Override
    public synchronized void requestStarted(CallControl control) {
        request = control;
        if (stopped.get() || !pending.isEmpty()) {
            control.cancel();
        } else {
            requestVersion = inputVersion;
        }
    }

    @Override
    public synchronized void requestFinished(CallControl control) {
        if (request == control) {
            request = null;
        }
    }

    @Override
    public synchronized boolean hasPending() {
        return stopped.get() || !pending.isEmpty();
    }

    @Override
    public synchronized boolean beginTools(List<JsonNode> calls) {
        ensureRunning();
        if (!pending.isEmpty()) {
            return false;
        }
        toolContext = interimContext(calls);
        return true;
    }

    @Override
    public synchronized void endTools() {
        toolContext = null;
        cancelInterim();
    }

    @Override
    public synchronized boolean tryFinish() {
        ensureRunning();
        if (!pending.isEmpty()) {
            return false;
        }
        accepting = false;
        return true;
    }

    @Override
    public void resetOutput(int round) {
        ensureRunning();
        reset.accept(round);
    }

    synchronized void close() {
        accepting = false;
        if (request != null) {
            request.cancel();
        }
        endTools();
    }

    synchronized boolean stop(long expectedMessageId, String actorId) {
        if (!accepting || expectedMessageId != initialMessageId || !Objects.equals(actorId, sink.actorId())) {
            return false;
        }
        stopped.set(true);
        close();
        return true;
    }

    boolean stopped() {
        return stopped.get();
    }

    synchronized boolean currentOutput() {
        return requestVersion == inputVersion;
    }

    private void ensureRunning() {
        if (stopped.get()) {
            throw new ChatCancelledException();
        }
    }

    /** Read the last completed conversation state on the work thread, before the tool batch changes it. */
    private InterimRequest interimContext(List<JsonNode> calls) {
        ObjectNode body = modelBody.objectNode().put("model", modelBody.path("model").asText());
        var messages = body.putArray("messages");
        var system = messages.addObject().put("role", "system");
        Map<JsonNode, List<String>> refs = new IdentityHashMap<>();
        int initialUserIndex = -1;
        StringBuilder prompt = new StringBuilder();
        for (JsonNode message : modelBody.path("messages")) {
            if (contextPlan != null && !contextPlan.includedInInterim(message)) {
                continue;
            }
            JsonNode content = message.path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                continue;
            }
            switch (message.path("role").asText()) {
                case "system" -> {
                    if (contextPlan == null || contextPlan.requiredForInterim(message)) {
                        prompt.append(content.asText()).append("\n\n");
                    }
                }
                case "user", "assistant" -> {
                    if (message == initialUserMessage) { initialUserIndex = messages.size(); }
                    String text = message == initialUserMessage && !supplementGuidanceInstalled
                            ? INITIAL_PREFIX + initialInput : content.asText();
                    var copy = messages.addObject().put("role", message.path("role").asText()).put("content", text);
                    var ids = references.get(message);
                    if (ids == null && contextPlan != null) { ids = contextPlan.references(message); }
                    if (ids != null && !ids.isEmpty()) { refs.put(copy, ids); }
                }
                default -> { }
            }
        }
        if (initialUserIndex < 0) {
            initialUserIndex = messages.size();
            var fallback = messages.addObject().put("role", "user").put("content", INITIAL_PREFIX + initialInput);
            addReference(refs, fallback, initialMessageId);
        }
        if (!supplementGuidanceInstalled) {
            prompt.append(SUPPLEMENT_GUIDANCE).append("\n\n");
        }
        List<String> names = calls.stream().map(call -> call.path("function").path("name").asText())
                .filter(name -> !name.isBlank()).distinct().toList();
        system.put("content", prompt.append(INTERIM_PROMPT).append("当前工具批次：")
                .append(String.join("、", names)).toString());
        return new InterimRequest(body, initialUserIndex, refs,
                contextPlan == null ? null : MemoryLiveContext.current());
    }

    private void startInterim() {
        cancelInterim();
        ObjectNode body = toolContext.body().deepCopy();
        var messages = (ArrayNode) body.get("messages");
        Map<JsonNode, List<String>> refs = new IdentityHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            var ids = toolContext.references().get(toolContext.body().path("messages").get(i));
            if (ids != null) { refs.put(messages.get(i), ids); }
        }
        appendPending(messages, null, refs);
        var request = new InterimRequest(body, toolContext.initialUserIndex(), refs, toolContext.memorySnapshot());
        CallControl control = new CallControl();
        interim = control;
        Thread.startVirtualThread(() -> answerInterim(request, control));
    }

    private void answerInterim(InterimRequest body, CallControl control) {
        try {
            String content = reply.apply(body, control);
            synchronized (this) {
                if (interim != control || !accepting || stopped.get()) {
                    return;
                }
                interim = null;
                if (content == null || content.isBlank()) {
                    return;
                }
                long messageId = persist.apply("assistant", content);
                pending.add(new Interjection(messageId, "assistant", content));
                sink.interim(messageId, content);
            }
        } catch (RuntimeException error) {
            if (!control.cancelled()) {
                log.debug("工作期间的即时回复失败，补充消息仍由主流程处理", error);
            }
        }
    }

    private void cancelInterim() {
        if (interim != null) {
            interim.cancel();
            interim = null;
        }
    }
}
