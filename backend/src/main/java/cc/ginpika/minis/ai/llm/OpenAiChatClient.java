package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.config.LlmProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI Compatible 聊天客户端（POST {base}/chat/completions，SSE 流式）。
 * base-url 需指向带版本前缀的端点，如 https://api.openai.com/v1。
 *
 * <p>流式消费并聚合 delta，对调用方暴露与非流式一致的 message 节点
 * （content / reasoning_content / tool_calls），rawResponse 为重组后的等价 JSON。
 * 流式下 readTimeout 语义变为「相邻 chunk 的最大间隔」，含首包之前的等待（TTFT），
 * 因此聊天单独使用 minis.llm.chat-timeout-ms（embedding 仍用 minis.llm.timeout-ms）。
 *
 * <p>调试：请求期间每 1s 打印 tokens/s（按 SSE chunk 计数，约等于 token 数）。
 *
 * <p>用量：请求带 stream_options.include_usage，流末尾的 usage 尾包按来源标签记入 token_usage
 * （统计页数据源）；供应商未回传时按请求/输出文本估算兜底。
 *
 * <p>messages 由调用方组装（可选 system + 历史 + 本次 user）；本类只负责协议与解析。
 * 失败统一抛 IllegalStateException，由上层映射 502。
 */
@Component
public class OpenAiChatClient {

    /** 一条对话消息（role: system|user|assistant） */
    public record ChatMessage(String role, String content) {
    }

    /** 一次补全结果（content 为主回答；reasoningContent 为思考链，模型不支持时为 null） */
    public record Completion(String content, String reasoningContent, String rawResponse) {
    }

    /** Typed request failures let callers retry transient failures without repeating invalid requests. */
    public static final class ChatFailure extends IllegalStateException {
        private final int status;
        public ChatFailure(int status,String detail,Throwable cause){super("chat 调用失败: "+detail,cause);this.status=status;}
        public int status(){return status;}
        public boolean retryable(){return status==0 || status==408 || status==429 || status>=500 && status<=599;}
    }

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final LlmProps props;
    private final SettingsService settings;
    private final ObjectMapper objectMapper;
    private final TokenUsageService tokenUsage;
    private final OkHttpClient client;

    public OpenAiChatClient(LlmProps props, SettingsService settings, ObjectMapper objectMapper,
                            TokenUsageService tokenUsage) {
        this.props = props;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.tokenUsage = tokenUsage;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(props.getChatTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 默认聊天模型是否就绪（设置页模型或 .env 任一） */
    public boolean configured() {
        return settings.chatConfigured();
    }

    /** 当前默认聊天模型名（未配置为空串） */
    public String model() {
        return settings.chatModel().model();
    }

    public Completion chat(List<ChatMessage> messages) {
        return chat(settings.chatModel(), messages, TokenUsageService.SOURCE_CHAT);
    }

    public Completion chat(ModelTarget target, List<ChatMessage> messages) {
        return chat(target, messages, TokenUsageService.SOURCE_CHAT);
    }

    /** 一次性补全（默认聊天模型）+ token 统计来源标签 */
    public Completion chat(List<ChatMessage> messages, String source) {
        return chat(settings.chatModel(), messages, source);
    }

    /**
     * 一次性补全（非流式语义）；source 为 token 统计的来源标签
     * （见 {@link TokenUsageService} 的 SOURCE_* 常量）。
     */
    public Completion chat(ModelTarget target, List<ChatMessage> messages, String source) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", target.model());
        ArrayNode arr = body.putArray("messages");
        for (ChatMessage m : messages) {
            ObjectNode node = arr.addObject();
            node.put("role", m.role());
            node.put("content", m.content());
        }
        OpenAiChatClient.RawResult raw = chatRaw(target, body, null, source);
        JsonNode message = raw.message();
        String content = message.path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("响应无 choices[0].message.content");
        }
        JsonNode reasoning = message.get("reasoning_content");
        return new Completion(content,
                reasoning == null ? null : reasoning.asText(),
                raw.rawResponse());
    }

    /** chatRaw 的返回：message 节点 + 重组的等价响应体 */
    public record RawResult(JsonNode message, String rawResponse, String finishReason) {
        private static final ObjectMapper ENVELOPE_JSON = new ObjectMapper();
        public RawResult(JsonNode message, String rawResponse) {
            this(message, rawResponse, parseFinishReason(rawResponse));
        }
        private static String parseFinishReason(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try { return ENVELOPE_JSON.readTree(raw).path("choices").path(0).path("finish_reason").asText(null); }
            catch (IOException ignored) { return null; }
        }
    }

