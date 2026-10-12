package cc.ginpika.minis.agent.runtime;

import cc.ginpika.minis.agent.chat.ChatTrace;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ChatProtocol;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ModelTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Agent 内核：<b>唯一</b>的一条「模型往返 + 工具批 + 分段」循环。
 *
 * <p>从两份逻辑同构、各写一遍的循环合并而来——{@code ChatTurnRunner.runChatInner}（会话那条流：
 * 流式、停止标志、取消落库）与 {@code AgentRunner.run}（子代理 / 心跳 / 计划：逐轮增量落库）。
 * 内核只做执行，<b>不碰任何存储</b>：拿到的 messages 进去，结论出来，过程通过 {@link Listener}
 * 与 {@link RunState} 交给调用方——于是「谁来落库、落到哪张表」完全由调用方决定。
 *
 * <p>因此它两头都不认识：既不知道 {@code chat_message}，也不知道 {@code task}。调用方把
 * 组装好的请求体（{@link Spec#body()}）与「一次工具调用该由谁办」（{@link Spec#dispatch()}）交进来，
 * 把「过程要通知谁」（{@link Spec#listener()}，可为空）与累计态的落点（{@link Spec#runState()}）备好。
 *
 * <p>两条约定与两处旧实现一致，不要改：
 * <ul>
 *   <li><b>最后一轮撤掉 tools</b>，逼模型给出最终回答；</li>
 *   <li>带 {@code tool_calls} 的 assistant 轮次补 {@code reasoning_content}（思考模型的续轮硬要求），
 *       工具结果按 {@code tool_call_id} 配对续在同一串 messages 上——协议闭合。</li>
 * </ul>
 *
 * <p><b>异常</b>：内核不吞异常。上游拒绝、用户停止（{@link Listener#round} / {@link Listener#deltaListener}
 * 里抛的）都原样冒泡——停止与取消是调用方的语义，内核不替它决定。
 */
@Component
public class AgentRuntime {

    private final OpenAiChatClient client;
    private cc.ginpika.minis.ai.memory.MemoryLiveContext liveMemory;

    public AgentRuntime(OpenAiChatClient client) {
        this.client = client;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setLiveMemory(cc.ginpika.minis.ai.memory.MemoryLiveContext liveMemory) {
        this.liveMemory = liveMemory;
    }

    // ---------- 契约 ----------

    /**
     * 一次执行的全部输入。
     *
     * @param target    本次发往的模型
     * @param source    计量留痕的来源（{@link cc.ginpika.minis.stats.TokenUsageService} 的 source）
     * @param body      请求体：已含 model 与 messages（内核就地往 messages 追加工具往返）；
     *                  最后一轮由内核用去掉 tools 的副本
     * @param dispatch  一次工具调用该由谁办
     * @param maxRounds 工具往返轮数上限（0 = 只有首轮）
     * @param listener  过程回调；不需要过程通知的流传 null
     * @param runState  累计态（思考链 / 分段 / 工具往返 / 原始返回）的落点，由调用方持有
     */
    public record Spec(ModelTarget target, String source, ObjectNode body, ToolDispatch dispatch,
                       int maxRounds, Listener listener, RunState runState) {
    }

    /** 一次工具调用的执行入口：调用方决定这件工具归谁办、能不能并行 */
    public interface ToolDispatch {
        /** 落地一条调用，返回要回给模型的文本 */
        String run(JsonNode call);

        /** 该工具名是否可并行（默认不可并行） */
        default boolean parallelizable(String name) {
            return false;
        }
    }

    /** 执行过程的回调：全部默认空实现，调用方只覆写关心的阶段 */
    public interface Listener {
        /** 进入第 round 轮补全；用户停止可在这里抛异常解栈 */
        default void round(int round) {
        }

        /** 为第 round 轮造一个增量监听器（返回 null = 这一轮不要流式） */
        default OpenAiChatClient.DeltaListener deltaListener(int round) {
            return null;
        }

        /** 模型请求调用一次工具（在真正执行之前） */
        default void tool(String name, String args) {
        }

        /** 一轮工具往返结束（逐轮增量落库的时机） */
        default void progress() {
        }

        /** 上游拒收这次补全；调用方可记下出站形态便于排障 */
        default void rejected(int round, ObjectNode body, RuntimeException error) {
        }

        /** 本轮没给正文、回退用思考内容作答；调用方可留痕 */
        default void fallbackToReasoning(int round, int chars, String rawResponse) {
        }
    }

    /**
     * 一次执行的累计态：由<b>调用方</b>持有并传进来，内核就地写入——于是中途抛错（用户停止、
     * 上游失败）时，调用方仍读得到已经产出的那部分（取消时把半截回答落库就靠它）。
     */
    public static final class RunState {
        private final StringBuilder reasoningChain = new StringBuilder();
        private final List<ChatDtos.TraceSegment> trace = new ArrayList<>();
        private final List<JsonNode> toolRounds = new ArrayList<>();
        /** 本 Turn 每一次 LLM 调用的完整留痕（请求体 + 响应体），按轮次升序 */
        private final List<ChatDtos.LlmCall> llmCalls = new ArrayList<>();
        private String lastRawResponse;

        /** 累计思考链（空则为 null），分隔线与逐轮拼接规则与落库一致 */
        public String reasoning() {
            return reasoningChain.length() == 0 ? null : reasoningChain.toString();
        }

        /** 思考链的写入口（取消分支要往末尾追加半截思考） */
        public StringBuilder reasoningChain() {
            return reasoningChain;
        }

        /** 结构化分段（思考 / 工具）：可直接序列化落库 */
        public List<ChatDtos.TraceSegment> trace() {
            return trace;
        }

        /** 工具往返的原生消息（assistant 含 tool_calls + 配对 tool 结果） */
        public List<JsonNode> toolRounds() {
            return toolRounds;
        }

        /** 本 Turn 每一次 LLM 调用的留痕（请求体 + 响应体），按轮次升序 */
        public List<ChatDtos.LlmCall> llmCalls() {
            return llmCalls;
        }

        /** 最近一次补全的原始返回 */
        public String lastRawResponse() {
            return lastRawResponse;
        }
    }

    /** 这次执行怎么收的尾 */
    public enum End {
        /** 拿到最终回答 */
        ANSWERED,
        /** 模型这一轮既没正文也没思考（供应商形态异常） */
        EMPTY_CONTENT,
        /** 轮数用尽仍只给工具调用，没产出最终回答 */
        ROUNDS_EXHAUSTED
    }

    /** 执行结果：内容为空时看 {@link #end()} 区分成因，调用方据此报错与留痕 */
    public record Result(String content, String rawResponse, End end, int round) {
    }

    // ---------- 循环 ----------

    /** 跑一次：多轮模型往返与工具批，直到拿到最终回答或用尽轮数 */
    public Result run(Spec spec) {
        var previous = cc.ginpika.minis.ai.memory.MemoryLiveContext.begin((ArrayNode) spec.body().get("messages"));
        boolean ownsStage = !cc.ginpika.minis.ai.memory.MemoryStage.active();
        if (ownsStage) { cc.ginpika.minis.ai.memory.MemoryStage.begin(); }
        try {
            Result result = runScoped(spec);
            if (ownsStage) { cc.ginpika.minis.ai.memory.MemoryStage.commit(); }
            return result;
        } catch (RuntimeException error) {
            cc.ginpika.minis.ai.memory.MemoryStage.clear();
            throw error;
        }
        finally { cc.ginpika.minis.ai.memory.MemoryLiveContext.restore(previous); }
    }

    private Result runScoped(Spec spec) {
        ArrayNode msgs = (ArrayNode) spec.body().get("messages");
        int maxRounds = Math.max(0, spec.maxRounds());
        Listener listener = spec.listener();
        RunState state = spec.runState();

        for (int round = 0; round <= maxRounds; round++) {
            if (liveMemory != null) { liveMemory.refresh(msgs); }
            if (listener != null) {
                listener.round(round);
            }
            /* 最后一轮撤掉 tools，强制模型给出最终回答。副本必须<b>此刻</b>才拷——
               前几轮追加的 assistant(tool_calls) 与工具结果都在 messages 里，末轮同样要看得到，
               否则模型只能凭一个孤零零的提问作答（丢掉全部检索证据） */
            ObjectNode body = round < maxRounds ? spec.body() : withoutTools(spec.body());
            OpenAiChatClient.RawResult raw;
            try {
                raw = client.chatRaw(spec.target(), body,
                        listener == null ? null : listener.deltaListener(round), spec.source());
            } catch (RuntimeException e) {
                /* 失败也要留痕：出站请求体是排障的第一手材料（chatRaw 发起前已把 model/stream 就地补进 body） */
                state.llmCalls.add(new ChatDtos.LlmCall(round, body.toString(), null, round < maxRounds));
                if (listener != null) {
                    listener.rejected(round, body, e);
                }
                throw e;
            }
            state.lastRawResponse = raw.rawResponse();
            /* 留痕这一轮真实发出的请求体与收到的响应体：body 已被 chatRaw 就地补全 model/stream/
               stream_options，序列化成快照——后续轮次对该对象的追加不会改到已留的这份 */
            state.llmCalls.add(new ChatDtos.LlmCall(round, body.toString(),
                    raw.rawResponse(), round < maxRounds));
            JsonNode message = raw.message();

            /* 累计本轮思维链（工具轮的思考也留痕，供链式回放），并落为一段 reasoning 分段 */
            JsonNode reasoning = message.get("reasoning_content");
            if (reasoning != null && !reasoning.asText().isBlank()) {
                String text = reasoning.asText();
                if (state.reasoningChain.length() > 0) {
                    state.reasoningChain.append(ChatTrace.REASONING_SEPARATOR);
                }
                state.reasoningChain.append(text);
                state.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING, text, null, null, round));
            }

            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = message.path("content").asText(null);
                if (content == null || content.isBlank()) {
                    /* 兜底：部分推理模型/端点在正文为空时把整段回答放进了 reasoning_content
                     * （deepseek-flash 实测：content=null、reasoning_content 为完整回答），
                     * 此时以本轮思考内容作为回答，避免整轮失败丢弃已生成的内容 */
                    String fallback = message.path("reasoning_content").asText("");
                    if (fallback.isBlank()) {
                        return new Result(null, null, End.EMPTY_CONTENT, round);
                    }
                    if (listener != null) {
                        listener.fallbackToReasoning(round, fallback.length(), state.lastRawResponse);
                    }
                    content = fallback;
                }
                return new Result(content, raw.rawResponse(), End.ANSWERED, round);
            }

            /* 模型请求调用工具：assistant 消息（含 tool_calls）与按 tool_call_id 配对的 tool 结果
               都续进 messages 与 runState.toolRounds，供跨请求按原生协议原样回放 */
            JsonNode assistantRound = ChatProtocol.withReasoningForToolCalls(message.deepCopy());
            msgs.add(assistantRound);
            state.toolRounds.add(assistantRound);
            List<JsonNode> calls = new ArrayList<>();
            toolCalls.forEach(calls::add);
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText("");
                state.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_TOOL, null, name,
                        call.path("function").path("arguments").asText(""), round));
                if (listener != null) {
                    listener.tool(name, call.path("function").path("arguments").asText(""));
                }
            }
            List<String> contents = ToolBatch.run(calls, spec.dispatch()::parallelizable,
                    call -> {
                        if (liveMemory != null) { liveMemory.refresh(msgs); }
                        return spec.dispatch().run(call);
                    });
            for (int i = 0; i < calls.size(); i++) {
                ObjectNode toolMsg = msgs.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", calls.get(i).path("id").asText(""))
                        .put("content", contents.get(i));
                state.toolRounds.add(toolMsg);
            }
            if (listener != null) {
                listener.progress();
            }
        }
        return new Result(null, null, End.ROUNDS_EXHAUSTED, maxRounds);
    }

    /** 请求体的去 tools 副本（末轮用）：此刻才拷，带上已经追加的工具往返 */
    private static ObjectNode withoutTools(ObjectNode body) {
        ObjectNode copy = body.deepCopy();
        copy.remove("tools");
        return copy;
    }
}
