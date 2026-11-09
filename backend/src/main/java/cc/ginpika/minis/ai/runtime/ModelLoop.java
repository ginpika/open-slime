package cc.ginpika.minis.ai.runtime;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.springframework.stereotype.Component;

/** Shared model/tool protocol loop. Business state, memory and completion policy belong to callers. */
@Component
public class ModelLoop {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final OpenAiChatClient client;
    public ModelLoop(OpenAiChatClient client) { this.client = client; }

    public enum End { ANSWERED, EMPTY_CONTENT }
    public record Result(String content, String rawResponse, End end, int round, JsonNode message) {
        public Result(String content,String rawResponse,End end,int round){this(content,rawResponse,end,round,null);}
    }
    public interface Dispatch { List<String> execute(List<JsonNode> calls); }
    /** Optional projection of the working transcript at the model request boundary. */
    public interface RequestPreparation {
        PreparedRequest prepare(int round, ObjectNode workingBody);
    }
    /** Before each model attempt the caller can validate the scope and observe actual transport submissions. */
    public record PreparedRequest(ObjectNode body, IntFunction<OpenAiChatClient.SubmissionListener> beforeAttempt) {}

    public interface Hooks {
        default void beforeRound(int round, ObjectNode body) {}
        default OpenAiChatClient.DeltaListener deltas(int round) { return null; }
        default void response(int round, ObjectNode body, OpenAiChatClient.RawResult raw, boolean tools) {}
        /** Caller-controlled recovery before any assistant tool calls are accepted or executed. */
        default boolean continueAfterResponse(int round, ObjectNode body, OpenAiChatClient.RawResult raw) { return false; }
        default void rejected(int round, ObjectNode body, RuntimeException error, boolean tools) {}
        default void interrupted(int round, ObjectNode body, boolean tools) {}
        default void reasoning(int round, String text) {}
        default void requested(int round, JsonNode call) {}
        default void message(JsonNode message) {}
        default void interjection() {}
        default void progress() {}
        default void fallback(int round, String text, String raw) {}
        default void beforeTools() {}
        /** Caller may finish the turn after a complete tool exchange, e.g. to wait for human input. */
        default String answerAfterTools(int round, List<JsonNode> calls, List<String> results) { return null; }
        default boolean continueAfterAnswer(int round, String content) { return false; }
        default boolean continueAfterAnswer(int round, JsonNode message, String content) { return continueAfterAnswer(round,content); }
        /** Retries apply only to the current model request, before any tools are dispatched. */
        default int maxRequestRetries(){return 0;}
        default long retryDelayMillis(int retry){return 500L << Math.min(4,retry-1);}
        default void retrying(int round,int retry,int maximum,long delayMillis,RuntimeException error){}
    }
    /** Optional input for a running turn. The caller synchronizes acceptance and lifecycle changes. */
    public interface Steering {
        /** Apply accepted inputs to the request body and return their count for replay. */
        int applyInputs();
        /** Register the handle, and cancel it if an input arrived after applyInputs(). */
        void requestStarted(OpenAiChatClient.CallControl control);
        void requestFinished(OpenAiChatClient.CallControl control);
        boolean hasPending();
        /** Claim the tool batch only if no input is waiting. */
        boolean beginTools(List<JsonNode> calls);
        void endTools();
        /** Seal the inbox only when empty. An accepted input must cause another model round. */
        boolean tryFinish();
        void resetOutput(int abandonedRound);
    }

    public record Spec(ModelTarget target, String source, ObjectNode body,
                       boolean allowReasoningAnswer, Dispatch dispatch, Hooks hooks,
                       OpenAiChatClient.CallControl control, Steering steering, RequestPreparation preparation) {
        public Spec(ModelTarget target, String source, ObjectNode body, boolean allowReasoningAnswer,
                    Dispatch dispatch, Hooks hooks, OpenAiChatClient.CallControl control) {
            this(target, source, body, allowReasoningAnswer, dispatch, hooks, control, null, null);
        }
        public Spec(ModelTarget target, String source, ObjectNode body, boolean allowReasoningAnswer,
                    Dispatch dispatch, Hooks hooks, OpenAiChatClient.CallControl control, Steering steering) {
            this(target, source, body, allowReasoningAnswer, dispatch, hooks, control, steering, null);
        }
    }