    /**
     * 流式增量监听：每解析到一段 delta 实时回调（供上层边收边推给前端）。
     * reasoning=思维链增量；content=正文增量，二者缺一为 null；实现须自行处理 null。
     */
    @FunctionalInterface
    public interface DeltaListener {
        void onDelta(String reasoning, String content);
    }

    /** A per-run cancellation handle; also cancels a request before its first response chunk. */
    public static final class CallControl {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<okhttp3.Call> active = new java.util.concurrent.atomic.AtomicReference<>();
        public void cancel() { cancelled.set(true); var call = active.get(); if (call != null) { call.cancel(); } }
        public boolean cancelled() { return cancelled.get(); }
        private void attach(okhttp3.Call call) { active.set(call); if (cancelled.get()) { call.cancel(); } }
        private void release(okhttp3.Call call) { active.compareAndSet(call, null); }
    }

    /** 低层补全（默认聊天模型）：调用方自组请求体 */
    public RawResult chatRaw(ObjectNode body) {
        return chatRaw(settings.chatModel(), body, null, TokenUsageService.SOURCE_CHAT);
    }

    /** 低层补全（默认聊天模型）+ token 统计来源标签 */
    public RawResult chatRaw(ObjectNode body, String source) {
        return chatRaw(settings.chatModel(), body, null, source);
    }

    /**
     * 低层补全：调用方自组请求体（messages / tools / tool_choice…），model 以运行时端点为准（target.model 覆盖），
     * 强制 stream=true 并聚合 SSE delta，返回 choices[0].message 等价节点
     * （可能含 tool_calls，content 可能为 null）。
     */
    public RawResult chatRaw(ModelTarget target, ObjectNode body) {
        return chatRaw(target, body, null, TokenUsageService.SOURCE_CHAT);
    }

    /** 同 {@link #chatRaw(ModelTarget, ObjectNode)}，额外把每段 delta 实时回调给 listener（可为 null） */
    public RawResult chatRaw(ModelTarget target, ObjectNode body, DeltaListener listener) {
        return chatRaw(target, body, listener, TokenUsageService.SOURCE_CHAT);
    }

    /**
     * 低层补全 + token 用量留痕：source 为统计来源标签（见 {@link TokenUsageService} 的 SOURCE_* 常量）。
     * 请求带 stream_options.include_usage，供应商回传 usage 时按真实值落库，否则本地估算兜底。
     */
    public RawResult chatRaw(ModelTarget target, ObjectNode body, DeltaListener listener,
                             String source) {
        return chatRaw(target, body, listener, source, null);
    }

