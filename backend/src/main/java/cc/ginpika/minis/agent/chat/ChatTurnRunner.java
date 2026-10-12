package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.agent.chat.ChatService.ChatCancelledException;
import cc.ginpika.minis.agent.chat.ChatService.StreamSink;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.runtime.AgentRuntime;
import cc.ginpika.minis.agent.runtime.TaskCheckpointService;
import cc.ginpika.minis.agent.runtime.ToolExecutionRecorder;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.memory.MemoryStage;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.core.tool.heartbeat.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DelegatePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DeletePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.UpdatePlanTool;
import cc.ginpika.minis.agent.chat.turn.History;
import cc.ginpika.minis.agent.chat.turn.Turn;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.config.ContextProps;
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
import java.util.ArrayList;
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

/**
 * 一次 Turn 的执行侧：消息正本落 SQLite，循环交给 Agent 内核跑。
 *
 * <p>从 {@link ChatService} 整体搬出（行为零变化）：{@link ChatService} 从此只做门面转发，本类才是
 * 「跑一次生成」的落点。之所以这么切——控制器要的是一个分配器，而不是一个既握 22 个依赖、又跑循环的类。
 *
 * <p><b>循环不在本类</b>：模型往返与工具批收在 {@link AgentRuntime}（与子代理 / 心跳共用同一条内核）。
 * 本类只做三件事：备齐素材（会话、历史、工具面，见 {@link #requestBody}）、把过程接到流式与留痕上
 * （见 {@link AgentRuntime.Listener}）、把结果落库。
 *
 * <p>归属：M0 单用户，不区分 owner。历史全量带入上下文（个人场景会话长度有限）。
 *
 * <p>知识库工具：search_vector_chunks（三路召回 chunk：关键词+向量+图谱，附带图谱结构证据）与
 * 知识图谱查询（search_graph_entities 按关键词/语义查找实体、query_entity_relations 展开某实体的一跳关系、
 * query_entity_path 查询两实体间的最短路径、explore_subgraph 以若干实体为 seed 做 PPR 扩散
 * 一次取回多跳相关子图）；
 * 另提供图谱写入与编辑（upsert_node / upsert_edge 新增实体与关系；
 * edit_entity_props / edit_relation_props 增改删实体/关系的 KV 属性；
 * delete_graph_item 删除实体（连带其关系）或一条关系），
 * 以及文档工具（search_documents_by_title 按关键词匹配标题查文档清单；
 * read_document 按 id + 每页字符数 + 页码实时分页读正文，让模型自己决定读多少、读哪里；
 * create_document 把一段 markdown 正文新建为知识库文档，按标题/段落自动分块入库）。
 * 模型可在回答前调用，命中的 chunk / 图谱片段作为 tool 消息注入本轮上下文指导推理。
 * 每轮的思考与每次工具调用会作为结构化分段（trace）随 assistant 消息落库，供前端分阶段折叠回放。
 *
 * <p>历史回放默认只带 role + content；会话开启 context_trace（「…」菜单开关）时，assistant 的历史
 * 按原生协议回放——该轮的工具往返（assistant 的 tool_calls 与按 tool_call_id 配对的 tool 结果，
 * 落库于 chat_message.tool_rounds）连同 reasoning_content 一并回放（超限的思考 / tool 结果 /
 * 调用参数截断留痕），协议闭合、不向 content 注入任何标记文本。这段组装自 STEP 2 起收敛进上下文
 * 平面（见 {@code agent.chat.turn.History}），本类只负责备齐素材后交给它。
 *
 * <p><b>与圆桌的关系</b>：多智能体那条流（{@code agent.roundtable.RoundtableChatService}）<b>不再继承本类</b>——
 * 它改为实现 {@link ChatExecution}（会话 / 消息落点、上下文组装、工具分发的可替换点），再交给本类
 * 同一个执行器跑；循环、流式、停止、留痕一字不改。于是本类不再是别人的基类，而是「一次会话执行」
 * 的执行器本身。
 */
@Service
public class ChatTurnRunner implements ChatExecution {

    private static final Logger log = LoggerFactory.getLogger(ChatTurnRunner.class);

    private static final int TITLE_MAX = 20;