    public Result run(Spec spec) {
        ArrayNode messages = (ArrayNode) spec.body().get("messages");
        Hooks hooks = spec.hooks() == null ? new Hooks() {} : spec.hooks();
        Steering steering = spec.steering();
        String lastRaw = null;
        for (int round = 0; ; round++) {
            checkCancelled(spec.control());
            if (steering != null) {
                for (int remaining = steering.applyInputs(); remaining > 0; remaining--) {
                    hooks.interjection();
                }
            }
            if (spec.preparation() == null) {
                cc.ginpika.minis.ai.core.tool.ToolOutput.retainRecentImages(messages, 2);
            }
            hooks.beforeRound(round, spec.body());
            ObjectNode body = spec.body();
            OpenAiChatClient.RawResult raw = requestRound(spec, hooks, round);
            if (raw == null) {
                steering.resetOutput(round);
                continue;
            }
            lastRaw = raw.rawResponse();
            if (hooks.continueAfterResponse(round, body, raw)) { continue; }
            JsonNode message = raw.message();
            String reasoning = message.path("reasoning_content").asText("");
            if (steering == null && !reasoning.isBlank()) { hooks.reasoning(round, reasoning); }
            JsonNode requested = message.path("tool_calls");
            if (!requested.isArray() || requested.isEmpty()) {
                if (steering != null && !steering.tryFinish()) {
                    steering.resetOutput(round);
                    continue;
                }
                if (steering != null && !reasoning.isBlank()) { hooks.reasoning(round, reasoning); }
                String content = message.path("content").asText("");
                if (content.isBlank()) {
                    if (reasoning.isBlank() || !spec.allowReasoningAnswer()) { return new Result(null, lastRaw, End.EMPTY_CONTENT, round); }
                    hooks.fallback(round, reasoning, lastRaw);
                    content = reasoning;
                }
                ObjectNode answer=assistant(message);answer.put("content",content);
                if (hooks.continueAfterAnswer(round, answer, content)) { continue; }
                return new Result(content, lastRaw, End.ANSWERED, round,answer);
            }
            checkCancelled(spec.control());
            hooks.beforeTools();
            List<JsonNode> calls = new ArrayList<>();
            requested.forEach(calls::add);
            if (steering != null && !steering.beginTools(calls)) {
                steering.resetOutput(round);
                continue;
            }
            List<String> results;
            try {
                if (steering != null && !reasoning.isBlank()) { hooks.reasoning(round, reasoning); }
                ObjectNode assistant = assistant(message);
                messages.add(assistant);
                hooks.message(assistant);
                for (JsonNode call : calls) { hooks.requested(round, call); }
                results = spec.dispatch().execute(calls);
            } finally {
                if (steering != null) { steering.endTools(); }
            }
            if (results.size() != calls.size()) { throw new IllegalStateException("工具结果数量不匹配"); }
            List<ObjectNode> observations = new ArrayList<>();
            for (int i = 0; i < calls.size(); i++) {
                var output = cc.ginpika.minis.ai.core.tool.ToolOutput.decode(results.get(i));
                ObjectNode result = messages.addObject().put("role", "tool")
                        .put("tool_call_id", calls.get(i).path("id").asText(""))
                        .put("content", output.text());
                hooks.message(result);
                if (!output.images().isEmpty()) observations.add(cc.ginpika.minis.ai.core.tool.ToolOutput.observation(
                        JSON, calls.get(i).path("id").asText(), output.images()));
            }
            for (var observation : observations) { messages.add(observation); hooks.message(observation); }
            hooks.progress();
            String answer = hooks.answerAfterTools(round, calls, results);
            if (answer != null && (steering == null || steering.tryFinish())) {
                return new Result(answer, lastRaw, End.ANSWERED, round);
            }
        }
    }

