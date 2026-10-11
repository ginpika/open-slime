package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.ChatExecution;
import cc.ginpika.minis.agent.chat.ChatService.StreamSink;
import cc.ginpika.minis.agent.chat.ChatTurnRunner;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.member.AgentAnchorService;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.agent.roundtable.RoundtableMessageStore;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableSessionStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.graph.ReadMyAnchorTool;
import cc.ginpika.minis.ai.core.tool.multiagent.AddTodoTool;
import cc.ginpika.minis.ai.core.tool.multiagent.AskUserTool;
import cc.ginpika.minis.ai.core.tool.multiagent.CompleteTodoTool;
import cc.ginpika.minis.ai.core.tool.multiagent.GoSilentTool;
import cc.ginpika.minis.ai.core.tool.multiagent.InterjectTool;
import cc.ginpika.minis.ai.core.tool.multiagent.ProposeEndTool;
import cc.ginpika.minis.ai.core.tool.multiagent.RequestDiscussionTool;
import cc.ginpika.minis.ai.core.tool.multiagent.SpeakTool;
import cc.ginpika.minis.ai.core.tool.multiagent.StartWorkTool;
import cc.ginpika.minis.ai.core.tool.multiagent.UpdateTodoTool;
import cc.ginpika.minis.ai.core.tool.plan.WriteTodosTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.terminal.ListTerminalsTool;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.RoundtableProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 多智能体的发言流：<b>复用首页那条对话流</b>，只换掉「跟谁说话、说的话落到哪」。
 *
 * <p>为什么要复用执行器而不是复制一份：一次发言里真正值钱的不是那几十行循环，而是循环背后的一整套东西——
 * 流式增量与停止标志、工具往返与原生消息回放、思维链分段（trace）、取消时把半截回答落库、
 * 端到端的错误处理。这几件事在 {@link ChatTurnRunner} 里已经写对了，抄一份出来只会在
 * 两个地方各自演进。所以这里只实现执行器提炼出的那几个「可替换点」（见 {@link ChatExecution}）：
 * <ul>
 *   <li>{@link #resolveOrCreate} / {@link #insertNew} / {@link #touchSession} / {@link #rememberConfig}
 *       ——会话落到 {@code hub_session}（{@code mode='multi-agent'}，名册在 {@code member_ids}）；</li>
 *   <li>{@link #recordUserInput} / {@link #recordReply} / {@link #history}
 *       ——消息落到 {@code chat_message}（{@code role='peer'} + 发言成员那几列；成员发言不写记忆投影：
 *       多智能体与其他体系隔离）；</li>
 *   <li>{@link #requestBody}——system 换成「成员人格 + 协作规程」（对同一成员恒定），
 *       全部历史压成唯一一条 user「公开记录 + 此刻局面」（请求里没有 assistant 历史）；
 *       工具面换成只读检索 + 发言工具 + 状态机工具（{@link ToolSurface#PEER}），
 *       再加上这位成员自己声明的额外工具与它绑定了工作空间时的终端工具；</li>
 *   <li>{@link #runTool}——多接一条发言工具 / 状态机工具 / 终端工具的分发
 *       （终端只在它绑定的工作空间上执行，见 {@link #terminalTool}）。</li>
 * </ul>
 * 流循环、流式推送、停止、留痕格式全部原样沿用，执行器一行未改。
 *
 * <p><b>那次发言的落点靠 ThreadLocal 传递</b>：{@link #speak} 是圆桌发言的唯一入口
 * （一对一私聊另有一条 {@link #speakSolo}），它在把本类交给执行器（{@code runner.chatStream(this, …)}）之前把「哪次话题、哪位成员、往哪条流上说」放进几个 ThreadLocal，
 * 可替换点与工具分发就地读取。之所以能用：圆桌上同一时刻只有一个人在发言，
 * 而发言工具与状态机工具都不可并行（{@code ToolBatch} 因此不会把它们挪到别的线程）。
 *
 * <p><b>会话里只剩一位成员时走私聊</b>（{@link #speakSolo}）：那不是圆桌，是用户与这位成员一对一。
 * 它复用首页那条普通对话流，但换成它自己的人格提示词、去掉发言与状态机工具、不落话题；
 * 消息仍写 {@code ma_*}，圆桌页因此不必为「一个人」分出一套新界面。
 *
 * <p><b>说话走 speak 工具，正文只是兜底</b>：模型被要求把每一句话放进 {@code speak} 工具里说出来
 * （见 {@link #speakTool}），于是它可以边说边想、一次发言里说上好几段——这是「发言」在圆桌上的正路。
 * 正文那一层信封 {@code {"speech": "..."}} 退居兜底：模型没按新约定走时，仍按老规则把正文解出来
 * 当发言（详见 {@link SpeechSink}）；收尾轮没有工具可用，模型给一个空信封 {@code {"speech": ""}}，
 * 并在这里顺带报出「下一轮发言欲望」（{@code desire}，选人依据，见 {@link DesireSpeakerStrategy}），
 * 这才让循环能正常收束。
 *
 * <p>页面用的会话管理（{@link #sessions} / {@link #create} / {@link #messages} / {@link #delete}）
 * 由本类自己提供，走 {@code ma_*} 一套表；执行器（首页那条流）的
 * {@code list/create/messages/rewind} 仍是 hub_session 那套，多智能体页不使用它们。
 */
@Service
public class RoundtableChatService implements ChatExecution {

    /** 会话模式标记（HubRow.mode）：让两边的人都一眼看出这是圆桌会话，不是首页那个 */
    public static final String MODE = "multi-agent";

    /**
     * 只挂在成员发言这一侧的状态机工具：主对话的 CHAT 面永远拿不到它们。
     *
     * <p>前三件是成员内部的去向（提议结束 / 转沉默 / 抢答）；{@link AskUserTool} 是唯一一个
     * 把手伸到圆桌外面的——它请用户表态，并让整桌停下等人（见 RoundtableTopicService#advance）。
     */
    private static final List<String> STATE_TOOLS = List.of(
            ProposeEndTool.NAME, GoSilentTool.NAME, InterjectTool.NAME, AskUserTool.NAME);

    /**
     * 发言工具：把一句话真正说出口（见 {@link SpeakTool}）。
     *
     * <p>刻意与 {@link #STATE_TOOLS} 分开：它不改变任何状态，只往流上说话，因此不进状态机的分发。
     */
    private static final List<String> SPEAK_TOOLS = List.of(SpeakTool.NAME);

    /**
     * 执行态的入口工具：共享任务区（add / update / complete）与两个开关（开工 / 请讨论）。
     *
     * <p>它们不改变讨论状态机，而是把「要做的事」与「该开工 / 该再议」变成<b>任务板条目与运行时事件</b>——
     * 于是讨论态与执行态通过「共享状态 + 事件」耦合，而不是互相直接调用（依赖方向保持单向：
     * 讨论这条流发事件，运行时在全局层面接住）。
     */
    private static final List<String> WORK_TOOLS = List.of(
            AddTodoTool.NAME, UpdateTodoTool.NAME, CompleteTodoTool.NAME,
            StartWorkTool.NAME, RequestDiscussionTool.NAME);

    /**
     * 成员「自己埋头做」的通道：派一个子代理独立去调研（跑完投回本会话并发运行时事件）。
     *
     * <p>{@code delegate_subagent} 的 {@code agentsMayCall()=false}（子代理是「大模型自己那一层」的事），
     * 所以这里用 {@code grantedToolsOf} 绕过它，<b>只对圆桌成员单独放行</b>——这是「成员自己埋头做、
     * 做完通知彼此」闭环的载体。
     */
    private static final List<String> SUBAGENT_TOOLS =
            List.of(DelegateSubagentTool.NAME, ReadSubagentRunTool.NAME);

    /**
     * 终端工具：只有在这位成员<b>绑定了工作空间</b>时才授予它，并且只能在那几台上执行。
     *
     * <p>它们平时不给成员（{@code agentsMayCall()=false}，见各工具类）：任意命令执行权是
     * 「当场决定的那一层」的本领。人给某位成员绑了工作空间，才算对它单独放行——
     * 于是取定义要绕过 {@code agentsMayCall}（见 {@link ToolManager#grantedToolsOf}），
     * 执行时再把授权名单交给 {@link TerminalService}。
     */
    private static final List<String> TERMINAL_TOOLS =
            List.of(RunCommandTool.NAME, ListTerminalsTool.NAME);

    /** 估算误差的安全余量（token）：估算器是近似的，贴着预算发请求容易越界（与首页那条流同一口径） */
    private static final long SAFETY_TOKENS = 1024;

    /** 执行器：本次发言由它跑（循环、流式、停止、留痕都在它那儿）；本类只提供「可替换点」 */
    private final ChatTurnRunner runner;
    private final ObjectMapper om;
    private final ToolManager tools;
    /** 终端：成员绑定工作空间时的命令执行与授权名单 */
    private final TerminalService terminal;
    /** 上下文平面配置：输出预留 token（输入输出共用窗口，算公开记录预算时要扣掉） */
    private final ContextProps contextProps;

    private final RoundtableSessionStore maSessions;
    private final RoundtableMessageStore maMessages;
    private final RoundtableTopicStore topics;
    /** 话题事件正本：删会话时要抢在话题行之前清掉（它的 topic_id 有外键指着 roundtable_topic） */
    private final RoundtableTopicEventStore topicEvents;
    private final AgentDirectory directory;
    private final RoundtableContextBuilder context;
    private final AgentAnchorService peerAnchors;
    private final TopicStateMachine machine;
    private final RoundtableProps maProps;
    /** 会话级发言版本：人的新话 +1；一次发言提交前比对，过期则丢弃（设计一 §五-1） */
    private final RoundtableRevision revisions;
    /** 共享任务区：讨论态与执行态之间的任务板（成员工具 add/update/complete 读写它） */
    private final RoundtableTodoStore taskBoard;
    /** 个人计划清单（chat_todo）：执行态里成员用 write_todos 维护「自己这条活分几步做」，按成员互不串写 */
    private final TodoStore todos;
    /** 运行时事件总线：任务变更 / 开工 / 请讨论从这条流发出，由运行时在全局层面接住 */
    private final RuntimeEventBus runtimeEvents;
    /** 执行态留痕：每条 TODO 的每次执行落一条过程（思考链 / 过程分段 / 工具往返 / 请求体） */
    private final RoundtableWorkRunStore workRuns;

    /** 本次发言的落点：哪次话题、哪位成员（{@link #speak} 期间有效） */
    private final ThreadLocal<String> speakTopic = new ThreadLocal<>();
    /** 本次发言属于哪个会话（工作工具落任务板要用；{@link #speak} / {@link #speakSolo} 期间有效） */
    private final ThreadLocal<String> speakSession = new ThreadLocal<>();
    /** 执行态：这次不是「发言」，而是某成员在执行一条 TODO（{@link #executeTodo} 期间有效） */
    private final ThreadLocal<RoundtableTodoStore.TodoRow> workTodo = new ThreadLocal<>();
    /** 本次执行的留痕 id（{@link #executeTodo} 开，{@link #recordReply} 收） */
    private final ThreadLocal<Long> workRunId = new ThreadLocal<>();
    private final ThreadLocal<String> speakPeer = new ThreadLocal<>();
    /** 本次发言落库后的消息 id（{@link #recordReply} 写入，{@link #speak} 读走） */
    private final ThreadLocal<Long> speakReplyId = new ThreadLocal<>();
    /** 本次发言开始生成时的会话版本（{@link #speak} 写入，{@link #recordReply} 提交前比对） */
    private final ThreadLocal<Integer> speakBaseRevision = new ThreadLocal<>();
    /** 本次发言是否已过期（版本校验失败 → 丢弃，不落成正式发言） */
    private final ThreadLocal<Boolean> speakStale = new ThreadLocal<>();
    /** 本次发言组装出的请求体（{@link #requestBody} 写入，{@link #recordReply} 读走存进消息） */
    private final ThreadLocal<String> speakContext = new ThreadLocal<>();
    /** 本次发言的流出口（{@link #speak} 写入，{@link #speakTool} 与 {@link #recordReply} 读走） */
    private final ThreadLocal<SpeechSink> speakSink = new ThreadLocal<>();

    /**
     * 本次发言是不是「一对一私聊」：会话里只剩这一位成员时（见 {@link #speakSolo}）为真。
     *
     * <p>它把这条流切回首页那套普通对话——system 只取成员自己的人格（{@link RoundtableContextBuilder#persona}）、
     * 历史按 user/assistant 原样回放、正文直接流出去，不落话题、也不给发言 / 状态机工具；
     * 落点仍在 {@code ma_*}（见 {@link #recordUserInput} / {@link #recordReply}），圆桌页照常读到。
     */
    private final ThreadLocal<Boolean> solo = new ThreadLocal<>();

    public RoundtableChatService(ChatTurnRunner runner, ObjectMapper om, ToolManager tools,
                         TerminalService terminal, RoundtableSessionStore maSessions,
                         RoundtableMessageStore maMessages, RoundtableTopicStore topics,
                         RoundtableTopicEventStore topicEvents, AgentDirectory directory,
                         RoundtableContextBuilder context, AgentAnchorService peerAnchors,
                         TopicStateMachine machine, RoundtableProps maProps, ContextProps contextProps,
                         RoundtableRevision revisions, RoundtableTodoStore taskBoard,
                         TodoStore todos, RuntimeEventBus runtimeEvents,
                         RoundtableWorkRunStore workRuns) {
        this.runner = runner;
        this.om = om;
        this.tools = tools;
        this.terminal = terminal;
        this.maSessions = maSessions;
        this.maMessages = maMessages;
        this.topics = topics;
        this.topicEvents = topicEvents;
        this.directory = directory;
        this.context = context;
        this.peerAnchors = peerAnchors;
        this.machine = machine;
        this.maProps = maProps;
        this.contextProps = contextProps;
        this.revisions = revisions;
        this.taskBoard = taskBoard;
        this.todos = todos;
        this.runtimeEvents = runtimeEvents;
        this.workRuns = workRuns;
    }

    // ---------- 一次发言 ----------

    /**
     * 停止该会话进行中的流式生成：生成状态在执行器（{@link ChatTurnRunner}）那里，这里只转发。
     */
    public boolean requestStop(String sessionId) {
        return runner.requestStop(sessionId);
    }

    /**
     * 一次发言的结果：结论 + 落库后的消息 id（话题事件要按它指回正本）+ 这一次报出的下一轮发言欲望
     * + 是否过期 + 待发布的发言段落。
     *
     * <p>{@code desire} 是模型在收尾信封里顺带给的（0~10，见 {@link SpeechEnvelope}）；没给、或没按
     * 约定输出时为 null。它是选人的依据（见 {@link DesireSpeakerStrategy}），由 {@code RoundtableTopicService}
     * 记进话题事件；本类不判越界，夹紧在落库那一步做。
     *
     * <p>{@code stale}：这次发言是否因版本校验失败而作废（设计一 §五-1）——true 时它没有被落成正式
     * 发言，也不进入本轮对话上下文。
     *
     * <p>{@code utterances}：这次生成攒出的发言段落（<b>尚未发布</b>）。发布由协调器调用
     * {@link #publish} 在版本校验通过后按序完成——「生成」与「发布」解耦，是 P4 轮内并行的前置。
     */
    public record Speech(ChatDtos.ChatReply reply, long messageId, Integer desire, boolean stale,
                         List<String> utterances) {
    }

    /**
     * 让某位成员在某个话题里发一次言：把落点放进 ThreadLocal，交给执行器（{@link ChatTurnRunner}）的流循环去跑。
     *
     * <p>上下文（身份 + 协作规程 + 局面）与工具面由下面被覆盖的组装点现取现拼；
     * 成员的模型优先用它自己配的，其次会话兜底，最后回退设置页默认。
     *
     * <p>正文由 {@link SpeechSink} 攒起来（speak 工具说出的 + 正文信封兜底解出的）；
     * 下游（话题事件、落库、页面）看到的都是人话，不是 {@code {"speech": "..."}}。
     */
    public Speech speak(String topicId, String peerId, StreamSink sink) {
        TopicRow topic = topics.topic(topicId)
                .orElseThrow(() -> new IllegalStateException("话题不存在: " + topicId));
        AgentRow peer = directory.peer(peerId)
                .orElseThrow(() -> new IllegalStateException("成员不存在: " + peerId));
        String model = peer.modelId() != null ? peer.modelId()
                : maSessions.findById(topic.sessionId()).map(RoundtableSessionStore.SessionRow::modelId)
                        .orElse(null);
        speakTopic.set(topicId);
        speakSession.set(topic.sessionId());
        speakPeer.set(peerId);
        speakReplyId.remove();
        speakContext.remove();
        /* 记下开始生成时的会话版本：生成结束、提交之前要拿它与此刻比对（见 recordReply） */
        speakBaseRevision.set(revisions.current(topic.sessionId()));
        speakStale.remove();
        try {
            SpeechSink envelope = new SpeechSink(sink);
            /* 流出口交给 speak 工具：它在工具往返里就地说话（同一线程，见类注释） */
            speakSink.set(envelope);
            /* 圈定当前成员作用域：这条流期间发起的 RAG 召回据此归属到这位成员（见 AgentScope） */
            ChatDtos.ChatReply reply = AgentScope.run(peerId, () -> runner.chatStream(this,
                    topic.sessionId(), "〔" + peer.name() + " 发言〕", model, null, envelope));
            Long id = speakReplyId.get();
            boolean stale = Boolean.TRUE.equals(speakStale.get());
            /* 生成期只入账、不发布（见 SpeechSink#say）：真正推给页面由协调器在版本校验通过后
               调用 publish 按序完成——「不是「生成中边流边发」，而是「生成 → 校验 → 发布」」。 */
            List<String> utterances = stale ? List.of() : envelope.utterances();
            String speech = stale ? "" : String.join("\n\n", utterances);
            /* 收尾信封里顺带报出的发言欲望（先取 utterances 触发收尾，再读 desire）；过期则一并作废 */
            Integer desire = stale ? null : envelope.desire();
            /* 回执里的正文换成攒出来的发言：信封是给后端看的，下游（话题事件、页面）只该看到人话 */
            return new Speech(new ChatDtos.ChatReply(reply.sessionId(), reply.messageId(),
                    speech, reply.rawResponse(), reply.success(), reply.error()),
                    id == null ? -1L : id, desire, stale, utterances);
        } finally {
            speakTopic.remove();
            speakSession.remove();
            speakPeer.remove();
            speakReplyId.remove();
            speakContext.remove();
            speakSink.remove();
            speakBaseRevision.remove();
            speakStale.remove();
        }
    }

    // ---------- 执行态：一项 TODO = 一次 Turn ----------

    /**
     * 执行规程：执行态下挂在成员人格之后的一段说明（讲清「你在干活」而不是「在发言」）。
     */
    private static final String EXECUTION_PROTOCOL = """
            你现在不在讨论，而是在**执行**共享任务板里的一条待办（TODO）。你有自己的工具与检索能力，
            独立把这一步做完即可，不必征求别人同意；但任务板对全桌可见，你做完要如实更新它。

            规矩：
            1. 只做这一条。做完用 complete_todo 写清结论（做了什么、结论是什么、产出在哪）；
               中途发现该拆解或该换人，用 add_todo / update_todo 调整任务板。
            2. 真卡住了（前提不成立、方案有坑、需要别人拍板）——先做一次决策：**找谁**，再标 blocked。
               · 只缺某几位同伴手里的信息、只需这几位拍板 → update_todo 标 blocked 时用 with 写这几位的
                 名字（多个用「、」分隔）：系统只安排你们定向沟通一轮，其余成员不会被叫起来。把你卡在哪、
                 已经试过什么、需要什么写进 reason。
               · 只有用户能定的（要不要做、做到什么程度、边界在哪）→ with 写「用户」：全桌暂停，
                 等用户在会话里发话后再继续。
               · 牵涉多方、要改任务区分工、需要全桌一起定 → with 留空（或填「全员」）：全桌一起议。
               不要硬做没把握的事。
            3. 不要在正文里写长篇汇报：要交代的结论走 complete_todo；正文留空或一句话即可。
            """;

    private static final String EXECUTION_INSTRUCTION = "\n现在请完成上面这一条 TODO。";

    /**
     * 让某位成员执行一条 TODO —— 执行态的一次 Turn。
     *
     * <p>它与讨论态共用同一条执行内核（{@link ChatTurnRunner}：流式、工具往返、留痕、取消），
     * 只是上下文与工具面不同（见 {@link #workBody}）。一次执行落一条会话任务
     * （{@code taskTracked=true}），于是「这一条做没做成」有处可查。
     *
     * @param sink 过程出口；执行态通常不推流，传 null 即可
     */
    public ChatDtos.ChatReply executeTodo(String sessionId, String peerId,
                                          RoundtableTodoStore.TodoRow todo, StreamSink sink) {
        AgentRow peer = directory.peer(peerId)
                .orElseThrow(() -> new IllegalStateException("成员不存在: " + peerId));
        String model = peer.modelId() != null ? peer.modelId()
                : maSessions.findById(sessionId).map(RoundtableSessionStore.SessionRow::modelId)
                        .orElse(null);
        speakSession.set(sessionId);
        speakPeer.set(peerId);
        workTodo.set(todo);
        /* 开一条执行留痕：执行态页点开这条任务时能回放它此刻在干嘛（防死循环的代价是真金白银） */
        workRunId.set(workRuns.start(sessionId, todo.id(), peerId, peer.name()));
        try {
            return AgentScope.run(peerId, () -> runner.chatStream(this, sessionId,
                    "〔执行〕" + todo.title(), model, null, sink));
        } finally {
            speakSession.remove();
            speakPeer.remove();
            workTodo.remove();
            workRunId.remove();
        }
    }

    /**
     * 执行态的请求体：system = 成员人格 + 执行规程；user = 这条 TODO + 任务板快照。
     *
     * <p>没有历史回放：执行是「就事论事」的一次做，不是接着聊。任务板快照让这位成员知道
     * 桌上还有哪些条目、都被谁领了——这正是「共享任务区对其他 Agent 可见」的落地。
     */
    private ObjectNode workBody(HubRow row, ModelTarget target) {
        RoundtableTodoStore.TodoRow todo = workTodo.get();
        ObjectNode body = om.createObjectNode();
        body.put("model", target.model());
        ArrayNode msgs = body.putArray("messages");
        /* 个人计划清单只属于这位成员：与全桌共享任务区（下面 user 里的 board）不同，按成员互不串写。
           与首页会话同一口径：owner = 当前 Agent（见 AgentScope，此处即 speakPeer） */
        StringBuilder system = new StringBuilder(context.persona(speakPeer.get()))
                .append("\n\n").append(EXECUTION_PROTOCOL);
        String mine = TodoStore.render(todos.list(row.id(), AgentScope.get()));
        if (!mine.isBlank()) {
            system.append("\n\n【你的待办清单】按它推进；做完 / 失败一条就用 write_todos 把整份清单重写一遍")
                    .append("（含新状态），别在正文里复述进度：\n").append(mine);
        }
        msgs.addObject().put("role", "system").put("content", system.toString());
        msgs.addObject().put("role", "user").put("content", workPrompt(row.id(), todo));
        body.set("tools", toolSchemas());
        /* 留一份「这次到底发了什么」：随执行落进消息，页面上点开可知它当时看到了什么 */
        speakContext.set(body.toString());
        return body;
    }

    /** 执行态的 user：这一条 TODO 的内容 + 任务板快照 + 一句开工指令 */
    private String workPrompt(String sessionId, RoundtableTodoStore.TodoRow todo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<task id=\"").append(todo.id()).append("\">\n");
        sb.append("标题：").append(todo.title()).append('\n');
        if (todo.detail() != null && !todo.detail().isBlank()) {
            sb.append("说明：").append(todo.detail()).append('\n');
        }
        sb.append("</task>\n\n<board>\n");
        for (RoundtableTodoStore.TodoRow t : taskBoard.list(sessionId)) {
            sb.append("- [").append(t.status()).append("] ").append(t.title());
            sb.append(t.ownerPeer() == null ? "（公共）" : "（" + nameOr(t.ownerPeer()) + "）");
            sb.append('\n');
        }
        sb.append("</board>");
        sb.append(EXECUTION_INSTRUCTION);
        return sb.toString();
    }

    private String nameOr(String peerId) {
        String name = directory.nameOf(peerId);
        return name == null ? peerId : name;
    }

    /**
     * 把一次已生成、已通过版本校验的发言<b>按序发布</b>出去（设计一 §六的两阶段逻辑模型）。
     *
     * <p>发布权刻意留在协调器（{@code RoundtableTopicService.Advance}）手里，而不是 {@code speak} 内部：
     * 于是「生成」与「发布」解耦——并行的、或被取代的生成在发布之前就被挡下，
     * 不可能出现「两位成员的分段交错推给同一条流」这种破坏因果结构的场面。
     *
     * @param speech 生成结果（{@link Speech#utterances()} 是待发布的段落）
     * @param sink   这次发言的出口
     */
    public void publish(Speech speech, StreamSink sink) {
        if (sink == null || speech == null || speech.stale()) {
            return;
        }
        for (String text : speech.utterances()) {
            sink.speech(text);
        }
    }

    /**
     * 一对一私聊：会话里只剩这一位成员时，人说的每句话都是对它一个人说的。
     *
     * <p>这不是圆桌，所以不落话题、不跑状态机，直接走首页那条普通对话流程：system 是它自己那套
     * 「对话」人格（{@link RoundtableContextBuilder#persona}，<b>不含协作规程</b>），历史按 user/assistant
     * 原样回放，正文由增量直接流出。工具面只留检索与它自己声明的那些（见 {@link #toolSchemas}）。
     *
     * <p>消息仍进 {@code ma_*}：人的话与它的回答各一行（{@link #recordUserInput} /
     * {@link #recordReply}），圆桌页照常读到——只是没有话题与状态行。
     */
    public ChatDtos.ChatReply speakSolo(String sessionId, String peerId, String input, StreamSink sink) {
        AgentRow peer = directory.peer(peerId)
                .orElseThrow(() -> new IllegalStateException("成员不存在: " + peerId));
        String model = peer.modelId() != null ? peer.modelId()
                : maSessions.findById(sessionId).map(RoundtableSessionStore.SessionRow::modelId)
                        .orElse(null);
        speakSession.set(sessionId);
        speakPeer.set(peerId);
        solo.set(Boolean.TRUE);
        speakReplyId.remove();
        speakContext.remove();
        speakBaseRevision.set(revisions.current(sessionId));
        speakStale.remove();
        try {
            /* 私聊也是这位成员在说话：同样圈定成员作用域（RAG 召回归属到它） */
            return AgentScope.run(peerId, () -> runner.chatStream(this, sessionId, input, model, null, sink));
        } finally {
            speakSession.remove();
            speakPeer.remove();
            solo.remove();
            speakReplyId.remove();
            speakContext.remove();
            speakBaseRevision.remove();
            speakStale.remove();
        }
    }

    /**
     * 这次发言的出口与账本：模型说出口的话都从它走，并在这里攒成一段一段的正文。
     *
     * <p><b>两个来源，一本流水</b>——「说出去」有两条路：
     * <ol>
     *   <li>{@link #say}：{@code speak} 工具的落地。调用的一瞬间把这段话推给页面
     *       （{@link StreamSink#speech}，桌上的人当场听到并占一个新气泡），并记成一段。
     *       一次发言里可以走很多次，这是正路；</li>
     *   <li>正文信封：模型没走 speak 时的兜底。某一轮正文是 {@code {"speech": "..."}} 就把它解出来
     *       （见 {@link SpeechEnvelope}），按轮记成一段。</li>
     * </ol>
     * 两条路都按发生顺序往 {@link #utterances} 里追加，于是「它到底说了什么、分几段说的」
     * 是一份有序的流水：speak 说的排它说的位置，正文说的排在它那一轮结束的位置。
     *
     * <p>这样就不再需要「末轮取正」那套：以前正文按轮重置、只在末轮为空时沿用上一轮的话，
     * 是因为一次输出只能有一份正文；现在发言可以分好几段，逐段记下来才是它真实说过的话。
     */
    private final class SpeechSink implements StreamSink {

        private final StreamSink down;
        private SpeechEnvelope cur = new SpeechEnvelope();
        /** 这次发言里说出口的每一段，顺序即说出的顺序（落库时一段一行，就是一个个气泡） */
        private final List<String> utterances = new ArrayList<>();
        /** 这次发言报出的「下一轮发言欲望」（每次收尾信封里给一个，后报的为准；没报为 null） */
        private Integer lastDesire;

        private SpeechSink(StreamSink down) {
            this.down = down;
        }

        @Override
        public void meta(String sessionId, long messageId) {
            down.meta(sessionId, messageId);
        }

        @Override
        public void round(int round) {
            flush();
            down.round(round);
        }

        @Override
        public void delta(String reasoning, String content) {
            if (reasoning != null) {
                down.delta(reasoning, null);
            }
            if (content == null) {
                return;
            }
            String text = cur.append(content);
            if (!text.isEmpty()) {
                down.delta(null, text);
            }
        }

        @Override
        public void tool(String name, String args) {
            down.tool(name, args);
        }

        /** speak 工具说了一段：开一段新话（此前的正文到此为止），记进账本 */
        void say(String text) {
            flush();
            utterances.add(text);
            /* 生成期只入账、不发布：真正推给页面由协调器在版本校验通过后按序发布（见 ChatService#publish） */
        }

        /** 这次发言的全部段落（把最后一轮还没收下的正文一并收下） */
        List<String> utterances() {
            flush();
            return List.copyOf(utterances);
        }

        /** 这次发言的完整正文：段落之间空一行（话题事件与回执里用） */
        String speech() {
            return String.join("\n\n", utterances());
        }

        /** 这次发言报出的「下一轮发言欲望」（把最后一轮还没收下的信封读掉再给；没报为 null） */
        Integer desire() {
            flush();
            return lastDesire;
        }

        /** 把当前轮信封里解出的正文记成一段，并顺手收下这一轮报出的发言欲望（换轮、说话之前、取结果之前调用；重复调用安全） */
        private void flush() {
            String text = current();
            if (!text.isBlank()) {
                utterances.add(text);
            }
            Integer desire = cur.desire();
            if (desire != null) {
                lastDesire = desire;
            }
            cur = new SpeechEnvelope();
        }

        /** 这一轮解出来的发言（没按信封输出则整段原文） */
        private String current() {
            return cur.seen() ? cur.speech() : cur.fallback();
        }
    }

    // ---------- 可替换点：会话与消息的落点 ----------

    @Override
    public HubRow resolveOrCreate(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return maSessions.latest().map(this::asHubRow).orElseGet(this::insertNew);
        }
        return maSessions.findById(hubId).map(this::asHubRow)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "多智能体会话不存在: " + hubId));
    }

    @Override
    public HubRow insertNew() {
        /* 走到这里说明是「没带会话 id 直接开说」：没有勾选成员，member_ids 传 null 即全体名册 */
        String id = maSessions.insert(null);
        return maSessions.findById(id).map(this::asHubRow)
                .orElseThrow(() -> new IllegalStateException("圆桌会话落库失败: " + id));
    }

    /** 成员发言不是「人的一次提问」：不落用户消息，也不写记忆投影（多智能体与其他体系隔离）；
     *  私聊时反过来——这就是人正常的一次提问，照旧落一行，好让历史里看得见自己说过什么 */
    @Override
    public long recordUserInput(HubRow row, String input) {
        if (!isSolo()) {
            return -1L;
        }
        return maMessages.insert(row.id(), null, RoundtableMessageStore.ROLE_USER, null, null, input,
                null, null, null, null);
    }

    /**
     * 成员发言落进 chat_message（role=peer + 发言人），人的话由 {@code RoundtableTopicService} 自己落。
     *
     * <p><b>说一段落一行</b>：一次发言里可能说了好几段（边说边想），每段落成一行，页面于是
     * 一段一个气泡——这也正是刷新之后看到的样子（实时与回读同一个形状）。
     *
     * <p>「这一轮到底发生了什么」（思考链、工具往返、当时发出去的请求体）只挂在<b>第一段</b>上：
     * 它是整轮的留痕，摊到每一段只会让同一段思考在每个气泡里各出现一遍，也让请求体在库里存上好几份。
     * 后几段只留它自己那句话。
     *
     * <p><b>一句话都没说就不落行</b>：成员被叫到、判断这一轮无需开口（直接 go_silent 转了沉默）是
     * 正常动作，这时不该留一个空发言——记录里只留那条「某某转入沉默」的状态事件。
     */
    @Override
    public long recordReply(HubRow row, String content, String reasoning, String trace,
                               String rounds) {
        String peerId = speakPeer.get();
        if (peerId == null) {
            return -1L;
        }
        /* 执行态：不落讨论消息，但把这次执行的过程单独留痕（roundtable_work_run）——
           否则「点开一条任务」看不到它在想什么，卡进死循环也无人知晓（见 RoundtableWorkRunStore） */
        if (workTodo.get() != null) {
            Long runId = workRunId.get();
            if (runId != null) {
                workRuns.finish(runId, RoundtableWorkRunStore.STATUS_DONE, content, reasoning, trace,
                        rounds, speakContext.get(), null);
            }
            return -1L;
        }
        /* 版本校验（设计一 §五-1）：生成期间若人又插了一句新话，会话 revision 已变——说明这一轮
           已被新要求取代。此刻正要提交的结果<b>丢弃</b>：不落成正式发言、不进话题上下文，
           由调度器基于最新状态重新调度。旧结果不覆盖新状态。 */
        Integer base = speakBaseRevision.get();
        if (base != null && base.intValue() != revisions.current(row.id())) {
            speakStale.set(Boolean.TRUE);
            return -1L;
        }
        /* 私聊：一次回答就是一行（正文直接落，没有 speak 分词那回事），照旧带上思考与请求体留痕 */
        if (isSolo()) {
            long id = maMessages.insert(row.id(), null, RoundtableMessageStore.ROLE_PEER, peerId,
                    directory.nameOf(peerId), content, reasoning, trace, rounds, speakContext.get());
            speakReplyId.set(id);
            return id;
        }
        /* 段落以账本为准（speak 说出的 + 信封解出的，按序记好）；
           流出口缺失时（异常路径）退回按原文解析 */
        SpeechSink out = speakSink.get();
        List<String> utterances = out == null ? List.of() : out.utterances();
        if (utterances.isEmpty()) {
            String fallback = SpeechEnvelope.parse(content);
            if (fallback.isBlank()) {
                return -1L;
            }
            utterances = List.of(fallback);
        }
        long firstId = -1L;
        for (int i = 0; i < utterances.size(); i++) {
            boolean head = i == 0;
            long id = maMessages.insert(row.id(), speakTopic.get(), RoundtableMessageStore.ROLE_PEER, peerId,
                    directory.nameOf(peerId), utterances.get(i),
                    head ? reasoning : null, head ? trace : null, head ? rounds : null,
                    head ? speakContext.get() : null);
            if (head) {
                firstId = id;
                speakReplyId.set(id);
            }
        }
        return firstId;
    }

    @Override
    public List<ChatMessageStore.MessageRow> history(HubRow row) {
        return maMessages.bySession(row.id()).stream()
                /* 私聊的历史里只有人的话与它的回答；万一这张桌子原本是多人（成员被删后只剩一位），
                   旧的状态动作行也不该混进来当 assistant 回放 */
                .filter(m -> !isSolo() || !RoundtableMessageStore.ROLE_EVENT.equals(m.role()))
                .map(RoundtableChatService::asChatRow).toList();
    }

    @Override
    public void touchSession(HubRow row, String title) {
        maSessions.touch(row.id(), title);
    }

    /** 多智能体会话没有「会话级身份」这回事（身份逐成员定），这里只记兜底模型 */
    @Override
    public void rememberConfig(HubRow row, String modelId, String promptId) {
        maSessions.updateModel(row.id(), modelId);
    }

    // ---------- 可替换点：这次发言看什么、能调什么 ----------

    /**
     * 成员这次发言的请求体：system（人格 + 协作规程，恒定）+ <b>唯一一条 user</b>。
     *
     * <p>那条 user 不是「有人在对它说话」，而是调度器给的当前局面，XML 分成两段：{@code <transcript>}
     * 是本场对话的公开记录（人的话、各成员的发言、状态机的动作，按时间序），{@code <situation>}
     * 是此刻的局面（谁在什么状态、这一轮轮到它做什么）。tools = 只读检索 + 状态机工具。
     *
     * <p><b>请求里没有任何 assistant 历史，这是刻意的</b>：assistant 在协议上等于「你自己上一轮的
     * 输出」。把全体成员的发言都排成 assistant，模型就会把别人的话认成自己说过的——复读上一位的
     * 句式、立场被带着走，甚至顺着别人半句话往下续写。压成一条 user 之后，「谁说过什么」是一份
     * 客观纪要，「我是谁」由 system 里的名字锚定，两件事不再互相污染。
     *
     * <p>顺带消掉一个老毛病：结尾天然是 user，不再出现「以 assistant 结尾 + 带 tools」时供应商
     * 索要 {@code reasoning_content} 的 400，末尾那条「轮到你发言」的补丁因此也不再需要。
     *
     * <p><b>上面说的都是圆桌那一份</b>；一对一私聊（{@link #speakSolo}）另走一条：system 只有它自己
     * 的人格，历史按 user/assistant 原样回放，与首页对话同一个形状。
     */
    @Override
    public ObjectNode requestBody(HubRow row, ModelTarget target, String promptId,
                                  int contextWindow) {
        /* 执行态：这一次不是「发言」，而是某成员在执行一条 TODO——system 换成「人格 + 执行规程」，
           user 是这条 TODO 与任务板快照，工具面去掉发言 / 状态机（见 workBody） */
        if (workTodo.get() != null) {
            return workBody(row, target);
        }
        /* 私聊：system 只挂它自己的人格（没有圆桌协作规程），历史按 user/assistant 原样回放——
           与首页对话那条流同一个形状，只是「助手」换成了这位成员 */
        if (isSolo()) {
            ObjectNode soloBody = om.createObjectNode();
            soloBody.put("model", target.model());
            ArrayNode soloMsgs = soloBody.putArray("messages");
            soloMsgs.addObject().put("role", "system")
                    .put("content", context.persona(speakPeer.get()));
            for (ChatMessageStore.MessageRow m : history(row)) {
                soloMsgs.addObject().put("role", m.role()).put("content", m.content());
            }
            soloBody.set("tools", toolSchemas());
            /* 留一份「这次到底发了什么」：点开这条回答就能回看 */
            speakContext.set(soloBody.toString());
            return soloBody;
        }
        ObjectNode body = om.createObjectNode();
        body.put("model", target.model());
        ArrayNode msgs = body.putArray("messages");
        String system = systemOf();
        ArrayNode toolSchemas = toolSchemas();
        /* 公开记录（那条 user）能占多少 token：窗口 − 输出预留 − 安全余量 − system 与工具面的估算。
           输入与输出共用同一个窗口，且 system / 工具面同样占输入——不把它们扣掉，
           贴着窗口发的请求会被上游拒（口径与首页那条流一致，见 ChatTurnRunner#historyBudget） */
        msgs.addObject().put("role", "system").put("content", system);
        msgs.addObject().put("role", "user")
                .put("content", dispatch(row.id(), transcriptBudget(contextWindow, system, toolSchemas)));
        body.set("tools", toolSchemas);
        /* 留一份「这次到底发了什么」：随发言落进消息行，页面上点开这条发言就能回看 */
        speakContext.set(body.toString());
        return body;
    }

    /**
     * 公开记录最多能占多少 token。
     *
     * <p>{@code contextWindow} 从前是被忽略的：圆桌只按 {@code historyLimit} 条数硬截，
     * 长会话直接超窗口，而「一段发言落一行」会让 128 条很快耗尽——更糟的是截断按<b>行</b>走，
     * 可能把<b>人本轮的问题</b>截掉，成员看不到题目就开口。预算由窗口算出，裁剪改为「从最新往前」。
     */
    private int transcriptBudget(int contextWindow, String system, ArrayNode toolSchemas) {
        long reserved = contextProps.getReserveOutputTokens() + SAFETY_TOKENS
                + TokenUsageService.estimateTokens(system)
                + TokenUsageService.estimateTokens(toolSchemas == null ? "" : toolSchemas.toString());
        return (int) Math.max(0, contextWindow - reserved);
    }

    /**
     * 调度器交给这位成员的那条 user：一份调度文书（{@code <dispatch>}），里面两段——
     * {@code <transcript>} 是本场对话的公开记录，{@code <situation>} 是此刻局面。
     *
     * <p>分节是为了把「谁说了什么」「圆桌上发生过什么」「此刻谁是什么状态」在结构上分开：
     * 三类内容混在一段散文里时，模型只能靠「谁：」这种前缀去猜哪句是发言、哪句是动作。
     *
     * @param budget 公开记录能占的 token 预算（见 {@link #transcriptBudget}）
     */
    private String dispatch(String sessionId, int budget) {
        StringBuilder sb = new StringBuilder(RoundtablePrompts.DISPATCH_HEAD).append('\n');
        sb.append(RoundtablePrompts.TRANSCRIPT_HEAD).append('\n');
        for (String line : transcriptLines(sessionId, budget)) {
            sb.append(line).append('\n');
        }
        sb.append(RoundtablePrompts.TRANSCRIPT_TAIL).append('\n');
        TopicRow topic = topicNow();
        if (topic != null) {
            sb.append(context.situation(topic, speakPeer.get())).append('\n');
            /* 顺手把共享任务区摊在桌面上：不摊，讨论就看不见「哪条受阻、还有什么没做」——
               成员只会回应人的那句话，受阻条目与后续交付就断在这里（踩过这个坑） */
            String board = boardBlock(topic.sessionId());
            if (!board.isEmpty()) {
                sb.append(board).append('\n');
            }
        }
        return sb.append(RoundtablePrompts.DISPATCH_TAIL).toString();
    }

    /**
     * 共享任务区的快照（只列没做完的条目）：贴在局面之后，让讨论看得见全桌的活。
     *
     * <p>受阻条目会带上原因——那正是这轮讨论要处理的事。
     */
    private String boardBlock(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(RoundtablePrompts.BOARD_HEAD).append('\n');
        boolean any = false;
        for (RoundtableTodoStore.TodoRow t : taskBoard.openOnes(sessionId)) {
            if (RoundtableTodoStore.STATUS_DROPPED.equals(t.status())) {
                continue;
            }
            any = true;
            sb.append("  <todo id=\"").append(t.id()).append("\" status=\"").append(t.status()).append('"');
            if (t.ownerPeer() != null) {
                sb.append(" owner=\"").append(nameOr(t.ownerPeer())).append('"');
            }
            sb.append('>').append(t.title());
            if (RoundtableTodoStore.STATUS_BLOCKED.equals(t.status())
                    && t.blockReason() != null && !t.blockReason().isBlank()) {
                sb.append("（受阻：").append(t.blockReason()).append('）');
            }
            sb.append("</todo>\n");
        }
        return any ? sb.append(RoundtablePrompts.BOARD_TAIL).toString() : "";
    }

    /**
     * 本次回放哪些公开记录行：先按条数封顶，再从<b>最新往回</b>按 token 预算收。
     *
     * <p>「从最新往回」是关键：圆桌的记录里既有人的话、也有各成员的发言与状态动作，
     * 越靠后越相关。窗口的选取规则抽在 {@link #windowOf}（纯函数，便于单测）。
     */
    private List<String> transcriptLines(String sessionId, int budget) {
        List<RoundtableMessageStore.MessageRow> all = maMessages.bySession(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }
        List<Long> costs = new ArrayList<>(all.size());
        for (RoundtableMessageStore.MessageRow m : all) {
            costs.add(TokenUsageService.estimateTokens(lineOf(m)));
        }
        List<String> out = new ArrayList<>();
        for (int index : windowOf(costs, maProps.getHistoryLimit(), budget, lastUserIndex(all))) {
            out.add(lineOf(all.get(index)));
        }
        return out;
    }

    /**
     * 公开记录窗口的选取规则（纯函数：只吃「每行的 token 估算」与两个上限，不碰库，故可直接单测）。
     *
     * <p>三条规则，按优先级：
     * <ol>
     *   <li><b>从最新往回</b>收：累加最靠后的若干行，直到再加一行就超预算为止；</li>
     *   <li><b>最后一行永远保留</b>：哪怕它一个人就超预算——空着比只给半句强；</li>
     *   <li><b>「本轮人的话」必须在</b>（{@code askedIndex}）：它是这一桌为什么开口的全部依据。
     *       窗口没够到它（被条数封顶挡在外面，或预算先耗尽）时，把它补在<b>最末</b>——
     *       紧邻后面的局面交代，是模型注意力最近处。少了它，成员会以为「还没有实际话题」而集体沉默。</li>
     * </ol>
     *
     * @param costs      每行的 token 估算（按时间升序）
     * @param limit      条数上限
     * @param budget     token 预算
     * @param askedIndex 最近一条「人的话」的下标；没有则 -1
     * @return 要回放的行的下标（升序；补进来的那条在最末）
     */
    static List<Integer> windowOf(List<Long> costs, int limit, int budget, int askedIndex) {
        int end = costs.size();
        if (end == 0) {
            return List.of();
        }
        int floor = Math.max(0, end - Math.max(1, limit));
        int keep = end;
        long used = 0;
        while (keep > floor) {
            long cost = Math.max(0L, costs.get(keep - 1));
            /* 已经保住了一行，再往前加会超预算就停手（第一行不看预算，见规则 2） */
            if (keep < end && used + cost > budget) {
                break;
            }
            used += cost;
            keep--;
        }
        List<Integer> out = new ArrayList<>();
        for (int i = keep; i < end; i++) {
            out.add(i);
        }
        if (askedIndex >= 0 && askedIndex < keep) {
            out.add(askedIndex);
        }
        return out;
    }

    /** 最近一条「人的话」的下标（没有返回 -1）：它是「本轮人的话必须带上」的锚点 */
    private static int lastUserIndex(List<RoundtableMessageStore.MessageRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (RoundtableMessageStore.ROLE_USER.equals(rows.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 发言工具、状态机工具与终端工具由本类自己接住，其余（只读检索 / 工具箱）照执行器的分发走。
     *
     * <p><b>可用工具由本入口强制</b>：执行器的能力鉴权对「不接能力平面的流」一律放行
     * （{@code CapabilityGuard#check} 的 null grant 分支），所以圆桌必须自己把住这道门——
     * 模型可能凭幻觉调一件本次根本没发出去的工具（如 {@code delete_graph_item}），
     * 而分发是按名字走的，不看本次的工具面。故先与本次实际发出的工具集
     * （{@link #toolSchemas}，见 {@link #allowedToolNames}）比对，不在其中一律拒掉、不落执行。
     * 这落实「讨论阶段只读」：写入能力的唯一来源是人在成员页显式勾选或绑定工作空间。
     */
    @Override
    public String runTool(JsonNode call, String batchRunId, String hubId) {
        String name = call.path("function").path("name").asText("");
        if (!allowedToolNames().contains(name)) {
            return "「" + name + "」不在本次发言可用的工具范围内，未执行。"
                    + "若确需，请说明理由，由用户授权后再执行。";
        }
        if (SpeakTool.NAME.equals(name)) {
            return speakTool(call);
        }
        if (STATE_TOOLS.contains(name)) {
            return stateTool(name, call);
        }
        if (WORK_TOOLS.contains(name)) {
            return workTool(name, call);
        }
        if (ReadMyAnchorTool.NAME.equals(name)) {
            return readMyAnchorTool();
        }
        if (TerminalService.handles(name)) {
            return terminalTool(call);
        }
        return runner.runTool(call, batchRunId, hubId);
    }

    /** 成员的检索来自圆桌这条流：留痕的 source 标为 multiagent（与首页对话区分开） */
    @Override
    public String toolSource() {
        return "multiagent";
    }

    /**
     * 圆桌的每一次成员发言都落一条任务（{@code kind=session}，归属正在发言的那位成员）。
     *
     * <p>为什么并入任务体系：一次发言就是一次真实执行——有身份、有状态、会失败。落了任务，
     * 「它决定发言，执行为什么没成功」才有处可查（{@code task.status / error / trace}），
     * 进程重启打断的残留执行也能被收成「已中断」。这与圆桌的历史包袱（{@code ma_*} 那套表）无关：
     * 会话与消息早已并进 {@code hub_session} / {@code chat_message}，任务只是给这次执行补一个身份。
     */
    @Override
    public boolean taskTracked() {
        return true;
    }

    /**
     * 圆桌的一次发言不记 Turn 正本，也不推「答完了」通知。
     *
     * <p>Turn 是按「提问 → 回答」配对的正本（{@code turnStore.begin} 要一条触发端消息）：
     * 人的话由 {@code RoundtableTopicService} 落、成员的发言是一段一段落的，没有那个配对形状；
     * 而逐位发言的节拍推「答完了」既不对味，也会让圆桌页收到多余的会话通知。
     */
    @Override
    public boolean turnTracked() {
        return false;
    }

    /**
     * 圆桌不受「同会话不并发」的在途锁约束（STEP 4）：它由会话议程串行调度
     * （见 {@code RoundtableAgenda}），同一会话的两次推进本就按提交顺序排队；
     * 再加这把锁只会在「人插话」时把本轮直接顶成 409，挡掉既有节拍。
     */
    @Override
    public boolean singleFlight() {
        return false;
    }

    // ---------- 页面用的会话管理（走 ma_* 一套表） ----------

    public List<AgentDtos.SessionView> sessions() {
        return maSessions.listAll().stream().map(this::toSessionView).toList();
    }

    /** 新建会话：memberIds 是勾选的成员（null / 空 = 全体名册） */
    public AgentDtos.NewSession createSession(List<String> memberIds) {
        return new AgentDtos.NewSession(maSessions.insert(directory.membersJson(memberIds)));
    }

    public AgentDtos.SessionView session(String sessionId) {
        return maSessions.findById(sessionId).map(this::toSessionView)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "多智能体会话不存在: " + sessionId));
    }

    /** 删除会话：连同它的消息、话题与状态机事件一并清掉（不存在的会话返回 404） */
    @Transactional
    public void deleteSession(String sessionId) {
        maSessions.findById(sessionId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "多智能体会话不存在: " + sessionId));
        maMessages.deleteBySession(sessionId);
        /* 顺序不能反：roundtable_topic_event.topic_id 的外键指着 roundtable_topic，先删话题会撞 FK（见 RoundtableTopicStore#deleteBySession） */
        topicEvents.deleteBySession(sessionId);
        topics.deleteBySession(sessionId);
        maSessions.delete(sessionId);
    }

    /** 会话的全部消息（人的话与成员发言按时间序混排，页面按 role 区分渲染） */
    public List<AgentDtos.MessageView> messages(String sessionId, String topicId) {
        List<RoundtableMessageStore.MessageRow> rows = topicId == null || topicId.isBlank()
                ? maMessages.bySession(sessionId) : maMessages.byTopic(topicId);
        List<AgentDtos.MessageView> out = new ArrayList<>();
        for (RoundtableMessageStore.MessageRow m : rows) {
            out.add(new AgentDtos.MessageView(m.id(), m.sessionId(), m.topicId(), m.role(),
                    m.peerId(), m.peerName(), m.content(), m.reasoning(), traceOf(m.trace()),
                    iso(m.createdAt()), m.eventKind()));
        }
        return out;
    }

    /**
     * 某条发言**当时真正发出去的原始上下文**（点开一条成员发言回看它当时看到了什么）。
     *
     * <p>人的话没有这份东西（它不是模型的一次调用），返回空。
     */
    public Optional<String> contextOf(long messageId) {
        return maMessages.message(messageId)
                .map(RoundtableMessageStore.MessageRow::context)
                .filter(text -> text != null && !text.isBlank());
    }

    /** 把某位成员设为沉默/结束之后，页面刷新会话列表要看的最新状态；这里只做视图拼装 */
    private AgentDtos.SessionView toSessionView(RoundtableSessionStore.SessionRow row) {
        TopicRow topic = topics.openBySession(row.id()).orElse(null);
        /* 「这张桌子上坐着谁」在这里解成最终名单（没钉过成员 = 全体名册），页面直接拿去渲染 */
        List<String> memberIds = directory.peers(row.id()).stream().map(AgentRow::id).toList();
        return new AgentDtos.SessionView(row.id(), row.title(), row.modelId(),
                topic == null ? null : topic.id(),
                topic == null ? RoundtableTopicStore.STATUS_CLOSED : topic.status(),
                topic == null ? null : topic.floorPeer(), memberIds,
                row.createdAt() == null ? 0L : row.createdAt(), row.updatedAt());
    }

    // ---------- 私有 ----------

    /** 把圆桌会话行包成执行器认识的行值——流循环因此不必知道有两套会话 */
    private HubRow asHubRow(RoundtableSessionStore.SessionRow row) {
        return new HubRow(row.id(), row.title(), MODE, row.modelId(), null, null,
                true, true, Math.max(1, maProps.getMaxToolRounds()), false, false,
                row.createdAt(), row.updatedAt());
    }

    /** 圆桌消息行 → 执行器的行值（执行器的 history 约定；本类的 requestBody 另有带出处的拼法） */
    private static ChatMessageStore.MessageRow asChatRow(RoundtableMessageStore.MessageRow m) {
        return new ChatMessageStore.MessageRow(m.id(), m.sessionId(),
                RoundtableMessageStore.ROLE_USER.equals(m.role()) ? "user" : "assistant",
                m.content(), m.reasoning(), m.trace(), m.toolRounds(), null, null, m.createdAt());
    }

    /**
     * 公开记录里的一行：人的话与成员的发言是 {@code <message speaker="谁">}，状态机动作是
     * {@code <event>}——两类内容在结构上就分开，模型不必靠前缀去猜这一行是发言还是动作。
     */
    private static String lineOf(RoundtableMessageStore.MessageRow m) {
        if (RoundtableMessageStore.ROLE_EVENT.equals(m.role())) {
            return "<event>" + m.content() + "</event>";
        }
        String who = RoundtableMessageStore.ROLE_USER.equals(m.role()) ? "用户"
                : (m.peerName() == null || m.peerName().isBlank() ? "某位成员" : m.peerName());
        return "<message speaker=\"" + who + "\">" + m.content() + "</message>";
    }

    /** 成员这次发言的 system：人格 + 协作规程（对它恒定，不含局面——局面在那条 user 里） */
    private String systemOf() {
        return context.system(speakPeer.get());
    }

    /** 此刻正在发言的那次话题（{@link #speak} 期间有效） */
    private TopicRow topicNow() {
        String topicId = speakTopic.get();
        return topicId == null ? null : topics.topic(topicId).orElse(null);
    }

    /**
     * 成员这次能用的工具：只读检索（与子代理同一套窄工具集）+ 发言工具 + 状态机工具
     * + 它自己点名的额外工具 + （绑定了工作空间时）终端工具。
     *
     * <p>私聊时去掉发言工具与状态机工具：正文就是回答本身，不需要 speak 那层信封，
     * 也没有话题可以让 propose_end / go_silent 去改。
     */
    private ArrayNode toolSchemas() {
        ArrayNode out = om.createArrayNode();
        /* 逐份并入时必须按名字去重：默认只读工具集与成员「额外工具」的候选清单本就重叠
           （候选来自 grantableTools(CHAT)，其中非写工具正是默认就给的），同名进两次会被供应商判为非法请求 */
        Set<String> seen = new HashSet<>();
        append(out, seen, tools.readOnlyToolsOf(ToolSurface.CHAT));
        /* 发言 / 状态机工具只在「讨论态」给：执行态（workTodo 非空）不发言，也没有话题可改 */
        boolean discussing = !isSolo() && workTodo.get() == null;
        if (discussing) {
            append(out, seen, tools.toolsOf(SPEAK_TOOLS, ToolSurface.PEER));
            append(out, seen, tools.toolsOf(STATE_TOOLS, ToolSurface.PEER));
        }
        /* 执行态的个人 Plan & Execute：成员埋头做自己那条活时，可把它拆成可跟踪的小步。
           刻意只在执行态给——讨论态是「说」不是「做」；用 grantedToolsOf 绕过 agentsMayCall
           （write_todos 默认不给成员，这里对它单独放行） */
        if (!isSolo() && workTodo.get() != null) {
            append(out, seen, tools.grantedToolsOf(List.of(WriteTodosTool.NAME), ToolSurface.CHAT));
        }
        if (!isSolo()) {
            /* 执行态的入口：共享任务区读写 + 开工 / 请讨论（只在圆桌给，私聊没有任务区）。
               讨论态与执行态都给：执行中也可加条目 / 标完成 / 请再议 */
            append(out, seen, tools.toolsOf(WORK_TOOLS, ToolSurface.PEER));
            /* 成员自己「埋头做」的通道：派子代理独立调研，跑完投回本会话并唤醒同伴
               （见 SubagentReportBridge）。grantedToolsOf 不过 agentsMayCall——delegate_subagent
               对 Agent 默认关闭，这里对圆桌单独放行 */
            append(out, seen, tools.grantedToolsOf(SUBAGENT_TOOLS, ToolSurface.CHAT));
        }
        AgentRow peer = speakingPeer();
        List<String> extra = directory.declaredTools(peer);
        if (!extra.isEmpty()) {
            append(out, seen, tools.toolsOf(extra, ToolSurface.CHAT));
        }
        /* 成员自己的人格锚点：属于「当前发言的成员」，圆桌与私聊都该给，也不需人勾选——
           恒定授予（grantedToolsOf 不过 agentsMayCall，它挡的正是这类只给成员的只读工具，见 ReadMyAnchorTool） */
        append(out, seen, tools.grantedToolsOf(List.of(ReadMyAnchorTool.NAME), ToolSurface.CHAT));
        /* 绑定了工作空间才给终端工具：grantedToolsOf 不过 agentsMayCall——它挡的正是这一类 */
        if (!directory.declaredTerminals(peer).isEmpty()) {
            append(out, seen, tools.grantedToolsOf(TERMINAL_TOOLS, ToolSurface.CHAT));
        }
        return out;
    }

    /**
     * 本次发言实际发出的工具名集合：{@link #toolSchemas} 的名单，供 {@link #runTool} 在入口强制。
     *
     * <p>不在发言中（{@code speakPeer} 为空）时返回空集，即拒绝一切——这是安全默认：
     * 没有发言上下文就没有可用的工具，不该落到执行器那条宽松的分发上。
     */
    private Set<String> allowedToolNames() {
        Set<String> names = new HashSet<>();
        if (speakPeer.get() == null) {
            return names;
        }
        for (JsonNode node : toolSchemas()) {
            String name = node.path("function").path("name").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 把一批工具定义并入 {@code out}：同名的只保留先加入的那一份（见 {@link #toolSchemas}） */
    private static void append(ArrayNode out, Set<String> seen, ArrayNode more) {
        for (JsonNode node : more) {
            String name = node.path("function").path("name").asText("");
            if (name.isEmpty() || seen.add(name)) {
                out.add(node);
            }
        }
    }

    /** 此刻正在发言的那位成员（不在发言中时为 null） */
    private AgentRow speakingPeer() {
        String peerId = speakPeer.get();
        return peerId == null ? null : directory.peer(peerId).orElse(null);
    }

    /** 本次发言是不是一对一私聊（见 {@link #solo}）：会话里只剩这一位成员时 */
    private boolean isSolo() {
        return Boolean.TRUE.equals(solo.get());
    }

    /**
     * read_my_anchor 的落地：返回<b>当前发言成员自己</b>的人格锚点（锚点实体 + 一跳关系子图）。
     *
     * <p>「自己」由 {@link #speakPeer} 这个 ThreadLocal 解出（与发言工具同一线程，见类注释）；
     * 未绑定锚点时由 {@link AgentAnchorService#text(String)} 给一句提示语。
     */
    private String readMyAnchorTool() {
        String peerId = speakPeer.get();
        return peerId == null ? "（此刻不在成员发言中，读不到人格锚点）" : peerAnchors.text(peerId);
    }

    /**
     * 终端工具的落地：<b>收在这位成员绑定的工作空间里</b>（授权名单交给 {@link TerminalService}）。
     *
     * <p>没绑工作空间却收到调用（工具面里并没有它，通常是模型幻觉）时直接拒绝——
     * 绝不能落到执行器那条「不限目标」的分发上，否则多智能体这一侧就成了绕过授权的口子。
     */
    private String terminalTool(JsonNode call) {
        List<String> bound = directory.declaredTerminals(speakingPeer());
        if (bound.isEmpty()) {
            return "你没有可用的工作空间（未绑定），无法执行命令。";
        }
        return terminal.run(call, TerminalService.SOURCE_CHAT, new LinkedHashSet<>(bound)).content();
    }

    /**
     * speak 工具的落地：把这句话当场推给页面（桌上的人立刻听到），并计入本次发言的正文。
     *
     * <p>它不改状态、不写库：发言行是整轮跑完时由 {@link #recordReply} 一次性落的
     * （{@code RoundtableTopicService} 靠这一点把话题事件按正确先后补写进记录）。回给模型的那句话
     * 只是告诉它「已经说出去了」，好让它接着想、接着查、接着说。
     *
     * <p>流出口读的是 ThreadLocal——{@link SpeakTool} 不可并行，{@code ToolBatch} 因此
     * 会让含它的一批退回串行，说话时仍在发言这条线程上。
     */
    private String speakTool(JsonNode call) {
        SpeechSink out = speakSink.get();
        String text = readTree(call.path("function").path("arguments").asText(""))
                .path("text").asText("").strip();
        if (text.isEmpty()) {
            return "text 是空的，这句话没说出去。";
        }
        if (out == null) {
            return "此刻不在圆桌发言中，说不出口。";
        }
        out.say(text);
        return "已经说出口了，桌上的人都听到了。可以接着说，也可以先查资料、想清楚再开口。";
    }

    /** 状态机工具的落地：就地改状态并回一句「已经怎样了」，好让模型接着往下说 */
    private String stateTool(String name, JsonNode call) {
        String topicId = speakTopic.get();
        String peerId = speakPeer.get();
        if (topicId == null || peerId == null) {
            return "此刻没有进行中的话题，这个动作没有落点。";
        }
        String reason = readTree(call.path("function").path("arguments").asText(""))
                .path("reason").asText("").trim();
        return switch (name) {
            case ProposeEndTool.NAME -> {
                machine.proposeEnd(topicId, peerId, reason);
                yield "已记下你的结束提议。若有人重新发言，你会被重新叫到，可以改主意。";
            }
            case GoSilentTool.NAME -> {
                machine.silent(topicId, peerId, reason);
                yield "已转入沉默：这一轮不再安排你发言。想再开口时直接说即可。";
            }
            case InterjectTool.NAME -> {
                machine.interject(topicId, peerId, reason);
                yield "已记下你的发言请求（抢答）：接下来会优先把话头交给你。";
            }
            case AskUserTool.NAME -> {
                machine.askUser(topicId, peerId, reason);
                yield "已请用户表态：全桌就此暂停，等用户发言后再继续（这一轮不必再补充说明）。";
            }
            default -> "未知工具: " + name;
        };
    }

    // ---------- 执行态：共享任务区与两个开关 ----------

    /**
     * 执行态工具的落地：共享任务区的读写，以及「开工 / 请讨论」两个开关。
     *
     * <p>它<b>不直接调用调度器</b>：改任务板是写库；开工 / 请讨论是发运行时事件——
     * 由运行时在全局层面接住（见 {@code RoundtableWorkRuntime} / {@code RoundtableDiscussionRuntime}）。
     * 于是讨论这条流不必反过来依赖调度器，依赖方向保持单向。
     */
    private String workTool(String name, JsonNode call) {
        String sessionId = speakSession.get();
        String peerId = speakPeer.get();
        if (sessionId == null || peerId == null) {
            return "此刻不在圆桌发言中，共享任务区不可用。";
        }
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case AddTodoTool.NAME -> addTodo(sessionId, peerId, args);
            case UpdateTodoTool.NAME -> updateTodo(sessionId, peerId, args);
            case CompleteTodoTool.NAME -> completeTodo(sessionId, peerId, args);
            case StartWorkTool.NAME -> startWork(sessionId, peerId, args);
            case RequestDiscussionTool.NAME -> requestDiscussion(sessionId, peerId, args);
            default -> "未知工具: " + name;
        };
    }

    /** add_todo：往共享任务板加一条 */
    private String addTodo(String sessionId, String peerId, JsonNode args) {
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) {
            return "title 不能为空：写清这一步要做什么。";
        }
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员：owner 要么写本桌成员名，要么留空（公共）。";
        }
        /* 前置依赖：声明了就先等它们完成，未完成前没人会开工这条（下游不必在没有上游产出时白跑） */
        List<RoundtableTodoStore.TodoRow> deps = resolveDeps(sessionId, args.path("depends_on"));
        String id = taskBoard.add(sessionId, speakTopic.get(), owner, title,
                blankToNull(args.path("detail").asText("")), peerId, depsJson(deps));
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_CREATED, sessionId, peerId,
                Map.of("todoId", id, "title", title, "owner", owner == null ? "" : owner)));
        return "已加到共享任务区（id=" + id + "）：" + title
                + (owner == null ? "（公共：谁有空谁做）" : "（负责人：" + directory.nameOf(owner) + "）")
                + (deps.isEmpty() ? ""
                        : "（等 " + String.join("、",
                                deps.stream().map(RoundtableTodoStore.TodoRow::title).toList())
                                + " 完成后开工）")
                + "。桌上所有人都看得见；开工后会有成员按顺序去做。";
    }

    /**
     * 解析 add_todo 的 depends_on：既接受条目 id，也接受标题（模型更记得住标题）。
     * 命中不到的忽略；返回命中的前置条目（保持给定顺序、去重）。
     */
    private List<RoundtableTodoStore.TodoRow> resolveDeps(String sessionId, JsonNode arg) {
        if (arg == null || !arg.isArray()) {
            return List.of();
        }
        List<RoundtableTodoStore.TodoRow> board = taskBoard.list(sessionId);
        List<RoundtableTodoStore.TodoRow> out = new ArrayList<>();
        for (JsonNode n : arg) {
            String raw = n.asText("").strip();
            if (raw.isEmpty()) {
                continue;
            }
            RoundtableTodoStore.TodoRow hit = board.stream()
                    .filter(t -> raw.equals(t.id()) || raw.equals(t.title()))
                    .findFirst().orElse(null);
            if (hit != null && out.stream().noneMatch(t -> t.id().equals(hit.id()))) {
                out.add(hit);
            }
        }
        return out;
    }

    /** 前置清单 → JSON 数组文本；空清单落 null（读侧按「无依赖」处理） */
    private String depsJson(List<RoundtableTodoStore.TodoRow> deps) {
        if (deps.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(deps.stream().map(RoundtableTodoStore.TodoRow::id).toList());
        } catch (Exception e) {
            return null;
        }
    }

    /** update_todo：改状态 / 负责人 / 说明；标记受阻时可同时做「找谁」的决策（with） */
    private String updateTodo(String sessionId, String peerId, JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) {
            return "id 不能为空：指明要改任务区里的哪一条。";
        }
        RoundtableTodoStore.TodoRow existing = taskBoard.todo(id).orElse(null);
        if (existing == null) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String status = blankToNull(args.path("status").asText(""));
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员。";
        }
        String reason = blankToNull(args.path("reason").asText(""));
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status) && reason == null) {
            return "标记 blocked 时请在 reason 里写清受阻原因（那是请大家再议的凭据）。";
        }
        /* 受阻时先做一次决策：找哪位（可多位，用「、」分隔）、还是找用户拍板、还是全桌一起议。
           名字解析不出来就别落下半截状态——先把它当一次校验失败挡回去。 */
        List<String> targets = List.of();
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status)) {
            targets = parseWith(sessionId, args.path("with").asText(""));
            if (targets == null) {
                return "with 里有认不出的名字：写成员名（可多个，用「、」分隔）、写「用户」请用户拍板，"
                        + "或留空 / 填「全员」（全桌一起议）。";
            }
        }
        taskBoard.update(id, status, null, reason, owner, blankToNull(args.path("detail").asText("")));
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status)) {
            /* 记下这次「找谁」的决策（执行运行时据它决定定向 / 等用户 / 全桌），再发事件、带上条目标题 */
            String csv = String.join(",", targets);
            taskBoard.setBlockWith(id, csv.isEmpty() ? null : csv);
            runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, sessionId, peerId, null,
                    Map.of("todoId", id, "title", existing.title(),
                            "reason", reason == null ? "" : reason,
                            "with", csv)));
        }
        return "已更新任务区（id=" + id + "）" + (status == null ? "。" : "：状态 → " + status + "。");
    }

    /** complete_todo：标记完成并记结论 */
    private String completeTodo(String sessionId, String peerId, JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) {
            return "id 不能为空。";
        }
        if (taskBoard.todo(id).isEmpty()) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String result = blankToNull(args.path("result").asText(""));
        taskBoard.update(id, RoundtableTodoStore.STATUS_DONE, result, null, null, null);
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_COMPLETED, sessionId, peerId,
                Map.of("todoId", id, "result", result == null ? "" : result)));
        return "已完成（id=" + id + "）" + (result == null ? "。" : "：结论已记进任务板。");
    }

    /** start_work：讨论定案、开工（发 WORK_STARTED，由执行运行时接住） */
    private String startWork(String sessionId, String peerId, JsonNode args) {
        if (taskBoard.openOnes(sessionId).isEmpty()) {
            return "任务区里还没有待办：先把要做的事用 " + AddTodoTool.NAME + " 落成条目，再说开工。";
        }
        String note = args.path("note").asText("").strip();
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, sessionId, peerId,
                Map.of("note", note, "by", peerId)));
        return "已宣布开工：各成员的执行线程开始按顺序完成自己名下的条目。讨论不停，你仍可继续发言。";
    }

    /** request_discussion：执行遇阻、请大家再议（发 DISCUSSION_REQUESTED，由讨论运行时接住） */
    private String requestDiscussion(String sessionId, String peerId, JsonNode args) {
        String reason = args.path("reason").asText("").strip();
        if (reason.isEmpty()) {
            return "reason 不能为空：写清卡在哪、已经试过什么、需要什么决定。";
        }
        /* 同样带着「找谁」的决策：写成员名 = 只和它们定向沟通一轮；写「用户」= 全桌暂停等用户；
           留空 / 「全员」= 全桌一起议 */
        List<String> targets = parseWith(sessionId, args.path("with").asText(""));
        if (targets == null) {
            return "with 里有认不出的名字：写成员名（可多个，用「、」分隔）、写「用户」请用户拍板，"
                    + "或留空 / 填「全员」（全桌一起议）。";
        }
        /* 执行态：把这次决策记到当前待办上（受阻走的是 update_todo(blocked) → 执行运行时兜底上报，
           运行时读的就是这条 block_with）。只写不覆盖空值——update_todo 已经写过的以它为准 */
        RoundtableTodoStore.TodoRow cur = workTodo.get();
        if (cur != null && !targets.isEmpty()) {
            taskBoard.setBlockWith(cur.id(), String.join(",", targets));
        }
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.DISCUSSION_REQUESTED, sessionId, peerId,
                Map.of("reason", reason, "by", peerId, "with", String.join(",", targets))));
        if (targets.isEmpty()) {
            return "已把问题抛回桌面：全桌会被激活，一起决定怎么办。";
        }
        if (targets.contains(RoundtableTodoStore.BLOCK_WITH_USER)) {
            return "已把问题交给用户拍板：全桌暂停，等用户发言后继续。";
        }
        return "已把问题定向抛给「" + namesOf(targets) + "」：只你们沟通一轮，其余成员不受打扰。";
    }

    /**
     * 解析 {@code with}（受阻成员自己做的「找谁」决策）：
     * 返回目标列表——成员 id，或 {@link RoundtableTodoStore#BLOCK_WITH_USER}（用户）。
     * <b>空列表 = 全桌</b>（留空 / 「全员」）；<b>null = 无效</b>（有认不出的名字，由调用方报错）。
     *
     * <p>多个目标用「、」「,」「，」等分隔；{@code @名字} 里的 @ 会被忽略，方便直接抄界面上的写法。
     */
    private List<String> parseWith(String sessionId, String raw) {
        List<String> targets = new ArrayList<>();
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty() || isEveryone(s)) {
            return targets;   // 全桌
        }
        for (String token : s.split("[、,，;；/\\s]+")) {
            String name = token.replace("@", "").strip();
            if (name.isEmpty() || isEveryone(name)) {
                continue;
            }
            if (isUser(name)) {
                if (!targets.contains(RoundtableTodoStore.BLOCK_WITH_USER)) {
                    targets.add(RoundtableTodoStore.BLOCK_WITH_USER);
                }
                continue;
            }
            String id = resolveOwner(sessionId, name);
            if (id == null) {
                return null;   // 有认不出的名字：整体判为无效，别落下半截决策
            }
            if (!targets.contains(id)) {
                targets.add(id);
            }
        }
        return targets;
    }

    /** {@code with} 里表示「找用户拍板」的说法（成员 id 是 uuid，不会与它撞上） */
    private static boolean isUser(String with) {
        return Set.of("用户", "user", "人类").contains(with.strip());
    }

    /** 把目标列表拼成展示名（用户 → 「用户」） */
    private String namesOf(List<String> targets) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(RoundtableTodoStore.BLOCK_WITH_USER.equals(t) ? "用户" : directory.nameOf(t));
        }
        return sb.toString();
    }

    /** {@code with} 里表示「全桌一起议」的说法（留空也算）：不是这些、又解析不出成员名时按无效处理 */
    private static boolean isEveryone(String with) {
        if (with == null || with.isBlank()) {
            return true;
        }
        return Set.of("全员", "全桌", "所有人", "全体", "全部", "大家", "all").contains(with.strip());
    }

    /** 按名字在会话成员里找 id（找不到返回 null） */
    private String resolveOwner(String sessionId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        return directory.peers(sessionId).stream()
                .filter(p -> target.equals(p.name()))
                .map(AgentRow::id)
                .findFirst()
                .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private List<ChatDtos.TraceSegment> traceOf(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<ChatDtos.TraceSegment>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private static String iso(Long ms) {
        return ms == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                        .toString();
    }
}