    /** 估算误差的安全余量（token）：估算器是近似的，贴着预算发请求容易越界 */
    private static final long SAFETY_TOKENS = 1024;

    /** 由潜意识模块执行的工具：任务计划那一套（派计划 + 立改撤编制） */
    private static final Set<String> PLAN_TOOLS = Set.of(
            DelegatePlanTool.NAME, CreatePlanTool.NAME, UpdatePlanTool.NAME, DeletePlanTool.NAME);

    /** 由子代理模块执行的工具：派一个匿名子代理 + 按 id 翻任意一次执行的过程 */
    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            DelegateSubagentTool.NAME, ReadSubagentRunTool.NAME);

    /** Agent 内核：本次生成的循环（模型往返 + 工具批 + 分段）由它跑，本类只备素材、接过程、落结果 */
    private final AgentRuntime runtime;
    /** 工具执行记录器：每次工具调用落一条生命周期记录并发事件（设计一 §五-2） */
    private final ToolExecutionRecorder toolExecs;
    private final SettingsService settings;
    protected final HubSessionStore sessions;
    protected final ChatMessageStore messages;
    protected final ObjectMapper om;
    protected final PromptManager prompts;
    /** 成员提示词：首页会话选了某位成员时，人格取这位成员自己的那一套（见 {@link #systemFor}） */
    private final AgentPromptService peerPrompts;
    protected final ToolManager tools;
    protected final KnowledgeToolbox toolbox;
    private final HeartbeatTool heartbeat;
    private final SubagentTool subagent;
    /** 命令行终端：多智能体那条流要按成员绑定的工作空间收窄授权，故与 tools 一样对子类放开 */
    protected final TerminalService terminal;
    private final MemoryWriter memory;
    /** 记忆攒批缓冲（STEP M2）：轮次收尾时把本轮对话 push 进缓冲，攒够 / 空闲后合并提炼（异步、失败隔离） */
    private final MemoryIngest memoryIngest;
    /** 项目（memory-v5）：会话所属项目决定记忆归属与 role-play 隔离（narrative → 不升画像） */
    private final ProjectStore projects;
    /** 会话自动命名（§13）：首条输入后异步生成标题（异步、失败降级） */
    private final SessionTitleService sessionTitles;
    /** 上下文平面配置：逼近窗口时裁历史的开关，以及给模型输出预留的 token（输入输出共用窗口） */
    private final ContextProps contextProps;
    /** Turn 正本：提问与回答的显式配对，并挂上会话任务（见 {@link TurnStore}） */
    private final TurnStore turnStore;
    /** 会话任务：每次生成落一条 kind=session 的任务（身份 / 状态 / 可恢复），见 {@link ChatTaskRunner} */
    private final ChatTaskRunner chatTasks;
    /** 上下文平面：本次请求体由它按多路 {@code ContextProvider} 组装（STEP 2 从本类抽出的独立子系统） */
    private final ContextBuilder context;
    /** 会话历史（Turn 序列）：原始上下文预览借它把请求体按 Turn 切片（见 {@link #rawContext}） */
    private final History history;
    /** 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（见 {@link ToolValidator}） */
    private final ToolValidator validator;
    /** 待办清单（STEP 4）：plan-of-record 的落点（见 {@link TodoService}） */
    private final TodoService todo;
    /** 会话事件总线（STEP 4）：「有新消息 / 答完了」即时推给前端，取代 4~5s 轮询（见 {@link SessionEventBus}） */
    private final SessionEventBus events;
    /** 在途生成锁（STEP 4）：key=会话 id；同一会话同一时刻只允许一次生成（见 {@link #runChat}） */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();
    /** 工作集（memory-v6 §13）：工作状态的读写点——轮末落检查点、收尾蒸馏 */
    private final WorkingSetService workingSet;
    /** 任务检查点（工作状态的一部分）：把「做到哪一步」落到 task.memory_checkpoint（修 D11） */
    private final TaskCheckpointService taskCheckpoints;
    private final MemoryProps memoryProps;

    /* 下面这些 public 方法与几个常量同为一组「可替换点」（见 {@link ChatExecution}）：首页对话用
       本类自己的实现；多智能体那条流实现同一个接口、换成自己的落点与组装方式，但整条生成过程
       （工具往返、trace、停止标志）一字不改地复用。 */

    /**
     * 进行中的流式生成 → 各自的停止标志。
     *
     * <p>同一会话可以有多条在跑（圆桌一次「人插话」可能在上一轮尚未退出时就已开跑新的），
     * 所以按会话存的是<b>一组</b>标志而不是一个：停止按钮置位全部，各自的 finally 只摘掉自己那面，
     * 先结束的那条因此不会把仍在跑的那条的停止能力一并摘掉（原先是单槽位，正是这样失效的）。
     */
    private final ConcurrentHashMap<String, Set<AtomicBoolean>> activeStops = new ConcurrentHashMap<>();

    public ChatTurnRunner(AgentRuntime runtime, SettingsService settings,
                          HubSessionStore sessions, ChatMessageStore messages,
                          ObjectMapper om, PromptManager prompts, ToolManager tools,
                          KnowledgeToolbox toolbox, HeartbeatTool heartbeat,
                          SubagentTool subagent, TerminalService terminal, MemoryWriter memory,
                          MemoryIngest memoryIngest, ProjectStore projects,
                          SessionTitleService sessionTitles,
                          ChatTaskRunner chatTasks, ContextBuilder context, ContextProps contextProps,
                          TurnStore turnStore, History history,
                          ToolValidator validator, TodoService todo, SessionEventBus events,
                          ToolExecutionRecorder toolExecs, AgentPromptService peerPrompts,
                          WorkingSetService workingSet, TaskCheckpointService taskCheckpoints,
                          MemoryProps memoryProps) {
        this.runtime = runtime;
        this.toolExecs = toolExecs;
        this.settings = settings;
        this.sessions = sessions;
        this.messages = messages;
        this.om = om;
        this.prompts = prompts;
        this.peerPrompts = peerPrompts;
        this.tools = tools;
        this.toolbox = toolbox;
        this.heartbeat = heartbeat;
        this.subagent = subagent;
        this.terminal = terminal;
        this.memory = memory;
        this.memoryIngest = memoryIngest;
        this.projects = projects;
        this.sessionTitles = sessionTitles;
        this.chatTasks = chatTasks;
        this.context = context;
        this.contextProps = contextProps;
        this.turnStore = turnStore;
        this.history = history;
        this.validator = validator;
        this.todo = todo;
        this.events = events;
        this.workingSet = workingSet;
        this.taskCheckpoints = taskCheckpoints;
        this.memoryProps = memoryProps;
    }

    /** 发问：sessionId 缺省 → 最近会话（无则先建）；modelId/promptId 缺省 → 设置页默认项；
     *  user/assistant 消息均落本地库 */
    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId) {
        return runChat(this, hubId, input, modelId, promptId, null);
    }

    /**
     * 流式发问：与 {@link #chat} 同流程，另外把各阶段实时回调给 sink——user 落库后的
     * meta（供前端定位回退区间）、每轮开始的 round（工具轮边界，前端据此清掉上一轮的正文）、
     * 以及模型逐段产出的 delta（reasoning/content，用于实时思维链与逐字渲染）。
     * 返回最终回复，由调用方推 done 事件。
     */
    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         StreamSink sink) {
        return runChat(this, hubId, input, modelId, promptId, sink);
    }

    /**
     * 以指定的「可替换点」跑一次生成：多智能体那条流走这条——它自己实现 {@link ChatExecution}，
     * 再交给同一个执行器跑，循环、流式、停止、留痕一字不改（见 {@code RoundtableChatService}）。
     */
    public ChatDtos.ChatReply chatStream(ChatExecution exec, String hubId, String input,
                                         String modelId, String promptId, StreamSink sink) {
        return runChat(exec, hubId, input, modelId, promptId, sink);
    }

    /**
     * 标记该会话进行中的流式生成不再需要（前端「停止」按钮）：生成线程在下一个增量或
     * 轮次边界中断，停止等待/消费 LLM 响应；已产出的部分回答照常落库。
     *
     * @return 是否存在进行中的流式生成并被标记
     */
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

    /** 登记一面新的停止标志（同会话可并存多条，见 {@link #activeStops}） */
    private AtomicBoolean registerStop(String hubId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        activeStops.computeIfAbsent(hubId, k -> ConcurrentHashMap.newKeySet()).add(flag);
        return flag;
    }

    /** 停止标志已置位则抛 {@link ChatCancelledException}：沿 readStream 调用栈冒泡，终止上游读取 */
    private static void ensureRunning(AtomicBoolean stopFlag) {
        if (stopFlag != null && stopFlag.get()) {
            throw new ChatCancelledException();
        }
    }

    private ChatDtos.ChatReply runChat(ChatExecution exec, String hubId, String input,
                                       String modelId, String promptId, StreamSink sink) {
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

    /**
     * {@link #runChat} 取得在途锁之后的主体：备齐素材、把过程接到流式与留痕上、把结论与过程落库。
     *
     * <p>循环本身已收进 {@link AgentRuntime}；这里保留的是「这一次会话生成」特有的东西——
     * 停止标志、会话任务、能力授权、子代理开批/收批、取消时把半截回答落库、答完推事件。
     */
    private ChatDtos.ChatReply runChatInner(ChatExecution exec, HubRow row, ModelTarget target,
                                            String input, String modelId, String promptId,
                                            StreamSink sink) {
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
        long turnStart = System.currentTimeMillis();
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
        /* 记忆 v2（STEP M2）：首页对话轮次收尾，把本轮对话 push 进攒批缓冲
           （攒够 / 空闲后合并提炼；异步、失败隔离；临时会话不进记忆） */
        if (exec.turnTracked() && !sessions.memoryOff(row.id())) {
            /* memory-v6 §7.3：写侧作用域与本轮圈定的读侧是同一份（谁 × 项目 × 会话 × 身份），
               不再各构造一份；创作类项目身份为 FICTION → 写侧 source_kind=fiction 且永不升画像 */
            memoryIngest.tap(row.id(), memScope, input, result.content(),
                    cc.ginpika.minis.ai.memory.MemoryEvidence.ref("chat_message", userMsgId, input));
            /* memory-v6 §4.4 / D11：把「工作到哪一步」落成任务检查点（工作状态，不是长期记忆）；
               同时收尾蒸馏更早的工作集——本轮刚记的临时结论留到下一次收尾再整理（§13、T12） */
            if (taskId > 0) {
                try {
                    taskCheckpoints.save(taskId, workingSet.checkpointDigest(memScope));
                    // 普通 Turn 完成仅保存检查点；蒸馏由明确的会话收尾操作触发。
                } catch (RuntimeException e) {
                    log.debug("工作集收尾失败（不影响主流程）：{}", e.getMessage());
                }
            }
        }
        /* 会话自动命名（§13）：标题仍空时，用首条输入异步生成概括标题
           （失败降级为「首条输入截断」兜底，且不覆盖手动命名）。
           只传用户首条提问：命名用固定提示词，不吃会话 / 成员的「系统提示词」，
           也不喂助手回复——避免用户提示词越界把标题带偏 */
        if (exec.turnTracked()) {
            String currentTitle = sessions.findById(row.id()).map(HubRow::title).orElse(null);
            if (currentTitle == null || currentTitle.isBlank()) {
                sessionTitles.generateAsync(row.id(), input, abbreviate(input));
            }
        }
        /* 答完了：推一条 answered 事件——别的标签页也能即时收到「这个会话答完了」（取代原来的轮询）。
           只对「提问 → 回答」成对的流推（圆桌的成员发言不推，见 turnTracked）：它的节拍是逐位发言，
           推「答完了」既不对味、也会让圆桌页收到多余的会话通知。
           标题现取一次：首条提问可能刚落成标题，手里这份 row 还是旧的，别让通知显示成「未命名会话」 */
        if (exec.turnTracked()) {
            String title = sessions.findById(row.id()).map(HubRow::title).orElse(row.title());
            events.publish(new SessionEventBus.Event(row.id(), SessionEventBus.KIND_ANSWERED,
                    false, title));
        }

        return new ChatDtos.ChatReply(row.id(), userMsgId, result.content(), result.rawResponse(),
                true, null);
    }

    /**
     * 查看会话的原始上下文：按与真实发送完全一致的规则组装一份 /chat/completions 请求体
     * （model + system + 全量历史回放 + tools，见 {@link #requestBody}），但不发送、不落库——
     * 展示「此刻在这个会话再发一条消息时，系统发给大模型接口的内容」。
     *
     * <p>另附每个 Turn 在 {@code messages} 中的切片（{@link ChatDtos.TurnSpan}）：把请求体按
     * 会话的 Turn 架构对齐，前端据此给每个 Turn 挂「请求 / 响应」按钮，回看那一 Turn 当时
     * 真正发出去的每一轮请求体与收到的响应体（{@link #turnCalls}）。
     */
    public ChatDtos.RawContext rawContext(String hubId) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        ModelTarget target = settings.resolveChatModel(row.modelId());
        ContextRequest req = contextRequest(row, target, row.promptId(),
                settings.chatContextWindow(row.modelId()));
        ObjectNode body = context.build(req);
        return new ChatDtos.RawContext(body, turnSpans(body, req));
    }

    /**
     * 某条回答（一个 Turn）的全部 LLM 调用留痕（每一轮的请求体 + 响应体），供会话页
     * 「查看原始上下文」里每个 Turn 的「请求 / 响应」按钮回看该 Turn 当时真正发出去的内容。
     */
    public ChatDtos.TurnCalls turnCalls(String hubId, long replyMessageId) {
        String json = messages.contextOf(hubId, replyMessageId);
        List<ChatDtos.LlmCall> calls = ChatTraceCodec.readCalls(om, json);
        return new ChatDtos.TurnCalls(replyMessageId, calls == null ? List.of() : calls);
    }

    /**
     * 把请求体按 Turn 切片：请求体的 messages = [system?] + 各 Turn 回放的 messages（见 {@link History}）。
     * 用总长对齐出历史起点，再逐个 Turn 推进，得到每个 Turn 的 [from, to) 与回答行 id。
     */
    private List<ChatDtos.TurnSpan> turnSpans(ObjectNode body, ContextRequest req) {
        ArrayNode msgs = (ArrayNode) body.get("messages");
        List<History.TurnMessages> groups = history.turnMessages(req);
        int total = 0;
        for (History.TurnMessages g : groups) {
            total += g.messages().size();
        }
        int cursor = msgs.size() - total; // 跳过前置的 system（由 ContextBuilder 首条挂上）
        List<ChatDtos.TurnSpan> spans = new ArrayList<>();
        for (History.TurnMessages g : groups) {
            int from = cursor;
            int to = from + g.messages().size();
            Turn.Reply reply = g.turn().reply();
            Long replyId = reply == null ? null : reply.messageId();
            spans.add(new ChatDtos.TurnSpan(from, to, g.turn().trigger().source().name(), replyId));
            cursor = to;
        }
        return spans;
    }

    // ---------- 会话域门面：外围模块（潜意识 / 子代理）对 chat 数据的写入入口
    // 会话内容的写入收敛在 {@link ChatThreads}（潜意识投递）与本类的聊天流程（人机对话）；
    // 子代理结论投回经 SubagentReportEvent 事件。唯一例外是潜意识注入器的「最近对话」只读投影，
    // 它直接读 ChatMessageStore ----------

    // ---------- 私有 ----------

    /**
     * 组装一次对话补全的请求体：model + system（身份提示词 + 对话增强 + 在跑的子代理）+
     * 全量历史回放（含调用前刚落库的本次 user）+ 按能力域选出的工具面。
     *
     * <p>真实发送（{@link #runChat}）与「查看原始上下文」（{@link #rawContext}）共用本方法，
     * 保证预览与实际发出去的请求体完全一致。promptId 为本次发送指定的身份版本
     * （null 回退全局绑定）。
     *
     * <p>STEP 2 起组装本身收敛进上下文平面（{@code agent.context.ContextBuilder} 与各路 provider），
     * 这里只把「身份提示词、会话、工具面」备齐后交给它；历史回放与在跑子代理进度分别由
     * {@code agent.chat.turn.History} / {@code ActiveSubagentProvider} 现取现拼，规则与抽离前逐字一致。
     *
     * <p>工具面全量常驻：CHAT 面的工具一次全给模型。曾按「一句话里的关键词」挑域（能力平面 STEP 3），
     * 已移除——那会让模型在长任务中途突然失去工具，也会让提示前缀缓存永远命不中。
     *
     * <p>{@code contextWindow} 是本次模型的上下文窗口，交给上下文平面判断「是否已接近临界、该裁历史了」
     * （见 {@code agent.chat.turn.History}）；只有真实发送与预览都走同一条路径，预览才和实发逐字一致。
     *
     * <p><b>可替换点</b>：多智能体那条流覆盖本方法，把 system 换成「成员人格 + 协作规程」、
     * 全部历史压成唯一一条 user「公开记录 + 此刻局面」、工具面换成 PEER
     * （见 {@code agent.roundtable.RoundtableChatService}）。
     */
    @Override
    public ObjectNode requestBody(HubRow row, ModelTarget target, String promptId,
                                 int contextWindow) {
        return context.build(contextRequest(row, target, promptId, contextWindow));
    }

    /**
     * 备齐一次对话补全的上下文素材：本会话最近一条用户输入（能力路由判据 + 检索查询）、
     * 身份提示词、按能力域选出的工具面，以及历史 token 预算。
     *
     * <p>真实发送（{@link #requestBody}）与「查看原始上下文」（{@link #rawContext}）共用本方法，
     * 于是预览与实发逐字一致；{@code rawContext} 另拿同一份 {@link ContextRequest} 去
     * {@link History#turnMessages} 取逐 Turn 切片（见 {@link #turnSpans}）。
     */
    private ContextRequest contextRequest(HubRow row, ModelTarget target, String promptId,
                                          int contextWindow) {
        /* 本会话最近一条用户输入，一份判断两处用：一是能力路由的判据（工具面与鉴权共用，见 chatDomains），
           二是上下文平面的检索查询——知识 / 记忆注入据此召回（见 ContextRequest#userQuery） */
        String userQuery = latestUserText(row);
        String systemBase = systemFor(row, promptId);
        /* 工具面全量常驻：CHAT 面的工具一次全给模型——不再按「一句话里的关键词」挑域。
           理由：按措辞裁剪会让模型在长任务中途突然失去工具（上一句说了「跑命令」、下一句「继续」
           就没了），也会让提示前缀缓存永远命不中。执行权仍由各工具自己的授权把关
           （如终端的目标启用 / 绑定名单）。 */
        ArrayNode toolSchemas = tools.toolsOf(ToolSurface.CHAT);
        /* 身份上下文（memory-v6 §11）：创作类项目 → FICTION（写侧永不入画像；读侧仍注入真实画像但标注） */
        boolean narrative = row.projectId() != null && !row.projectId().isBlank()
                && projects.isNarrative(row.projectId());
        return new ContextRequest(target.model(), systemBase, row.id(),
                row.agentId(), row.projectId(), null,
                row.contextTrace(), userQuery,
                historyBudget(contextWindow, systemBase, toolSchemas), toolSchemas,
                ContextRequest.Surface.CHAT, MemoryIdentity.ofNarrative(narrative));
    }

    /** 本会话跟哪位 Agent 对话：{@code agent_id} 为空 / 'builtin' = 内置 Agent（用全局提示词）。
     *  口径统一在 {@link ChatSessionService#ownerOf}——清单等「按会话归属」的读取侧同此，不得各写一份 */
    private static String agentOf(HubRow row) {
        return ChatSessionService.ownerOf(row.agentId());
    }

    /**
     * 本次对话的 system：内置 Agent 走全局「对话身份」提示词（支持会话级 version 覆盖）；
     * 选了某位成员则取这位成员自己的那一套（{@link AgentPromptService#system}），会话的身份钉住对它不再生效。
     */
    private String systemFor(HubRow row, String promptId) {
        String agentId = agentOf(row);
        return AgentDirectory.isBuiltin(agentId)
                ? prompts.chatSystem(promptId)
                : peerPrompts.system(agentId);
    }

    /**
     * 落这一个 Turn 每一轮 LLM 调用的留痕（请求体 + 响应体）到回答行。
     *
     * <p>只对首页会话写：圆桌那条流（{@code exec != this}）的 {@code chat_message.context}
     * 另有其义（发言当时的请求体，由它自己写入），此处不得覆盖。
     */
    private void persistCalls(ChatExecution exec, long replyId, AgentRuntime.RunState state) {
        if (exec != this) {
            return;
        }
        String json = ChatTraceCodec.writeCalls(om, state.llmCalls());
        if (json != null) {
            messages.updateContext(replyId, json);
        }
    }

    /**
     * 这次历史最多能占多少 token：窗口 − 输出预留 − 安全余量 − system 与工具面的估算。
     *
     * <p><b>输入与输出共用同一个上下文窗口</b>，所以必须先把留给模型输出的那部分扣掉；再扣掉
     * system（身份提示词 + 各路尾段）与工具面 schema——它们同样占输入。扣完剩下的才是历史额度，
     * 历史超过它才降级（见 {@code agent.chat.turn.History}）。
     */
    private int historyBudget(int contextWindow, String systemBase, ArrayNode toolSchemas) {
        long used = contextProps.getReserveOutputTokens() + SAFETY_TOKENS
                + TokenUsageService.estimateTokens(systemBase)
                + TokenUsageService.estimateTokens(toolSchemas == null ? "" : toolSchemas.toString());
        return (int) Math.max(0, contextWindow - used);
    }

    /** 本会话最近一条用户输入（上下文平面的检索查询用它）；没有则空串 */
    private String latestUserText(HubRow row) {
        List<MessageRow> rows = history(row);
        for (int i = rows.size() - 1; i >= 0; i--) {
            if ("user".equals(rows.get(i).role())) {
                return rows.get(i).content();
            }
        }
        return "";
    }

    /**
     * 出站请求的消息形态摘要（<b>只记结构、不记正文</b>，便于安全打印到日志）：
     * tools 条数、每条消息的 role、是否带 tool_calls、reasoning_content 的「缺 / 空 / 字数」、
     * 以及是不是 tool 结果。供应商 400 时靠它一眼看出缺的是哪个字段。
     */
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

    /** 执行一次工具调用，返回给模型的 tool 消息内容（知识库工具见 {@link KnowledgeToolbox}；
     *  计划那套转交 {@link HeartbeatTool}，子代理那套转交 {@link SubagentTool}）。
     *  子代理走对话侧入口 {@link SubagentTool#runChatTool}：派活非阻塞，当场只回受理回执，
     *  结论跑完作为一条新消息投回 hubId 这个会话——主会话因此不被子代理占住
     *
     *  <p>执行前先过参数自检（{@link ToolValidator}）：模型可能凭幻觉调一件不存在的工具，参数不自洽的
     *  当场回一句可读理由、不落执行。执行权的把关在各工具自己那里（如终端的目标启用 / 绑定名单）。
     *
     * <p><b>可替换点</b>：多智能体那条流覆盖本方法，多接一条状态机工具的分发
     *  （propose_end / go_silent / interject，见 {@code agent.roundtable.RoundtableChatService}）。
     */
    @Override
    public String runTool(JsonNode call, String batchRunId, String hubId) {
        String name = call.path("function").path("name").asText("");
        /* 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（只读放行），拒了不落执行 */
        String invalid = validator.validate(name,
                call.path("function").path("arguments").asText(""));
        if (invalid != null) {
            return invalid;
        }
        if (TodoService.TOOLS.contains(name)) {
            /* 个人清单归属当前 Agent：首页 = 内置 Agent，圆桌执行态 = 当前发言成员（见 AgentScope）。
               刻意与下方 agentId（含内置兜底）分开——TodoProvider 回灌时用同一口径 AgentScope#get()，
               两边必须逐字一致，否则写进去的清单读不回来 */
            return todo.runTool(call, hubId, AgentScope.get());
        }
        /* 归属 Agent：首页对话 = 内置 Agent；圆桌那条流由 RoundtableChatService 在 AgentScope 里设好成员身份 */
        String agentId = AgentScope.get() == null ? AgentDirectory.BUILTIN_ID : AgentScope.get();
        if (PLAN_TOOLS.contains(name)) {
            return heartbeat.runTool(agentId, call);
        }
        if (SUBAGENT_TOOLS.contains(name)) {
            return subagent.runChatTool(agentId, call, batchRunId, hubId);
        }
        if (TerminalService.handles(name)) {
            return terminal.run(call, TerminalService.SOURCE_CHAT).content();
        }
        return toolbox.run(call, toolSource()).content();
    }

    /**
     * 这次工具调用来自哪条流（RAG 召回留痕的 source）：首页对话为 {@code chat}。
     *
     * <p><b>可替换点</b>：多智能体那条流覆盖为 {@code multiagent}，好让潜意识页的「RAG 召回」
     * 子页能按来源分开看。
     */
    @Override
    public String toolSource() {
        return "chat";
    }

    /**
     * 这次生成是否落一条会话任务（见 {@link ChatTaskRunner}）。首页对话与多智能体那条流都跟踪：
     * 圆桌的一次成员发言同样是一次真实执行，失败时应当留痕（{@code task.error}）。
     *
     * <p><b>可替换点</b>：多智能体那条流覆写 {@link #turnTracked()} 为 {@code false}——它跟踪任务，
     * 但不记 Turn（它没有一条提问消息作配对的一端，见 {@link ChatExecution#turnTracked()}）。
     */
    @Override
    public boolean taskTracked() {
        return true;
    }

    /**
     * 这次生成是否受「同一会话不并发」的在途锁约束（见 {@link #runChat}）。
     *
     * <p><b>可替换点</b>：圆桌那条流自己按话题状态机串行安排发言，不与人类似的「同一会话连续发问」
     * 场景，且它自有一套节拍——不受这把锁约束，保持旧行为零变化。
     */
    @Override
    public boolean singleFlight() {
        return true;
    }

    /**
     * 取会话：给定 id 则按 id 取（不存在=404），缺省取最近一个（无则先建）。
     *
     * <p><b>可替换点</b>：多智能体那条流覆盖本方法与 {@link #insertNew()}，换成自己的会话表，
     * 但把行值包成同一个 {@link HubSessionStore.HubRow}——于是整条流循环不必知道有两套会话。
     */
    @Override
    public HubRow resolveOrCreate(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return sessions.latest().orElseGet(this::insertNew);
        }
        return sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
    }

    /** 新建一个会话并返回它；多智能体那条流覆盖为写自己的会话表（行值仍包成 HubRow） */
    @Override
    public HubRow insertNew() {
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null);
        return sessions.findById(hubId)
                .orElseThrow(() -> new IllegalStateException("hub_session 落库失败: " + hubId));
    }

    // ---------- 可替换点：落点与投影（多智能体那条流换掉这几处，流循环本身不动） ----------

    /** 某会话的既存消息（组装上下文与秘书总结共用）；多智能体那条流换成自己的消息表 */
    @Override
    public List<MessageRow> history(HubRow row) {
        return messages.listBySession(row.id());
    }

    /** 落一条用户输入（留痕 + 记忆投影），返回消息行 id */
    @Override
    public long recordUserInput(HubRow row, String input) {
        long id = messages.insert(row.id(), "user", input, null, null, null, null, null);
        if (!sessions.memoryOff(row.id())) {
            memory.onChatMessage(eventScope(row), id, "user", row.title(), input);
        }
        return id;
    }

    /** 落一条助手回复（留痕 + 记忆投影），返回消息行 id */
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

    /** 会话有动静（新消息落库后）：刷新时间戳并按需回填标题 */
    @Override
    public void touchSession(HubRow row, String title) {
        sessions.touch(row.id(), title);
    }

    /** 记住本次使用的模型 / 身份版本（会话级配置，下次进入该会话自动回显）；Agent 不由此处改（走「…」菜单的 updateConfig） */
    @Override
    public void rememberConfig(HubRow row, String modelId, String promptId) {
        sessions.updateConfig(row.id(), modelId, promptId, null, null, null, null, null);
    }

    /** 标题截断（首条发言取前若干字） */
    private static String abbreviate(String input) {
        String s = input.strip();
        return s.length() <= TITLE_MAX ? s : s.substring(0, TITLE_MAX);
    }
}
