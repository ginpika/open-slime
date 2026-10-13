package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.MemoryStage;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 单次会话执行：协调任务、流式过程、停止与结果落库；模型循环由 AgentRuntime 执行。 */
@Service
public class ChatTurnRunner implements ChatExecution {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnRunner.class);

    private static final int TITLE_MAX = 20;

    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            DelegateSubagentTool.NAME, ReadSubagentRunTool.NAME);

    private final AgentRuntime runtime;
    private final ToolExecutionRecorder toolExecs;
    private final SettingsService settings;
    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    private final ObjectMapper om;
    private final ToolManager tools;
    private final SubagentTool subagent;
    private final MemoryWriter memory;
    private final ProjectStore projects;
    private final ChatTaskRunner chatTasks;
    private final TurnStore turnStore;
    private final TodoService todo;
    private final ChatContextService chatContext;
    private final ChatToolDispatcher toolDispatcher;
    private final ChatTurnCompletion completion;
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    /* 下面这些 public 方法与几个常量同为一组「可替换点」（见 {@link ChatExecution}）：首页对话用
       本类自己的实现；多智能体那条流实现同一个接口、换成自己的落点与组装方式，但整条生成过程
       （工具往返、trace、停止标志）一字不改地复用。 */

    private final ConcurrentHashMap<String, Set<AtomicBoolean>> activeStops = new ConcurrentHashMap<>();

    public ChatTurnRunner(AgentRuntime runtime,
            ToolExecutionRecorder toolExecs,
            SettingsService settings,
            HubSessionStore sessions,
            ChatMessageStore messages,
            ObjectMapper om,
            ToolManager tools,
            SubagentTool subagent,
            MemoryWriter memory,
            ProjectStore projects,
            ChatTaskRunner chatTasks,
            TurnStore turnStore,
            TodoService todo,
            ChatContextService chatContext,
            ChatToolDispatcher toolDispatcher,
            ChatTurnCompletion completion) {
        this.runtime = runtime;
        this.toolExecs = toolExecs;
        this.settings = settings;
        this.sessions = sessions;
        this.messages = messages;
        this.om = om;
        this.tools = tools;
        this.subagent = subagent;
        this.memory = memory;
        this.projects = projects;
        this.chatTasks = chatTasks;
        this.turnStore = turnStore;
        this.todo = todo;
        this.chatContext = chatContext;
        this.toolDispatcher = toolDispatcher;
        this.completion = completion;
    }

    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId) {
        return runChat(this, hubId, input, modelId, promptId, null);
    }

    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         ChatStream sink) {
        return runChat(this, hubId, input, modelId, promptId, sink);
    }

    public ChatDtos.ChatReply chatStream(ChatExecution exec, String hubId, String input,
                                         String modelId, String promptId, ChatStream sink) {
        return runChat(exec, hubId, input, modelId, promptId, sink);
    }

    public boolean requestStop(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return false;
        }
        Set<AtomicBoolean> flags = activeStops.get(hubId);
        if (flags == null || flags.isEmpty()) {
            return false;
        }
        /* 置位全部在跑的那几条：同会话可能不止一条（见 activeStops 字段注释） */
        for (AtomicBoolean flag : flags) {
            flag.set(true);
        }
        return true;
    }

    private AtomicBoolean registerStop(String hubId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        activeStops.computeIfAbsent(hubId, k -> ConcurrentHashMap.newKeySet()).add(flag);
        return flag;
    }

    private static void ensureRunning(AtomicBoolean stopFlag) {
        if (stopFlag != null && stopFlag.get()) {
            throw new ChatCancelledException();
        }
    }

    private ChatDtos.ChatReply runChat(ChatExecution exec, String hubId, String input,
                                       String modelId, String promptId, ChatStream sink) {
        ModelTarget target = settings.resolveChatModel(modelId);
        if (!target.configured()) {
            throw new IllegalStateException(
                    "LLM 未配置：请在设置页添加模型，或在 .env 填写 "
                            + "LLM_BASE_URL / LLM_API_KEY / LLM_CHAT_MODEL");
        }
        HubRow row = exec.resolveOrCreate(hubId);
        /* 在途锁（STEP 4）：同一会话同一时刻只允许一次生成。重复触发（双击发送 / 两个标签页同时发）
           在这里被后端挡住，不再单靠前端 sendingRef；被拒的这次不落库、不动历史。
           圆桌那条流自管节拍（singleFlight=false），不受此锁约束——保持旧行为零变化 */
        boolean locked = exec.singleFlight() && inflight.add(row.id());
        if (exec.singleFlight() && !locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该会话正在生成中，请等它答完或先点停止。");
        }
        try {
            return runChatInner(exec, row, target, input, modelId, promptId, sink);
        } finally {
            if (exec.singleFlight()) {
                inflight.remove(row.id());
            }
            /* 生成收尾（成功 / 取消 / 异常都走这里）：把该会话遗留的「进行中」收敛为「未开始」，
               于是清单不会因中断 / 超限长期卡在 in_progress（见 TodoService#settle）。
               归属口径与写入侧一致：圆桌在外层设了成员作用域就用它，否则按会话绑定的 Agent */
            todo.settle(row.id(), AgentScope.get() != null ? AgentScope.get() : agentOf(row));
        }
    }

    private ChatDtos.ChatReply runChatInner(ChatExecution exec, HubRow row, ModelTarget target,
                                            String input, String modelId, String promptId,
                                            ChatStream sink) {
        /* 流式请求登记停止标志（非流式不参与停止）：requestStop 按会话 id 置位，
            生成线程在增量与轮次边界检测；finally 里注销，避免残留标志误停下一次发送 */
        AtomicBoolean stopFlag = sink == null ? null : registerStop(row.id());
        /* 记住本次使用的模型/提示词（会话级「…」菜单配置），下次进入该会话自动回显 */
        exec.rememberConfig(row, modelId, promptId);
        /* 本次提问的工具往返上限：会话级「…」菜单配置（缺省 30），超限后撤掉 tools 强制出最终回答 */
        int maxRounds = Math.max(ChatSessionService.MIN_MAX_ROUNDS, row.maxRounds());

        /* 这次生成落一条任务：有了身份与状态，进程重启打断时它才会被收成「已中断」而不是凭空消失。
           不跟踪任务的流拿到 0，{@link ChatTaskRunner} 的收尾方法对非正数一律空转。
           归属 Agent 现取：首页对话 = 内置 Agent；圆桌那次发言 = 正在发言的成员
           （成员作用域由 RoundtableChatService#speak 在外层设好，见 AgentScope） */
        String taskAgentId = AgentScope.get() != null ? AgentScope.get() : agentOf(row);
        long taskId = exec.taskTracked()
                ? chatTasks.begin(row.id(), taskAgentId, abbreviate(input), input) : 0L;
        long userMsgId = exec.recordUserInput(row, input);
        if (sink != null) {
            sink.meta(row.id(), userMsgId);
        }
        /* 这一轮的 Turn 落一行：提问与它的回答显式配对，并挂上刚开的会话任务（执行信封）。
           只对「提问 → 回答」成对的流写——圆桌的一次发言没有一条提问消息当触发端
           （人的话由 RoundtableTopicService 落，成员的发言是一段一段的），故它不记
           （见 ChatExecution#turnTracked）。回答还没出来，先只有触发端；答完（或取消）再补上输出端 */
        long turnId = exec.turnTracked()
                ? turnStore.begin(row.id(), TurnTrigger.Source.USER_INPUT, userMsgId,
                        taskId > 0 ? taskId : null)
                : 0L;

        /* memory-v6 §4.2：本轮的记忆执行作用域——谁 × 哪个项目 / 会话 / 任务 × 身份上下文。
           在组装上下文（requestBody）之前就圈定：记忆注入与工具面读的是同一份作用域，
           并带上 turnId 供快照回放（§4.3） */
        boolean narrative = row.projectId() != null && !row.projectId().isBlank()
                && projects.isNarrative(row.projectId());
        MemoryExec memScope = MemoryExec.chat(taskAgentId, row.projectId(), row.id(),
                taskId > 0 ? taskId : null, turnId > 0 ? Long.toString(turnId) : null,
                MemoryIdentity.ofNarrative(narrative));

        /* 工具面已全量常驻（不再按能力域裁剪）：这里不再需要「本次授权」这份判断 */

        /* 请求体：system + 全量历史（含刚落库的本次 user）+ 知识库工具（向量检索 + 图谱查询）；
           组装规则与「查看原始上下文」共用同一份（见 requestBody）。循环由 Agent 内核跑（见下），
           本类只负责备齐素材、把过程接到流式与留痕上、把结果落库 */
        ObjectNode body = MemoryScopeCtx.run(memScope, () ->
                exec.requestBody(row, target, promptId, settings.chatContextWindow(modelId)));
        ArrayNode msgs = (ArrayNode) body.get("messages");

        /* 累计态交给内核就地写入（思考链跨轮拼接、分段、工具往返、原始返回）：本类持有，
           于是中途停止时仍读得到已经产出的那部分，半截回答照样落库 */
        AgentRuntime.RunState state = new AgentRuntime.RunState();
        /* 本次输入派出的子代理归属的批次：懒开、方法末尾统一收；用数组承载，供回调 lambda 捕获 */
        String[] subagentBatch = {null};
        /* 用户停止时按轮次给部分思考分段标轮次 */
        int[] currentRound = {0};
        /* 本轮已流出的部分正文/思考（逐轮重置，最终答复以末轮为准）：用户停止时以部分回答落库 */
        StringBuilder partialContent = new StringBuilder();
        StringBuilder partialReasoning = new StringBuilder();
        boolean[] anyReasoning = {false};
        OpenAiChatClient.DeltaListener base = sink == null ? null : (r, c) -> {
            ensureRunning(stopFlag); // 用户已停止：抛出解栈，不再等待/消费后续 LLM 输出
            if (r != null) {
                partialReasoning.append(r);
            }
            if (c != null) {
                partialContent.append(c);
            }
            sink.delta(r, c);
        };
        /* 过程回调：把内核的执行过程接到「流式推送 · 子代理开批 · 排障日志」上；非流式不设它 */
        AgentRuntime.Listener listener = sink == null ? null : new AgentRuntime.Listener() {
            @Override
            public void round(int round) {
                currentRound[0] = round;
                ensureRunning(stopFlag); // 上一轮工具执行期间用户可能已停止
                sink.round(round);
                /* 部分输出按轮累计：正文以停止所在轮为准（与最终答复取末轮一致） */
                partialContent.setLength(0);
                partialReasoning.setLength(0);
            }

            @Override
            public OpenAiChatClient.DeltaListener deltaListener(int round) {
                /* 本轮首个 reasoning 分片：跨轮时补分隔线（首次思考不补），与落库拼接保持一致 */
                boolean[] roundReasoning = {false};
                return (r, c) -> {
                    if (r == null) {
                        base.onDelta(null, c);
                        return;
                    }
                    String out = r;
                    if (!roundReasoning[0]) {
                        roundReasoning[0] = true;
                        if (anyReasoning[0]) {
                            out = ChatTrace.REASONING_SEPARATOR + r;
                        }
                        anyReasoning[0] = true;
                    }
                    base.onDelta(out, c);
                };
            }

            @Override
            public void tool(String name, String args) {
                /* 本次输入派出去的子代理同属一批 run：页面「最近几批」不被逐个调用刷屏，
                   整批跑完也只补一条整体总结。首批出现子代理调用时开批，收批统一在方法末尾的
                   finally（不能一开一收在各轮里做，否则跨轮派活会被拆成多批、总结多次） */
                if (subagentBatch[0] == null && SUBAGENT_TOOLS.contains(name)) {
                    String a = AgentScope.get() == null ? AgentDirectory.BUILTIN_ID : AgentScope.get();
                    subagentBatch[0] = subagent.openRun(a, "chat");
                }
                sink.tool(name, args);
            }

            @Override
            public void rejected(int round, ObjectNode rejectedBody, RuntimeException e) {
                /* 供应商拒收时把「这次到底发了什么」记一行结构摘要（不含正文）：
                   思考模式的 reasoning_content 缺失那类 400 只给一句模糊错误，
                   没有出站形态根本无从下手（踩过两次） */
                log.warn("补全被拒：session={} round={}/{} 带工具={} error={}｜出站形态={}",
                        row.id(), round, maxRounds, round < maxRounds, e.getMessage(),
                        digest(rejectedBody));
            }

            @Override
            public void fallbackToReasoning(int round, int chars, String raw) {
                log.warn("模型未返回 content，回退用 reasoning_content 作为回答：session={} round={}/{} "
                                + "chars={} raw={}",
                        row.id(), round, maxRounds, chars, raw);
            }
        };

        AgentRuntime.Result result;
        /* 圈定当前 Agent 作用域：首页对话是内置 Agent；圆桌那条流由 RoundtableChatService 在外层
           设好成员身份（exec != this），这里不再覆盖，否则成员的 RAG 召回会被记到内置 Agent 名下 */
        String scopeAgent = (exec == this) ? agentOf(row) : AgentScope.get();
        AgentRuntime.Spec spec = new AgentRuntime.Spec(target, TokenUsageService.SOURCE_CHAT, body,
                new AgentRuntime.ToolDispatch() {
                    @Override
                    public String run(JsonNode call) {
                        /* 每次工具调用都落一条生命周期记录（running → done/failed）并发事件：
                           工具的副作用因此可查、可审计，而不只是塞回模型上下文（设计一 §五-2） */
                        return toolExecs.record(row.id(), null, subagentBatch[0], call,
                                () -> exec.runTool(call, subagentBatch[0], row.id()));
                    }

                    @Override
                    public boolean parallelizable(String name) {
                        return tools.isParallelizable(name);
                    }
                },
                maxRounds, listener, state);
        /* memory-v6 §4.4：开启轮内写缓冲——本轮工具内的记忆写入（如 publish_knowledge）推到轮末提交，
           本轮注入仍用轮首快照；轮末在 finally 统一提交（被取消也提交：已经发生的发布不该丢） */
        MemoryStage.begin();
        try {
            result = MemoryScopeCtx.run(memScope,
                    () -> AgentScope.run(scopeAgent, () -> runtime.run(spec)));
        } catch (ChatCancelledException cancelled) {
            /* 用户停止：已有部分正文则以部分回答落库（思维链与工具轮留痕一并带上），
               刷新后对话完整保留；尚无任何正文则原样上抛，由 Controller 静默收尾 */
            String partial = partialContent.toString();
            /* 会话任务也跟着落定：停下的这轮是 cancelled（有部分正文就留成产物） */
            chatTasks.cancelled(taskId, partial);
            if (partial.isBlank()) {
                throw cancelled;
            }
            String partialReason = partialReasoning.toString();
            if (!partialReason.isBlank()) {
                if (state.reasoningChain().length() > 0) {
                    state.reasoningChain().append(ChatTrace.REASONING_SEPARATOR);
                }
                state.reasoningChain().append(partialReason);
                state.trace().add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING, partialReason,
                        null, null, currentRound[0]));
            }
            long partialReplyId = exec.recordReply(row, partial, state.reasoning(),
                    ChatTraceCodec.writeTrace(om, state.trace()),
                    ChatTraceCodec.writeRounds(om, state.toolRounds()));
            persistCalls(exec, partialReplyId, state);
            turnStore.complete(turnId, partialReplyId);
            exec.touchSession(row, null);
            return new ChatDtos.ChatReply(row.id(), userMsgId, partial, state.lastRawResponse(),
                    true, null);
        } catch (RuntimeException e) {
            /* 出错也要把任务收掉：留在 running 会被下次启动误判成「进程重启打断」（可重跑） */
            chatTasks.failed(taskId);
            throw e;
        } finally {
            /* 轮末提交轮内写缓冲（memory-v6 §4.4）：这一轮发布的记忆到此才落库，下一轮才可见 */
            MemoryScopeCtx.runVoid(memScope, MemoryStage::commit);
            /* 注销自己那面停止标志：同会话别的在跑流不受影响；集合空了连 key 一起摘掉，
               免得会话表随使用无界增长 */
            if (stopFlag != null) {
                activeStops.computeIfPresent(row.id(), (k, flags) -> {
                    flags.remove(stopFlag);
                    return flags.isEmpty() ? null : flags;
                });
            }
            /* 收批：派出去的子代理还在后台跑，这里只挂个账，等它们各自回报完才真正收——
               收批同时触发一次整体总结（见 SubagentService#settle / SubagentDeliveryService#onSubagentBatchDone） */
            if (subagentBatch[0] != null) {
                subagent.closeRun(subagentBatch[0]);
            }
        }
        if (result.end() == AgentRuntime.End.EMPTY_CONTENT) {
            log.error("模型返回空 content（本轮无 tool_calls）：session={} round={}/{} messages={} "
                            + "requestChars={} raw={}",
                    row.id(), result.round(), maxRounds, msgs.size(),
                    body.toString().length(), state.lastRawResponse());
            chatTasks.failed(taskId);
            throw new IllegalStateException("响应无 choices[0].message.content");
        }
        if (result.end() != AgentRuntime.End.ANSWERED) {
            log.error("工具调用轮数超限：session={} messages={} requestChars={} lastRaw={}",
                    row.id(), msgs.size(), body.toString().length(), state.lastRawResponse());
            chatTasks.failed(taskId);
            throw new IllegalStateException("工具调用轮数超限（" + maxRounds + "）");
        }

        long replyId = exec.recordReply(row, result.content(), state.reasoning(),
                ChatTraceCodec.writeTrace(om, state.trace()),
                ChatTraceCodec.writeRounds(om, state.toolRounds()));
        persistCalls(exec, replyId, state);
        turnStore.complete(turnId, replyId);
        exec.touchSession(row, null);
        chatTasks.done(taskId, result.content());
        completion.complete(exec, row, memScope, taskId, userMsgId, input, result.content());

        return new ChatDtos.ChatReply(row.id(), userMsgId, result.content(), result.rawResponse(),
                true, null);
    }

    // ---------- 会话域门面：外围模块（潜意识 / 子代理）对 chat 数据的写入入口
    // 会话内容的写入收敛在 {@link ChatThreads}（潜意识投递）与本类的聊天流程（人机对话）；
    // 子代理结论投回经 SubagentReportEvent 事件。唯一例外是潜意识注入器的「最近对话」只读投影，
    // 它直接读 ChatMessageStore ----------

    // ---------- 私有 ----------

    private static String agentOf(HubRow row) {
        return ChatSessionService.ownerOf(row.agentId());
    }

    private void persistCalls(ChatExecution exec, long replyId, AgentRuntime.RunState state) {
        if (exec != this) {
            return;
        }
        String json = ChatTraceCodec.writeCalls(om, state.llmCalls());
        if (json != null) {
            messages.updateContext(replyId, json);
        }
    }

    private static String digest(ObjectNode body) {
        StringBuilder sb = new StringBuilder();
        sb.append("tools=").append(body.path("tools").size())
                .append(" msgs=").append(body.path("messages").size()).append(" → ");
        for (JsonNode m : body.path("messages")) {
            JsonNode calls = m.path("tool_calls");
            boolean hasCalls = calls.isArray() && !calls.isEmpty();
            sb.append('[').append(m.path("role").asText("?"));
            if (hasCalls) {
                sb.append(" calls=").append(calls.size());
            }
            JsonNode reason = m.get("reasoning_content");
            if (reason != null) {
                sb.append(" reason=").append(reason.asText().isEmpty()
                        ? "空" : reason.asText().length() + "字");
            } else if (hasCalls) {
                sb.append(" reason=缺");
            }
            if (m.hasNonNull("tool_call_id")) {
                sb.append(" 工具结果");
            }
            sb.append("] ");
        }
        return sb.toString();
    }

    @Override
    public String toolSource() {
        return "chat";
    }

    @Override
    public boolean taskTracked() {
        return true;
    }

    @Override
    public boolean singleFlight() {
        return true;
    }

    @Override
    public HubRow resolveOrCreate(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return sessions.latest().orElseGet(this::insertNew);
        }
        return sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
    }

    @Override
    public HubRow insertNew() {
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null);
        return sessions.findById(hubId)
                .orElseThrow(() -> new IllegalStateException("hub_session 落库失败: " + hubId));
    }

    // ---------- 可替换点：落点与投影（多智能体那条流换掉这几处，流循环本身不动） ----------

    @Override
    public List<MessageRow> history(HubRow row) {
        return messages.listBySession(row.id());
    }

    @Override
    public long recordUserInput(HubRow row, String input) {
        long id = messages.insert(row.id(), "user", input, null, null, null, null, null);
        if (!sessions.memoryOff(row.id())) {
            memory.onChatMessage(eventScope(row), id, "user", row.title(), input);
        }
        return id;
    }

    @Override
    public long recordReply(HubRow row, String content, String reasoning, String trace,
                            String rounds) {
        long id = messages.insert(row.id(), "assistant", content, reasoning, trace, rounds,
                null, null);
        if (!sessions.memoryOff(row.id())) {
            memory.onChatMessage(eventScope(row), id, "assistant", row.title(), content);
        }
        return id;
    }

    private MemoryExec eventScope(HubRow row) {
        return MemoryExec.chat(agentOf(row), row.projectId(), row.id(), null,
                row.projectId() != null && projects.isNarrative(row.projectId()));
    }

    @Override
    public void touchSession(HubRow row, String title) {
        sessions.touch(row.id(), title);
    }

    @Override
    public void rememberConfig(HubRow row, String modelId, String promptId) {
        sessions.updateConfig(row.id(), modelId, promptId, null, null, null, null, null);
    }

    private static String abbreviate(String input) {
        String s = input.strip();
        return s.length() <= TITLE_MAX ? s : s.substring(0, TITLE_MAX);
    }

    @Override
    public ObjectNode requestBody(HubRow row, ModelTarget target, String promptId, int contextWindow) {
        return chatContext.requestBody(row, target, promptId, contextWindow);
    }

    @Override
    public String runTool(JsonNode call, String batchRunId, String hubId) {
        return toolDispatcher.runTool(call, batchRunId, hubId);
    }
}
