package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.MemoryStage;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.chat.RequestTodoCompletionTool;
import cc.ginpika.minis.ai.core.tool.chat.RequestUserInputTool;
import cc.ginpika.minis.ai.core.tool.doc.FetchWebTool;
import cc.ginpika.minis.ai.core.tool.doc.ReadDocumentTool;
import cc.ginpika.minis.ai.core.tool.doc.WebBrowserTool;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.ModelReasoning;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
    private ChatContextSources contextSources;
    private ChatContextManifests contextManifests;
    @org.springframework.beans.factory.annotation.Autowired
    private ChatTodoApprovals todoApprovals;
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ChatSteering> activeSteering = new ConcurrentHashMap<>();

    /* 下面这些 public 方法与几个常量同为一组「可替换点」（见 {@link ChatExecution}）：首页对话用
       本类自己的实现；多智能体那条流实现同一个接口、换成自己的落点与组装方式，但整条生成过程
       （工具往返、trace、停止标志）一字不改地复用。 */

    private final ConcurrentHashMap<String, Set<AtomicBoolean>> activeStops = new ConcurrentHashMap<>();
    private final ThreadLocal<AtomicBoolean> currentStop = new ThreadLocal<>();

    private record ApprovalPause(int round, ChatTodoApprovals.Draft draft) {}

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

    @org.springframework.beans.factory.annotation.Autowired
    public void setContextPilot(ChatContextSources sources, ChatContextManifests manifests) {
        this.contextSources = sources;
        this.contextManifests = manifests;
    }

    /** 回复与受控证据依赖同事务发布（R3）；非 Spring 测试环境缺省退化为直接写入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager manager) {
        this.transactions = manager == null ? null
                : new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    private org.springframework.transaction.support.TransactionTemplate transactions;

    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId) {
        return chat(hubId, input, modelId, promptId, null);
    }

    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId,
                                    String reasoningEffort) {
        return chat(hubId, input, modelId, promptId, reasoningEffort, null);
    }

    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId,
                                    String reasoningEffort, String actorId) {
        return runChat(this, hubId, input, modelId, promptId, reasoningEffort, null, actorId);
    }

    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         ChatStream sink) {
        return chatStream(hubId, input, modelId, promptId, null, sink);
    }

    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         String reasoningEffort, ChatStream sink) {
        return runChat(this, hubId, input, modelId, promptId, reasoningEffort, sink,
                sink == null ? null : sink.actorId());
    }

    public ChatDtos.ChatReply chatStream(ChatExecution exec, String hubId, String input,
                                         String modelId, String promptId, ChatStream sink) {
        return runChat(exec, hubId, input, modelId, promptId, null, sink, null);
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
        ChatSteering steering = activeSteering.get(hubId);
        if (steering != null && steering.stopped()) {
            steering.close();
        }
        return true;
    }

    public long steer(String hubId, long messageId, String input, String actorId) {
        ChatSteering steering = activeSteering.get(hubId);
        if (steering == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮回复已结束，请重新发送");
        }
        return steering.submit(messageId, actorId, input);
    }

    public boolean requestStop(String hubId, long messageId, String actorId) {
        ChatSteering steering = hubId == null ? null : activeSteering.get(hubId);
        return steering != null && steering.stop(messageId, actorId);
    }

    private AtomicBoolean registerStop(String hubId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        activeStops.computeIfAbsent(hubId, k -> ConcurrentHashMap.newKeySet()).add(flag);
        return flag;
    }

    /** Pause the other executions while allowing the caller to persist its question normally. */
    public void requestStopOthers(String hubId) {
        Set<AtomicBoolean> flags = activeStops.get(hubId);
        if (flags != null) for (AtomicBoolean flag : flags) {
            if (flag != currentStop.get()) flag.set(true);
        }
        ChatSteering steering = activeSteering.get(hubId);
        if (steering != null && steering.stopped()) {
            steering.close();
        }
    }

    private static void ensureRunning(AtomicBoolean stopFlag) {
        if (stopFlag != null && stopFlag.get()) {
            throw new ChatCancelledException();
        }
    }

    private ChatDtos.ChatReply runChat(ChatExecution exec, String hubId, String input,
                                       String modelId, String promptId, String reasoningEffort, ChatStream sink,
                                       String actorId) {
        HubRow row = exec.resolveOrCreate(hubId);
        String requestedModel=modelId;
        if(exec==this && (modelId==null || modelId.isBlank())){modelId=chatContext.modelIdFor(row.agentId(),modelId);}
        ModelTarget target = settings.resolveChatModel(modelId);
        if (!target.configured()) {
            throw new IllegalStateException(
                    "LLM 未配置：请在设置页添加模型，或在 .env 填写 "
                            + "LLM_BASE_URL / LLM_API_KEY / LLM_CHAT_MODEL");
        }
        String effort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
        if (effort != null && !ModelReasoning.efforts(target.model()).contains(effort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该模型不支持所选的思考强度");
        }
        /* 在途锁（STEP 4）：同一会话同一时刻只允许一次生成。重复触发（双击发送 / 两个标签页同时发）
           在这里被后端挡住，不再单靠前端 sendingRef；被拒的这次不落库、不动历史。
           圆桌那条流自管节拍（singleFlight=false），不受此锁约束——保持旧行为零变化 */
        boolean locked = exec.singleFlight() && inflight.add(row.id());
        if (exec.singleFlight() && !locked) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该会话正在生成中，请等它答完或先点停止。");
        }
        boolean steerable = exec == this && locked && sink != null && sink.steerable();
        AtomicBoolean stopFlag = sink == null ? null : registerStop(row.id());
        try {
            return runChatInner(exec, row, target, input, modelId, requestedModel, promptId, effort,
                    sink, stopFlag, steerable, actorId);
        } finally {
            if (steerable) {
                ChatSteering steering = activeSteering.remove(row.id());
                if (steering != null) {
                    steering.close();
                }
            }
            if (stopFlag != null) {
                activeStops.computeIfPresent(row.id(), (key, flags) -> {
                    flags.remove(stopFlag);
                    return flags.isEmpty() ? null : flags;
                });
            }
            if (exec.singleFlight()) {
                inflight.remove(row.id());
            }
            /* 生成收尾（成功 / 取消 / 异常都走这里）：把该会话遗留的「进行中」收敛为「未开始」，
               于是清单不会因中断 / 超限长期卡在 in_progress（见 TodoService#settle）。
               归属口径与写入侧一致：圆桌在外层设了成员作用域就用它，否则按会话绑定的 Agent */
            if (exec.settleTodosOnFinish()) {
                todo.settle(row.id(), AgentScope.get() != null ? AgentScope.get() : agentOf(row));
            }
        }
    }

    private ChatDtos.ChatReply runChatInner(ChatExecution exec, HubRow row, ModelTarget target,
                                            String input, String modelId, String requestedModel, String promptId,
                                            String reasoningEffort, ChatStream sink, AtomicBoolean stopFlag,
                                            boolean steerable, String actorId) {
        /* 记住本次使用的模型/提示词（会话级「…」菜单配置），下次进入该会话自动回显 */
        exec.rememberConfig(row, requestedModel, promptId);

        /* 这次生成落一条任务：有了身份与状态，进程重启打断时它才会被收成「已中断」而不是凭空消失。
           不跟踪任务的流拿到 0，{@link ChatTaskRunner} 的收尾方法对非正数一律空转。
           归属 Agent 现取：首页对话 = 内置 Agent；圆桌那次发言 = 正在发言的成员
           （成员作用域由 RoundtableChatService#speak 在外层设好，见 AgentScope） */
        String taskAgentId = AgentScope.get() != null ? AgentScope.get() : agentOf(row);
        boolean pilotEnabled = exec == this && "ai".equals(row.mode()) && taskAgentId.equals(agentOf(row))
                && chatContext.ephemeralContextEnabled();
        /* R1：Turn 开始前捕获当前任务段——每个 Turn 归入开始时的任务段；任务段活动期间，
           临时角色与确认约束随装配进入，普通段与已结束任务段不默认回放。 */
        Long turnScene = pilotEnabled ? chatContext.activeSceneId(row.id()) : null;
        ChatContextContinuation continuation = pilotEnabled ? chatContext.continuation(row, turnScene) : null;
        var approvalScope = pilotEnabled && chatContext.todoApprovalsEnabled() && actorId != null && !actorId.isBlank()
                ? todoApprovals.capture(row, actorId) : null;
        long taskId = exec.taskTracked()
                ? chatTasks.begin(row.id(), taskAgentId, abbreviate(input), input) : 0L;
        // The key travels with the messages when branching, where their database IDs are reassigned.
        String turnKey = steerable ? ChatMessageStore.STEER_TURN_PREFIX + UUID.randomUUID() : null;
        long userMsgId = steerable ? recordChatMessage(row, "user", input, null, null, null, turnKey)
                : exec.recordUserInput(row, input);
        /* 这一轮的 Turn 落一行：提问与它的回答显式配对，并挂上刚开的会话任务（执行信封）。
           只对「提问 → 回答」成对的流写——圆桌的一次发言没有一条提问消息当触发端
           （人的话由 RoundtableTopicService 落，成员的发言是一段一段的），故它不记
           （见 ChatExecution#turnTracked）。回答还没出来，先只有触发端；答完（或取消）再补上输出端 */
        long turnId = exec.turnTracked()
                ? turnStore.begin(row.id(), TurnTrigger.Source.USER_INPUT, userMsgId,
                        taskId > 0 ? taskId : null, turnScene)
                : 0L;

        ChatSteering steering = steerable ? new ChatSteering(userMsgId, input, sink, stopFlag,
                (role, text) -> recordInterjection(row, role, text, turnKey, !pilotEnabled || "user".equals(role)))
                : null;
        if (steering != null) {
            synchronized (steering) {
                activeSteering.put(row.id(), steering);
                sink.meta(row.id(), userMsgId);
            }
        } else if (sink != null) {
            sink.meta(row.id(), userMsgId);
        }

        /* memory-v6 §4.2：本轮的记忆执行作用域——谁 × 哪个项目 / 会话 / 任务 × 身份上下文。
           在组装上下文（requestBody）之前就圈定：记忆注入与工具面读的是同一份作用域，
           并带上 turnId 供快照回放（§4.3） */
        boolean narrative = row.projectId() != null && !row.projectId().isBlank()
                && projects.isNarrative(row.projectId());
        MemoryExec memScope = MemoryExec.chat(taskAgentId, row.projectId(), row.id(),
                taskId > 0 ? taskId : null, turnId > 0 ? Long.toString(turnId) : null,
                MemoryIdentity.ofNarrative(narrative));

        /* 工具面已全量常驻（不再按能力域裁剪）：这里不再需要「本次授权」这份判断 */

        // The pilot keeps the working transcript intact and fits a separate outbound view on each model step.
        ContextPlan plan;
        ObjectNode body;
        try {
            int contextWindow = settings.chatContextWindow(modelId);
            plan = pilotEnabled ? MemoryScopeCtx.run(memScope, () ->
                    chatContext.plannedRequestBody(row, target, promptId, contextWindow, userMsgId, turnId,
                            continuation, approvalScope != null)) : null;
            body = plan == null ? MemoryScopeCtx.run(memScope, () ->
                    exec.requestBody(row, target, promptId, contextWindow)) : plan.body();
        } catch (RuntimeException error) {
            chatTasks.failed(taskId);
            throw error;
        }
        ChatContextRun contextRun = plan == null ? null : new ChatContextRun(row, actorId, taskId, turnId,
                plan, contextSources, contextManifests, continuation);
        // This body belongs to one turn and is retained through its tool rounds.
        // Do not mutate the model's global settings or approximate effort with a prompt.
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort);
        ArrayNode msgs = (ArrayNode) body.get("messages");
        /* 本轮起点：提问收尾时要把这一轮已经说过的正文一起落库（见 answerAfterTools） */
        int turnStart = msgs.size();

        /* 累计态交给内核就地写入（思考链跨轮拼接、分段、工具往返、原始返回）：本类持有，
           于是中途停止时仍读得到已经产出的那部分，半截回答照样落库 */
        AgentRuntime.RunState state = new AgentRuntime.RunState();
        /* 本次输入派出的子代理归属的批次：懒开、方法末尾统一收；用数组承载，供回调 lambda 捕获 */
        String[] subagentBatch = {null};
        ChatTodoApprovals.Draft[] proposedApproval = {null};
        ApprovalPause[] approvalPause = {null};
        /* 用户停止时按轮次给部分思考分段标轮次 */
        int[] currentRound = {0};
        /* 本轮已流出的部分正文/思考（逐轮重置，最终答复以末轮为准）：用户停止时以部分回答落库 */
        StringBuilder partialContent = new StringBuilder();
        StringBuilder partialReasoning = new StringBuilder();
        boolean[] anyReasoning = {false};
        if (steering != null) {
            steering.prepare(body, round -> {
                partialContent.setLength(0);
                partialReasoning.setLength(0);
                anyReasoning[0] = !state.reasoningChain().isEmpty();
                sink.reset(round);
            }, plan,
                    (request, control) -> runtime.replyWhileWorking(target, request.body(),
                            TokenUsageService.SOURCE_CHAT, control, request.memorySnapshot(),
                            contextRun == null ? null : (round, workingBody) -> contextRun.prepareInterim(request)));
        }
        OpenAiChatClient.DeltaListener base = sink == null ? null : (r, c) -> {
            ensureRunning(stopFlag); // 用户已停止：抛出解栈，不再等待/消费后续 LLM 输出
            if (steering != null && !steering.currentOutput()) {
                return;
            }
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
                if (subagentBatch[0] == null && DelegateSubagentTool.NAME.equals(name)) {
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
                log.warn("补全被拒：session={} round={} 带工具={} error={}｜出站形态={}",
                        row.id(), round, rejectedBody.has("tools"), e.getMessage(),
                        digest(rejectedBody));
            }

            @Override
            public void fallbackToReasoning(int round, int chars, String raw) {
                log.warn("模型未返回 content，回退用 reasoning_content 作为回答：session={} round={} "
                                + "chars={} raw={}",
                        row.id(), round, chars, raw);
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
                        // Human input and approval end a turn and must be isolated from side effects.
                        JsonNode batch = msgs.get(msgs.size() - 1).path("tool_calls");
                        if (exec == ChatTurnRunner.this && batch.size() > 1) {
                            for (JsonNode item : batch) {
                                String itemName = item.path("function").path("name").asText();
                                if (RequestUserInputTool.NAME.equals(itemName)
                                        || RequestTodoCompletionTool.NAME.equals(itemName)) {
                                    return itemName + " 必须单独调用；本批工具均未执行，请分开重试。";
                                }
                            }
                        }
                        JsonNode function = call.path("function");
                        String name = function.path("name").asText();
                        if (sink == null || exec != ChatTurnRunner.this) {
                            return execute(call, name);
                        }
                        String callId = UUID.randomUUID().toString();
                        String activityTarget = toolActivityTarget(name, function.path("arguments").asText());
                        sink.toolActivity(callId, name, activityTarget, true);
                        try {
                            return execute(call, name);
                        } finally {
                            sink.toolActivity(callId, name, activityTarget, false);
                        }
                    }

                    private String execute(JsonNode call, String name) {
                        if (RequestTodoCompletionTool.NAME.equals(name)) {
                            if (approvalScope == null) {
                                return "当前执行没有启用待办确认。";
                            }
                            /* R9 P3/P4 显式延后：本轮已暂存新续接或产生待提交 Epoch 时，审批不写提案，
                               本轮走正常结算，下一轮可重新提出——避免暂停分支丢弃待保存的续接。 */
                            if (continuation != null && (continuation.hasPending()
                                    || (contextRun != null && contextRun.hasSubmittedEpoch()))) {
                                log.info("[ctx] approval.deferred session={} turn={} reason={}", row.id(), turnId,
                                        continuation.hasPending() ? "staged_continuation" : "submitted_epoch");
                                return om.createObjectNode().put("status", "deferred")
                                        .put("detail", "本轮已暂存阶段续接或水位，待办审批延后：本轮正常结算，"
                                                + "下一轮可重新提出完成提案。")
                                        .toString();
                            }
                            proposedApproval[0] = null;
                            try {
                                proposedApproval[0] = todoApprovals.propose(approvalScope, call);
                                return "提案已准备，只有本轮在确认安全点暂停后才会保存。";
                            } catch (IllegalArgumentException invalid) {
                                return "待办提案无效：" + invalid.getMessage();
                            }
                        }
                        if (contextRun != null && ChatContextSources.TOOL_NAME.equals(name)) {
                            return contextRun.readSource(call);
                        }
                        if (continuation != null && ContextPlan.CONTINUATION_TOOL.equals(name)) {
                            return contextRun.saveContinuation(call);
                        }
                        /* 工具副作用落生命周期记录（running → done/failed），供回查与审计。 */
                        if (contextRun != null && SUBAGENT_TOOLS.contains(name)) {
                            return toolExecs.record(row.id(), Long.toString(taskId), subagentBatch[0], call,
                                    () -> contextRun.runSubagent(call,
                                            () -> toolDispatcher.runTool(call, subagentBatch[0], row.id(), true, actorId)));
                        }
                        return toolExecs.record(row.id(), null, subagentBatch[0], call,
                                () -> exec.runTool(call, subagentBatch[0], row.id()));
                    }

                    @Override
                    public boolean parallelizable(String name) {
                        return exec.parallelToolCalls() && tools.isParallelizable(name);
                    }

                    @Override
                    public String answerAfterTools(int round, List<JsonNode> calls, List<String> results) {
                        if (exec != ChatTurnRunner.this || calls.size() != 1) return null;
                        String name = calls.getFirst().path("function").path("name").asText();
                        if (RequestTodoCompletionTool.NAME.equals(name) && proposedApproval[0] != null) {
                            var proposal = proposedApproval[0];
                            approvalPause[0] = new ApprovalPause(round, proposal);
                            String narrative = turnText(msgs, turnStart);
                            return narrative.isBlank() ? proposal.prompt() : narrative + "\n\n" + proposal.prompt();
                        }
                        if (!RequestUserInputTool.NAME.equals(name)) return null;
                        try {
                            JsonNode result = om.readTree(results.getFirst());
                            if (!"waiting_for_user".equals(result.path("status").asText())) return null;
                            String question = RequestUserInputTool.content(RequestUserInputTool.read(om, result.toString()));
                            /* 提问只结束本轮、不撤销已经说出的正文：正文在前，问题收尾 */
                            String narrative = turnText(msgs, turnStart);
                            return narrative.isBlank() ? question : narrative + "\n\n" + question;
                        } catch (Exception invalid) { return null; }
                    }
                },
                listener, state, steering,
                contextRun == null ? null : (round, workingBody) -> contextRun.prepare(round, workingBody,
                        steering == null ? Map.of() : steering.references()));
        /* memory-v6 §4.4：开启轮内写缓冲——本轮工具内的记忆写入（如 publish_knowledge）推到轮末提交，
           本轮注入仍用轮首快照；轮末在 finally 统一提交（被取消也提交：已经发生的发布不该丢） */
        MemoryStage.begin();
        AtomicBoolean previousStop = currentStop.get();
        currentStop.set(stopFlag);
        try {
            result = MemoryScopeCtx.run(memScope,
                    () -> AgentScope.run(scopeAgent, () -> runtime.run(spec)));
            if (approvalPause[0] != null && approvalPause[0].round() == result.round()) {
                ensureRunning(stopFlag);
            }
            if (continuation != null) {
                contextRun.verifyContinuation();
            }
        } catch (ChatCancelledException cancelled) {
            /* 用户停止：已有部分正文则以部分回答落库（思维链与工具轮留痕一并带上），
               刷新后对话完整保留；尚无任何正文则原样上抛，由 Controller 静默收尾 */
            String partial = exec.replyOnCancel(steering != null && !steering.currentOutput()
                    ? "" : partialContent.toString());
            if (continuation != null && !partial.isBlank()) {
                try {
                    contextRun.verifyContinuation();
                } catch (ResponseStatusException stale) {
                    partial = "";
                }
            }
            /* 会话任务也跟着落定：停下的这轮是 cancelled（有部分正文就留成产物） */
            chatTasks.cancelled(taskId, partial);
            if (partial.isBlank() || !exec.keepPartialReplyOnCancel()) {
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
            long partialReplyId = settleReply(exec, row, partial, state, turnKey, contextRun);
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
            if (previousStop == null) currentStop.remove();
            else currentStop.set(previousStop);
            /* 收批：派出去的子代理还在后台跑，这里只挂个账，等它们各自回报完才真正收——
               收批同时触发一次整体总结（见 SubagentService#settle / SubagentDeliveryService#onSubagentBatchDone） */
            if (subagentBatch[0] != null) {
                subagent.closeRun(subagentBatch[0]);
            }
        }
        {
            String withImages = cc.ginpika.minis.ai.comfyui.integration.GeneratedImageReply.appendMissing(result.content(), state.toolRounds(), om);
            if (!withImages.equals(result.content()) && !withImages.isBlank()) {
                if (sink != null) sink.delta(null, withImages.substring(result.content() == null ? 0 : result.content().length()));
                result = new AgentRuntime.Result(withImages, result.rawResponse(), AgentRuntime.End.ANSWERED, result.round());
            }
        }
        if (result.end() == AgentRuntime.End.EMPTY_CONTENT) {
            log.error("模型返回空 content（本轮无 tool_calls）：session={} round={} messages={} "
                            + "requestChars={} raw={}",
                    row.id(), result.round(), msgs.size(),
                    body.toString().length(), state.lastRawResponse());
            chatTasks.failed(taskId);
            throw new IllegalStateException("响应无 choices[0].message.content");
        }

        ApprovalPause pause = approvalPause[0];
        if (pause != null && pause.round() == result.round()) {
            String reply = result.content();
            try {
                todoApprovals.suspend(approvalScope, pause.draft(), taskId, turnId, () -> {
                    long replyId = settleReply(exec, row, reply, state, turnKey, contextRun, false);
                    if (replyId <= 0) {
                        throw new IllegalStateException("待办确认回复保存失败");
                    }
                    persistCalls(exec, replyId, state);
                    turnStore.complete(turnId, replyId);
                    exec.touchSession(row, null);
                    return replyId;
                });
            } catch (RuntimeException error) {
                chatTasks.failed(taskId);
                throw error;
            }
            completion.complete(exec, row, memScope, taskId, userMsgId, input, reply, false);
            return new ChatDtos.ChatReply(row.id(), userMsgId, reply, result.rawResponse(), true, null);
        }

        long replyId = settleReply(exec, row, result.content(), state, turnKey, contextRun);
        persistCalls(exec, replyId, state);
        turnStore.complete(turnId, replyId);
        exec.touchSession(row, null);
        chatTasks.done(taskId, result.content());
        if (continuation != null) {
            contextRun.settleContinuation();
        }
        if (contextRun != null && contextRun.usedScopedEvidence()) {
            completion.complete(exec, row, memScope, taskId, userMsgId, input, result.content(), false);
        } else {
            completion.complete(exec, row, memScope, taskId, userMsgId, input, result.content());
        }

        return new ChatDtos.ChatReply(row.id(), userMsgId, result.content(), result.rawResponse(),
                true, null);
    }

    /** Only display selected identifiers, never command lines or whole URLs. */
    private String toolActivityTarget(String name, String arguments) {
        String field = switch (name) {
            case FetchWebTool.NAME, WebBrowserTool.NAME -> "url";
            case ReadDocumentTool.NAME -> "doc_id";
            default -> null;
        };
        if (field == null) {
            return null;
        }
        try {
            JsonNode args = om.readTree(arguments);
            if (args == null || !args.hasNonNull(field)) {
                return null;
            }
            JsonNode value = args.get(field);
            if (ReadDocumentTool.NAME.equals(name)) {
                return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0
                        ? "#" + value.asLong() : null;
            }
            URI url = URI.create(value.asText());
            return url.getUserInfo() == null && ("https".equalsIgnoreCase(url.getScheme())
                    || "http".equalsIgnoreCase(url.getScheme())) ? url.getHost() : null;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return null;
        }
    }

    // ---------- 会话域门面：外围模块（潜意识 / 子代理）对 chat 数据的写入入口
    // 会话内容的写入收敛在 {@link ChatThreads}（潜意识投递）与本类的聊天流程（人机对话）；
    // 子代理结论投回经 SubagentReportEvent 事件。唯一例外是潜意识注入器的「最近对话」只读投影，
    // 它直接读 ChatMessageStore ----------

    // ---------- 私有 ----------

    private static String agentOf(HubRow row) {
        return ChatSessionService.ownerOf(row.agentId());
    }

    /** 本轮（自 turnStart 起）模型已说出的可见正文，按轮次拼接；工具轮的空 content 忽略。 */
    private static String turnText(ArrayNode msgs, int turnStart) {
        StringBuilder text = new StringBuilder();
        for (int i = turnStart; i < msgs.size(); i++) {
            JsonNode message = msgs.get(i);
            if (!"assistant".equals(message.path("role").asText())) { continue; }
            String content = message.path("content").asText("");
            if (content.isBlank()) { continue; }
            if (text.length() > 0) { text.append("\n\n"); }
            text.append(content.stripTrailing());
        }
        return text.toString();
    }

    private long recordTurnReply(ChatExecution exec, HubRow row, String content,
                                 AgentRuntime.RunState state, String turnKey, ChatContextRun contextRun) {
        return recordTurnReply(exec, row, content, state, turnKey, contextRun, true);
    }

    /** 回复与其受控证据依赖在同一结算中发布（R3）：没有依赖可记时走原路径，行为不变。 */
    private long settleReply(ChatExecution exec, HubRow row, String content, AgentRuntime.RunState state,
                             String turnKey, ChatContextRun contextRun) {
        return settleReply(exec, row, content, state, turnKey, contextRun, true);
    }

    private long settleReply(ChatExecution exec, HubRow row, String content, AgentRuntime.RunState state,
                             String turnKey, ChatContextRun contextRun, boolean captureAssistant) {
        if (contextRun == null) {
            return recordTurnReply(exec, row, content, state, turnKey, null, captureAssistant);
        }
        if (transactions == null) {
            // 无事务模板（非 Spring 装配）：仍记录依赖，只是不保证与正文同事务发布。
            long id = recordTurnReply(exec, row, content, state, turnKey, contextRun, captureAssistant);
            if (id > 0) {
                contextRun.persistDependencies(id);
            }
            return id;
        }
        Long replyId = transactions.execute(tx -> {
            long id = recordTurnReply(exec, row, content, state, turnKey, contextRun, captureAssistant);
            if (id > 0) {
                contextRun.persistDependencies(id);
            }
            return id;
        });
        return replyId == null ? -1L : replyId;
    }

    private long recordTurnReply(ChatExecution exec, HubRow row, String content,
                                 AgentRuntime.RunState state, String turnKey, ChatContextRun contextRun,
                                 boolean captureAssistant) {
        String trace = ChatTraceCodec.writeTrace(om, state.trace());
        String rounds = ChatTraceCodec.writeRounds(om,
                contextRun != null ? ChatContextRun.replayableRounds(state.toolRounds()) : state.toolRounds());
        if (!captureAssistant || (contextRun != null && contextRun.usedScopedEvidence())) {
            return messages.insert(row.id(), "assistant", content, state.reasoning(), trace, rounds, null, turnKey);
        }
        return turnKey == null ? exec.recordReply(row, content, state.reasoning(), trace, rounds)
                : recordChatMessage(row, "assistant", content, state.reasoning(), trace, rounds, turnKey);
    }

    private long recordInterjection(HubRow row, String role, String content, String turnKey, boolean captureMemory) {
        String kind = "user".equals(role) ? ChatMessageStore.KIND_STEER : ChatMessageStore.KIND_STEER_REPLY;
        long id = messages.insert(row.id(), role, content, null, null, null, kind, turnKey);
        if (id <= 0) {
            throw new IllegalStateException("会话补充消息落库失败");
        }
        try {
            if (captureMemory) recordMemoryMessage(row, id, role, content);
        } catch (RuntimeException error) {
            log.warn("会话补充消息已落库，记忆投影写入失败: messageId={}", id, error);
        }
        return id;
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
        return recordChatMessage(row, "user", input, null, null, null, null);
    }

    @Override
    public long recordReply(HubRow row, String content, String reasoning, String trace,
                            String rounds) {
        return recordChatMessage(row, "assistant", content, reasoning, trace, rounds, null);
    }

    private long recordChatMessage(HubRow row, String role, String content, String reasoning, String trace,
                                   String rounds, String turnKey) {
        long id = messages.insert(row.id(), role, content, reasoning, trace, rounds, null, turnKey);
        recordMemoryMessage(row, id, role, content);
        return id;
    }

    private void recordMemoryMessage(HubRow row, long id, String role, String content) {
        if (!sessions.memoryOff(row.id())) {
            memory.onChatMessage(eventScope(row), id, role, row.title(), content);
        }
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