    /** Preserve provider fields; an empty field represents absent historical reasoning, never invented text. */
    public static ObjectNode assistant(JsonNode message){
        ObjectNode result=(ObjectNode)message.deepCopy();result.put("role","assistant");
        if(!result.hasNonNull("reasoning_content")){result.put("reasoning_content","");}
        return result;
    }
    public static void ensureReasoning(ArrayNode messages){
        for(JsonNode node:messages){if("assistant".equals(node.path("role").asText()) && node instanceof ObjectNode object && !object.hasNonNull("reasoning_content")){object.put("reasoning_content","");}}
    }
    private OpenAiChatClient.RawResult requestRound(Spec spec, Hooks hooks, int round) {
        PreparedRequest prepared = null;
        Steering steering = spec.steering();
        OpenAiChatClient.CallControl control = steering == null ? spec.control() : new OpenAiChatClient.CallControl();
        OpenAiChatClient.RawResult raw;
        if (steering != null) { steering.requestStarted(control); }
        try {
            try {
                if (steering != null && control.cancelled()) { return null; }
                prepared = spec.preparation() == null ? new PreparedRequest(spec.body(), null)
                        : spec.preparation().prepare(round, spec.body());
                raw = request(spec, prepared, hooks, round, control);
            } finally {
                if (steering != null) { steering.requestFinished(control); }
            }
        } catch (RuntimeException error) {
            if (steering != null && (control.cancelled() || !steering.tryFinish())) {
                if (prepared != null) { hooks.interrupted(round, prepared.body(), hasTools(prepared.body())); }
                return null;
            }
            if (prepared != null) { hooks.rejected(round, prepared.body(), error, hasTools(prepared.body())); }
            throw error;
        }
        hooks.response(round, prepared.body(), raw, hasTools(prepared.body()));
        return steering != null && control.cancelled() ? null : raw;
    }

    private static boolean hasTools(ObjectNode body) {
        return body.path("tools").isArray() && !body.path("tools").isEmpty();
    }

    private OpenAiChatClient.RawResult request(Spec spec,PreparedRequest prepared,Hooks hooks,int round,
                                               OpenAiChatClient.CallControl control){
        int maximum=Math.max(0,Math.min(10,hooks.maxRequestRetries()));
        for(int retry=0;;retry++){
            checkCancelled(control);
            try {
                var listener = prepared.beforeAttempt() == null ? null : prepared.beforeAttempt().apply(retry);
                var deltas = hooks.deltas(round);
                if (listener != null) {
                    return client.chatRaw(spec.target(), prepared.body(), deltas, spec.source(), control, listener);
                }
                return control == null ? client.chatRaw(spec.target(), prepared.body(), deltas, spec.source())
                        : client.chatRaw(spec.target(), prepared.body(), deltas, spec.source(), control);
            }
            catch(RuntimeException error){
                checkCancelled(control);
                if(retry>=maximum || !(error instanceof OpenAiChatClient.ChatFailure failure) || !failure.retryable()){throw error;}
                long delay=Math.max(0,Math.min(30_000,hooks.retryDelayMillis(retry+1)));
                hooks.retrying(round,retry+1,maximum,delay,error);
                long remaining=delay;
                while(remaining>0){checkCancelled(control);long step=Math.min(100,remaining);try{Thread.sleep(step);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("模型重试已停止");}remaining-=step;}
            }
        }
    }
    private static void checkCancelled(OpenAiChatClient.CallControl control){if(Thread.currentThread().isInterrupted() || control!=null && control.cancelled()){throw new java.util.concurrent.CancellationException("模型请求已停止");}}
}