    public RawResult chatRaw(ModelTarget target, ObjectNode body, DeltaListener listener,
                             String source, CallControl control) {
        if (!target.configured()) {
            throw new IllegalStateException("LLM 未配置：请在设置页添加模型，或在 .env 填写 "
                    + "LLM_BASE_URL / LLM_API_KEY / LLM_CHAT_MODEL");
        }
        body.put("model", target.model());
        body.put("stream", true);
        /* 要求流末尾补一个 usage 尾包（choices 为空），否则多数供应商不下发用量 */
        body.putObject("stream_options").put("include_usage", true);
        StreamRateLogger rate = new StreamRateLogger(target.model());
        StreamMeter meter = new StreamMeter();
        boolean attempted = false;
        boolean succeeded = false;
        try {
            Request request = new Request.Builder()
                    .url(join(target.baseUrl(), "chat/completions"))
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .header("Authorization", "Bearer " + target.apiKey())
                    .build();
            rate.start();
            okhttp3.Call call = client.newCall(request);
            if (control != null) { control.attach(call); }
            attempted = true;
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String respBody = response.body() == null ? "" : response.body().string();
                    throw new ChatFailure(response.code(),"HTTP " + response.code() + " " + abbreviate(respBody),null);
                }
                StreamOutcome outcome = readStream(response, rate, listener, meter);
                meter.usage = outcome.usage();
                succeeded = true;
                return outcome.raw();
            } finally {
                if (control != null) { control.release(call); }
            }
        } catch (ChatFailure error) {
            log.warn("chat 调用失败：model={} baseUrl={} error={}",target.model(),target.baseUrl(),error.getMessage());
            throw error;
        } catch (IOException e) {
            log.warn("chat 调用失败：model={} baseUrl={} error={}",
                    target.model(), target.baseUrl(), e.getMessage(), e);
            throw new ChatFailure(0,e.getMessage(),e);
        } finally {
            if (attempted) recordUsage(source, target, body, meter, succeeded,
                    control != null && control.cancelled());
            rate.finish();
        }
    }

    /** 落库本次用量：供应商回传 usage 用真实值，否则按请求/输出文本估算（estimated=true） */
    private void recordUsage(String source, ModelTarget target, ObjectNode requestBody,
                             StreamMeter meter, boolean succeeded, boolean cancelled) {
        TokenUsageService.Usage usage = meter.usage;
        if (usage == null) {
            long prompt = succeeded || meter.received ? estimateText(requestBody.path("messages"))
                    + estimateText(requestBody.path("tools")) : 0;
            usage = TokenUsageService.Usage.estimated(prompt,
                    TokenUsageService.estimateTokens(meter.text.toString()));
        }
        String status = succeeded ? "success" : cancelled || meter.received ? "interrupted" : "failed";
        tokenUsage.record(source, target, succeeded ? usage : usage.partial(), status);
    }

    private static final class StreamMeter {
        TokenUsageService.Usage usage;
        boolean received;
        final StringBuilder text = new StringBuilder();
        void observe(JsonNode node) {
            if (node.isTextual()) text.append(node.asText());
            else for (JsonNode child : node) observe(child);
        }
    }

    /** 递归累加节点内所有文本的估算 token 数（messages / tools / 聚合后的 message） */
    private static long estimateText(JsonNode node) {
        if (node != null && "image_url".equals(node.path("type").asText())) return 4096;
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        if (node.isTextual()) {
            return TokenUsageService.estimateTokens(node.asText());
        }
        long sum = 0;
        for (JsonNode child : node) {
            sum += estimateText(child);
        }
        return sum;
    }

    /** 流式读取结果：聚合后的 message + 供应商回传的 usage（未回传为 null） */
    private record StreamOutcome(RawResult raw, TokenUsageService.Usage usage) {
    }

    /** 逐行消费 SSE 流，聚合 delta 为 message 节点；listener 非空时把每段 delta 实时透出 */
    private StreamOutcome readStream(Response response, StreamRateLogger rate, DeltaListener listener, StreamMeter meter)
            throws IOException {
        ResponseBody respBody = response.body();
        if (respBody == null) {
            throw new IOException("空响应体");
        }
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        ArrayNode toolCalls = objectMapper.createArrayNode();
        /* 正文的流式解析器：把供应商漏转的原生工具调用标记（DSML 文本）就地识别出来。
           它逐字符推进，标记被 SSE 拆成任意片段也能正确拼接；块内字符一律不进入正文。 */
        DsmlToolCallParser dsml = new DsmlToolCallParser(objectMapper);
        boolean anyDelta = false;
        boolean completed = false;
        /* 真实用量（usage 尾包；部分供应商只在最后一包给全量，故取最后一个） */
        TokenUsageService.Usage usage = null;
        /* 结束原因（stop/length/tool_calls…）：排查「content 为空」是正常收尾还是被截断 */
        String finishReason = null;
        try (BufferedReader reader = new BufferedReader(respBody.charStream())) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (!line.startsWith("data:")) {
                    continue; // 跳过空行 / SSE 注释等
                }
                String payload = line.substring("data:".length()).strip();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    completed=true;
                    break;
                }
                JsonNode root = objectMapper.readTree(payload);
                TokenUsageService.Usage parsed = parseUsage(root.path("usage"));
                if (parsed != null) {
                    usage = parsed;
                    meter.usage = parsed;
                }
                JsonNode choice = root.path("choices").path(0);
                JsonNode fr = choice.get("finish_reason");
                if (fr != null && !fr.isNull() && !fr.asText().isEmpty()) {
                    finishReason = fr.asText();completed=true;
                }
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode()) {
                    continue; // 如 usage-only 尾包
                }
                anyDelta = true;
                meter.received = true;
                meter.observe(delta);
                boolean got = false;
                /* 正文先过解析器：它只放行「人话」，工具调用标记被扣下（不推给页面、不进正文） */
                String shown = dsml.feed(delta.path("content").asText(null));
                if (!shown.isEmpty()) {
                    content.append(shown);
                    got = true;
                    if (listener != null) {
                        listener.onDelta(null, shown);
                    }
                }
                String r = delta.path("reasoning_content").asText(null);
                if (r != null && !r.isEmpty()) {
                    reasoning.append(r);
                    got = true;
                    if (listener != null) {
                        listener.onDelta(r, null);
                    }
                }
                got |= mergeToolCalls(delta.path("tool_calls"), toolCalls);
                if (got) {
                    rate.addToken();
                }
            }
        }
        if (!anyDelta) {
            throw new IOException("流式响应无 delta 数据");
        }
        if(!completed){throw new IOException("流式响应在完成前断开；未接收完整模型结果");}
        /* 收尾：把解析器扣下的调用（含跨 chunk 拼好的标记）交出来 */
        DsmlToolCallParser.Result tail = dsml.finish();
        if (!tail.visible().isEmpty()) {
            content.append(tail.visible());
            if (listener != null) {
                listener.onDelta(null, tail.visible());
            }
        }
        /* 检测逻辑：结构化 tool_calls 为空 + 正文里解析出了 DSML 工具调用 → 注入 assistant.tool_calls，
           并把结束原因从 stop 改判为 tool_calls（Hermes / Cherry Studio 同款：不然下游当普通回答，工具不会跑） */
        if (toolCalls.isEmpty() && !tail.calls().isEmpty()) {
            tail.calls().forEach(toolCalls::add);
            if (finishReason == null || "stop".equals(finishReason)) {
                finishReason = "tool_calls";
            }
            log.warn("正文里检测到原生工具调用标记（DSML）：已解析为 {} 次工具调用，finish_reason 改判为 tool_calls",
                    toolCalls.size());
        }
        String text = content.toString();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "assistant");
        if (!text.isEmpty()) {
            message.put("content", text);
        } else {
            message.putNull("content");
        }
        if (reasoning.length() > 0) {
            message.put("reasoning_content", reasoning.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }
        ObjectNode raw = objectMapper.createObjectNode();
        ObjectNode rawChoice = raw.putArray("choices").addObject();
        rawChoice.set("message", message);
        if (finishReason != null) {
            rawChoice.put("finish_reason", finishReason);
        }
        if (usage != null) {
            raw.putObject("usage").put("prompt_tokens",usage.promptTokens())
                    .put("completion_tokens",usage.completionTokens()).put("total_tokens",usage.totalTokens());
        }
        return new StreamOutcome(new RawResult(message, raw.toString(), finishReason), usage);
    }

    /** 解析 usage 尾包，保留实测/部分实测及缓存、推理明细；缺失字段不视为真实的零。 */
    private static TokenUsageService.Usage parseUsage(JsonNode usage) {
        return UsageParser.parse(usage, false);
    }

    /** 按下标合并流式 tool_calls delta（id/name 覆盖、arguments 追加），返回是否有内容 */
    private boolean mergeToolCalls(JsonNode deltas, ArrayNode merged) {
        if (!deltas.isArray() || deltas.isEmpty()) {
            return false;
        }
        for (JsonNode d : deltas) {
            int idx = d.path("index").asInt(merged.size());
            while (merged.size() <= idx) {
                merged.addObject().put("type", "function")
                        .putObject("function").put("arguments", "");
            }
            ObjectNode tc = (ObjectNode) merged.get(idx);
            ObjectNode fn = (ObjectNode) tc.path("function");
            String id = d.path("id").asText("");
            if (!id.isEmpty()) {
                tc.put("id", id);
            }
            String name = d.path("function").path("name").asText("");
            if (!name.isEmpty()) {
                fn.put("name", name);
            }
            fn.put("arguments", fn.path("arguments").asText("")
                    + d.path("function").path("arguments").asText(""));
        }
        return true;
    }

    /**
     * 流式速率调试日志：请求期间每 1s 打印瞬时/平均 tokens/s
     * （按携带内容的 SSE chunk 计数，约等于 token 数）。
     */
    private static final class StreamRateLogger {
        private static final ScheduledExecutorService SCHEDULER =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "llm-stream-rate");
                    t.setDaemon(true);
                    return t;
                });

        private final String label;
        private final long startNanos = System.nanoTime();
        private volatile long tokens;
        // 以下仅调度线程访问
        private long lastNanos = startNanos;
        private long lastTokens;
        private ScheduledFuture<?> task;

        StreamRateLogger(String label) {
            this.label = label;
        }

        void start() {
            task = SCHEDULER.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        }

        void addToken() {
            tokens++;
        }

        private void tick() {
            long now = System.nanoTime();
            double windowSec = (now - lastNanos) / 1e9;
            long total = tokens;
            double inst = (total - lastTokens) / windowSec;
            double avg = total / ((now - startNanos) / 1e9);
            log.info("[llm-stream:{}] +{}s tokens={} 瞬时 {} tok/s | 平均 {} tok/s",
                    label, "%.1f".formatted((now - startNanos) / 1e9), total,
                    "%.1f".formatted(inst), "%.1f".formatted(avg));
            lastNanos = now;
            lastTokens = total;
        }

        /** 流结束：停止节拍并打印汇总（失败路径同样适用） */
        void finish() {
            if (task != null) {
                task.cancel(false);
            }
            double sec = (System.nanoTime() - startNanos) / 1e9;
            log.info("[llm-stream:{}] 结束: {} tokens / {}s（平均 {} tok/s）",
                    label, tokens, "%.1f".formatted(sec), "%.1f".formatted(tokens / Math.max(sec, 1e-9)));
        }
    }

    private static String join(String base, String path) {
        return (base.endsWith("/") ? base : base + "/") + path;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
