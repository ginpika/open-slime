package cc.ginpika.minis.ai.runtime;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Shared model/tool protocol loop. Business state, memory and completion policy belong to callers. */
@Component
public class ModelLoop {
    private final OpenAiChatClient client;
    public ModelLoop(OpenAiChatClient client) { this.client = client; }

    public enum End { ANSWERED, EMPTY_CONTENT }
    public record Result(String content, String rawResponse, End end, int round, JsonNode message) {
        public Result(String content,String rawResponse,End end,int round){this(content,rawResponse,end,round,null);}
    }
    public interface Dispatch { List<String> execute(List<JsonNode> calls); }
    public interface Hooks {
        default void beforeRound(int round, ObjectNode body) {}
        default OpenAiChatClient.DeltaListener deltas(int round) { return null; }
        default void response(int round, ObjectNode body, OpenAiChatClient.RawResult raw, boolean tools) {}
        /** Caller-controlled recovery before any assistant tool calls are accepted or executed. */
        default boolean continueAfterResponse(int round, ObjectNode body, OpenAiChatClient.RawResult raw) { return false; }
        default void rejected(int round, ObjectNode body, RuntimeException error, boolean tools) {}
        default void reasoning(int round, String text) {}
        default void requested(int round, JsonNode call) {}
        default void message(JsonNode message) {}
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
    public record Spec(ModelTarget target, String source, ObjectNode body,
                       boolean allowReasoningAnswer, Dispatch dispatch, Hooks hooks,
                       OpenAiChatClient.CallControl control) {}

    public Result run(Spec spec) {
        ArrayNode messages = (ArrayNode) spec.body().get("messages");
        Hooks hooks = spec.hooks() == null ? new Hooks() {} : spec.hooks();
        String lastRaw = null;
        for (int round = 0; ; round++) {
            checkCancelled(spec);
            hooks.beforeRound(round, spec.body());
            ObjectNode body = spec.body();
            boolean tools = body.path("tools").isArray() && !body.path("tools").isEmpty();
            OpenAiChatClient.RawResult raw;
            try {
                raw = request(spec,body,hooks,round);
            } catch (RuntimeException error) {
                hooks.rejected(round, body, error, tools);
                throw error;
            }
            lastRaw = raw.rawResponse();
            hooks.response(round, body, raw, tools);
            if (hooks.continueAfterResponse(round, body, raw)) { continue; }
            JsonNode message = raw.message();
            String reasoning = message.path("reasoning_content").asText("");
            if (!reasoning.isBlank()) { hooks.reasoning(round, reasoning); }
            JsonNode requested = message.path("tool_calls");
            if (!requested.isArray() || requested.isEmpty()) {
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
            checkCancelled(spec);
            hooks.beforeTools();
            ObjectNode assistant = assistant(message);
            messages.add(assistant);
            hooks.message(assistant);
            List<JsonNode> calls = new ArrayList<>();
            requested.forEach(calls::add);
            for (JsonNode call : calls) { hooks.requested(round, call); }
            List<String> results = spec.dispatch().execute(calls);
            if (results.size() != calls.size()) { throw new IllegalStateException("工具结果数量不匹配"); }
            for (int i = 0; i < calls.size(); i++) {
                ObjectNode result = messages.addObject().put("role", "tool")
                        .put("tool_call_id", calls.get(i).path("id").asText(""))
                        .put("content", results.get(i));
                hooks.message(result);
            }
            hooks.progress();
            String answer = hooks.answerAfterTools(round, calls, results);
            if (answer != null) { return new Result(answer, lastRaw, End.ANSWERED, round); }
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
    private OpenAiChatClient.RawResult request(Spec spec,ObjectNode body,Hooks hooks,int round){
        int maximum=Math.max(0,Math.min(10,hooks.maxRequestRetries()));
        for(int retry=0;;retry++){
            checkCancelled(spec);
            try{return spec.control()==null?client.chatRaw(spec.target(),body,hooks.deltas(round),spec.source()):client.chatRaw(spec.target(),body,hooks.deltas(round),spec.source(),spec.control());}
            catch(RuntimeException error){
                checkCancelled(spec);
                if(retry>=maximum || !(error instanceof OpenAiChatClient.ChatFailure failure) || !failure.retryable()){throw error;}
                long delay=Math.max(0,Math.min(30_000,hooks.retryDelayMillis(retry+1)));
                hooks.retrying(round,retry+1,maximum,delay,error);
                long remaining=delay;
                while(remaining>0){checkCancelled(spec);long step=Math.min(100,remaining);try{Thread.sleep(step);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("模型重试已停止");}remaining-=step;}
            }
        }
    }
    private static void checkCancelled(Spec spec){if(Thread.currentThread().isInterrupted() || spec.control()!=null && spec.control().cancelled()){throw new java.util.concurrent.CancellationException("模型请求已停止");}}
}
