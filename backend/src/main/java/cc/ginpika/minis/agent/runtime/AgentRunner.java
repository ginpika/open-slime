package cc.ginpika.minis.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import cc.ginpika.minis.agent.chat.ChatService;
import cc.ginpika.minis.agent.chat.ChatTrace;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.config.SubconsciousProps;
import cc.ginpika.minis.stats.TokenUsageService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行引擎：跑完「一次独立上下文的执行」——组装好的 messages 进去，结论出来，过程全部留痕。
 *
 * <p>三种执行共用它，靠 {@code kind} 区分（见 {@link ExecStore}）：心跳主会话登场、任务计划被派出、
 * 子代理被当场交代。它们的差别只在「上下文怎么组装」与「工具调用落到谁身上」——
 * 前者由调用方自己造 messages，后者由调用方给的 {@link Dispatch} 决定；引擎只负责
 * 无状态地调 LLM、转工具往返、把结论与过程落进 {@code sub_exec}。
 *
 * <p>没有状态：给模型的 messages 每次现造，引擎自己不缓存任何东西（这正是「LLM 无状态、
 * 状态在库里」的落点）。工具往返上限见 {@code minis.subconscious.max-tool-rounds}，
 * 最后一轮撤掉 tools 逼模型给出结论。
 *
 * <p><b>并行</b>：模型在一条消息里一次要求调多个工具时，由 {@link ToolBatch} 决定并行还是串行——
 * 全部声明可并行（如一次派几个子代理）就并发跑，结果仍按原顺序回填。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 一次执行的输入 */
    public record Request(String runId, String kind, String planId, String title, String modelId,
                          ArrayNode messages, ArrayNode toolSchemas, Dispatch dispatch) {
    }

    /** 一次执行的结果：execId 可在留痕里回看这次执行的全过程 */
    public record Outcome(long execId, String status, String output, String error) {
    }

    /** 工具分发：把模型请求的一次调用落地成一条 tool 结果（不同调用方带不同的工具面） */
    public interface Dispatch {

        ToolResult run(JsonNode call, long execId);

        /** 多个调用同时到达时，本工具能否并行执行（默认不能：多数工具有顺序语义） */
        default boolean parallelizable(String name) {
            return false;
        }
    }

    private final SubconsciousProps props;
    private final SettingsService settings;
    private final OpenAiChatClient client;
    private final ExecStore execs;
    private final ObjectMapper om;
    private final MemoryWriter memory;

    public AgentRunner(SubconsciousProps props, SettingsService settings, OpenAiChatClient client,
                       ExecStore execs, ObjectMapper om, MemoryWriter memory) {
        this.props = props;
        this.settings = settings;
        this.client = client;
        this.execs = execs;
        this.om = om;
        this.memory = memory;
    }

    /** 跑一次执行：落 exec 行 → 多轮工具往返 → 落结论与过程 */
    public Outcome run(Request req) {
        return run(req, openExec(req));
    }

    /**
     * 预登记一次执行：先落一行 running 拿到 execId，再交给 {@link #run(Request, long)} 去跑。
     *
     * <p>异步派活（对话侧的子代理）走这条路——调用方要<b>立刻</b>拿到 id 回一张受理回执，
     * 不能等这次执行整个跑完才拿到；后台跑起来与回执无关，两边只在 exec 行上相遇。
     */
    public long openExec(Request req) {
        return execs.insertExec(req.runId(), req.kind(), req.planId(), req.title(),
                req.messages().toString());
    }

    /** 跑一次已登记的执行（execId 由 {@link #openExec} 给出）：多轮工具往返 → 落结论与过程 */
    public Outcome run(Request req, long execId) {
        ModelTarget target = settings.resolveChatModel(req.modelId());
        if (!target.configured()) {
            execs.finishExec(execId, "failed", null, null, null, null, "LLM 未配置");
            return new Outcome(execId, "failed", null, "LLM 未配置");
        }

        ArrayNode messages = req.messages().deepCopy();
        int maxRounds = Math.max(0, props.getMaxToolRounds());

        StringBuilder reasoningChain = new StringBuilder();
        List<ChatDtos.TraceSegment> trace = new ArrayList<>();
        List<JsonNode> toolRounds = new ArrayList<>();
        String output = null;

        for (int round = 0; round <= maxRounds; round++) {
            ObjectNode body = om.createObjectNode();
            body.put("model", target.model());
            body.set("messages", messages);
            if (req.toolSchemas() != null && !req.toolSchemas().isEmpty() && round < maxRounds) {
                body.set("tools", req.toolSchemas());
            }
            JsonNode message = client.chatRaw(target, body, null,
                    TokenUsageService.SOURCE_SUBCONSCIOUS).message();

            JsonNode reasoning = message.get("reasoning_content");
            if (reasoning != null && !reasoning.asText().isBlank()) {
                String text = reasoning.asText();
                if (reasoningChain.length() > 0) {
                    reasoningChain.append(ChatTrace.REASONING_SEPARATOR);
                }
                reasoningChain.append(text);
                trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING, text, null, null, round));
            }

            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                output = message.path("content").asText("");
                if (output.isBlank()) {
                    /* 兜底：部分推理模型把整段回答塞进 reasoning_content */
                    output = message.path("reasoning_content").asText("");
                }
                break;
            }

            /* assistant 消息（含 tool_calls）续在 messages 上，再逐条追加配对的 tool 结果；
               带 tool_calls 的轮次要补 reasoning_content（思考模式的续轮硬要求，见 ChatService） */
            JsonNode assistantRound = ChatService.withReasoningForToolCalls(message.deepCopy());
            messages.add(assistantRound);
            toolRounds.add(assistantRound);

            List<JsonNode> calls = new ArrayList<>();
            toolCalls.forEach(calls::add);
            List<String> contents = ToolBatch.run(calls, req.dispatch()::parallelizable,
                    call -> dispatch(req.dispatch(), call, execId));
            for (int i = 0; i < calls.size(); i++) {
                JsonNode call = calls.get(i);
                trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_TOOL, null,
                        call.path("function").path("name").asText(""),
                        call.path("function").path("arguments").asText(""), round));
                ObjectNode toolMsg = messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.path("id").asText(""))
                        .put("content", contents.get(i));
                toolRounds.add(toolMsg);
            }

            /* 这一轮的思考与工具往返就地增量落库：于是这条执行在跑的过程中也读得到进度
               （派它的人据此知道它跑到哪一步，不必等它出结论） */
            execs.updateProgress(execId, nullable(reasoningChain), writeStr(trace),
                    writeStr(toolRounds));
        }

        if (output == null || output.isBlank()) {
            execs.finishExec(execId, "failed", null, nullable(reasoningChain), writeStr(trace),
                    writeStr(toolRounds), "未产出结论");
            return new Outcome(execId, "failed", null, "未产出结论");
        }
        execs.finishExec(execId, "done", output, nullable(reasoningChain), writeStr(trace),
                writeStr(toolRounds), null);
        memory.onExecDone(execId, req.kind(), req.title(), output);
        return new Outcome(execId, "done", output, null);
    }

    /** 落地一次调用：写库类工具的调用另记一条写库清单 */
    private String dispatch(Dispatch dispatch, JsonNode call, long execId) {
        ToolResult outcome = dispatch.run(call, execId);
        if (outcome.write()) {
            execs.insertEdit(execId, outcome.name(), outcome.args(), outcome.content());
        }
        return outcome.content();
    }

    private String writeStr(List<?> list) {
        if (list.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("过程留痕序列化失败（仅本次执行内生效）：{}", e.getMessage());
            return null;
        }
    }

    private static String nullable(StringBuilder sb) {
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 落库的 messages JSON 反序列化回来做缩进展示；解析失败原样返回（供 read_subagent_run 用） */
    public static String pretty(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return om.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(om.readValue(json, new TypeReference<List<JsonNode>>() {
                    }));
        } catch (Exception e) {
            return json;
        }
    }
}
