package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.chat.ChatService;
import cc.ginpika.minis.agent.chat.ChatLiveStore;
import cc.ginpika.minis.agent.chat.SubagentTool;
import cc.ginpika.minis.agent.chat.SubconsciousTool;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.multiagent.dto.MultiAgentDtos;
import cc.ginpika.minis.agent.multiagent.store.MaMessageStore;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import cc.ginpika.minis.agent.multiagent.store.MaSessionStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicEventStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicStore.TopicRow;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.graph.ReadMyAnchorTool;
import cc.ginpika.minis.ai.core.tool.multiagent.AskUserTool;
import cc.ginpika.minis.ai.core.tool.multiagent.GoSilentTool;
import cc.ginpika.minis.ai.core.tool.multiagent.InterjectTool;
import cc.ginpika.minis.ai.core.tool.multiagent.ProposeEndTool;
import cc.ginpika.minis.ai.core.tool.multiagent.SpeakTool;
import cc.ginpika.minis.ai.core.tool.terminal.ListTerminalsTool;
import cc.ginpika.minis.ai.core.tool.terminal.RunCommandTool;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.config.MultiAgentProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 多智能体的发言流：<b>继承 chat 的那条流</b>，只换掉「跟谁说话、说的话落到哪」。
 *
 * <p>为什么要继承而不是复制：一次发言里真正值钱的不是那几十行循环，而是循环背后的一整套东西——
 * 流式增量与停止标志、工具往返与原生消息回放、思维链分段（trace）、取消时把半截回答落库、
 * 端到端的错误处理。这几件事在 {@link ChatService#runChat} 里已经写对了，抄一份出来只会在
 * 两个地方各自演进。所以这里只覆盖父类留出的几个「可覆盖点」（见父类里那段注释）：
 * <ul>
 *   <li>{@link #resolveOrCreate} / {@link #insertNew} / {@link #touchSession} / {@link #rememberConfig}
 *       ——会话落到 {@code ma_session}；</li>
 *   <li>{@link #recordUserInput} / {@link #recordReply} / {@link #history}
 *       ——消息落到 {@code ma_message}（成员发言不写记忆投影：多智能体与其他体系隔离）；</li>
 *   <li>{@link #requestBody}——system 换成「成员人格 + 协作规程」（对同一成员恒定），
 *       全部历史压成唯一一条 user「公开记录 + 此刻局面」（请求里没有 assistant 历史）；
 *       工具面换成只读检索 + 发言工具 + 状态机工具（{@link ToolSurface#PEER}），
 *       再加上这位成员自己声明的额外工具与它绑定了工作空间时的终端工具；</li>
 *   <li>{@link #runTool}——多接一条发言工具 / 状态机工具 / 终端工具的分发
 *       （终端只在它绑定的工作空间上执行，见 {@link #terminalTool}）。</li>
 * </ul>
 * 流循环、流式推送、停止、留痕格式全部原样沿用，父类行为一字未改。
 *
 * <p><b>那次发言的落点靠 ThreadLocal 传递</b>：{@link #speak} 是圆桌发言的唯一入口
 * （一对一私聊另有一条 {@link #speakSolo}），它在调用父类的 {@code chatStream} 之前把「哪次话题、哪位成员、往哪条流上说」放进几个 ThreadLocal，
 * 覆盖点与工具分发就地读取。之所以能用：圆桌上同一时刻只有一个人在发言，
 * 而发言工具与状态机工具都不可并行（{@code ToolBatch} 因此不会把它们挪到别的线程）。
 *
 * <p><b>会话里只剩一位成员时走私聊</b>（{@link #speakSolo}）：那不是圆桌，是用户与这位成员一对一。
 * 它复用父类那条普通对话流，但换成它自己的人格提示词、去掉发言与状态机工具、不落话题；
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
 * 由本类自己提供，走 {@code ma_*} 一套表；父类继承来的 {@code list/create/messages/rewind}
 * 仍是 hub_session 那套，多智能体页不使用它们。
 */
@Service
public class MaChatService extends ChatService {

    /** 会话模式标记（HubRow.mode）：让两边的人都一眼看出这是圆桌会话，不是首页那个 */
    public static final String MODE = "multi-agent";

    /**
     * 只挂在成员发言这一侧的状态机工具：主对话的 CHAT 面永远拿不到它们。
     *
     * <p>前三件是成员内部的去向（提议结束 / 转沉默 / 抢答）；{@link AskUserTool} 是唯一一个
     * 把手伸到圆桌外面的——它请用户表态，并让整桌停下等人（见 MaTopicService#advance）。
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
     * 终端工具：只有在这位成员<b>绑定了工作空间</b>时才授予它，并且只能在那几台上执行。
     *
     * <p>它们平时不给成员（{@code agentsMayCall()=false}，见各工具类）：任意命令执行权是
     * 「当场决定的那一层」的本领。人给某位成员绑了工作空间，才算对它单独放行——
     * 于是取定义要绕过 {@code agentsMayCall}（见 {@link ToolManager#grantedToolsOf}），
     * 执行时再把授权名单交给 {@link TerminalService}。
     */
    private static final List<String> TERMINAL_TOOLS =
            List.of(RunCommandTool.NAME, ListTerminalsTool.NAME);

    private final MaSessionStore maSessions;
    private final MaMessageStore maMessages;
    private final MaTopicStore topics;
    /** 话题事件正本：删会话时要抢在话题行之前清掉（它的 topic_id 有外键指着 ma_topic） */
    private final MaTopicEventStore topicEvents;
    private final PeerDirectory directory;
    private final PeerContextBuilder context;
    private final PeerAnchorService peerAnchors;
    private final TopicStateMachine machine;
    private final MultiAgentProps maProps;

    /** 本次发言的落点：哪次话题、哪位成员（{@link #speak} 期间有效） */
    private final ThreadLocal<String> speakTopic = new ThreadLocal<>();
    private final ThreadLocal<String> speakPeer = new ThreadLocal<>();
    /** 本次发言落库后的消息 id（{@link #recordReply} 写入，{@link #speak} 读走） */
    private final ThreadLocal<Long> speakReplyId = new ThreadLocal<>();
    /** 本次发言组装出的请求体（{@link #requestBody} 写入，{@link #recordReply} 读走存进消息） */
    private final ThreadLocal<String> speakContext = new ThreadLocal<>();
    /** 本次发言的流出口（{@link #speak} 写入，{@link #speakTool} 与 {@link #recordReply} 读走） */
    private final ThreadLocal<SpeechSink> speakSink = new ThreadLocal<>();

    /**
     * 本次发言是不是「一对一私聊」：会话里只剩这一位成员时（见 {@link #speakSolo}）为真。
     *
     * <p>它把这条流切回父类那套普通对话——system 只取成员自己的人格（{@link PeerContextBuilder#persona}）、
     * 历史按 user/assistant 原样回放、正文直接流出去，不落话题、也不给发言 / 状态机工具；
     * 落点仍在 {@code ma_*}（见 {@link #recordUserInput} / {@link #recordReply}），圆桌页照常读到。
     */
    private final ThreadLocal<Boolean> solo = new ThreadLocal<>();

    public MaChatService(SettingsService settings, OpenAiChatClient client,
                         HubSessionStore sessions, ChatMessageStore messages, ObjectMapper om,
                         PromptManager prompts, ToolManager tools, KnowledgeToolbox toolbox,
                         SubconsciousTool subconscious, SubagentTool subagent,
                         TerminalService terminal, MemoryWriter memory, ChatLiveStore live,
                         MaSessionStore maSessions, MaMessageStore maMessages, MaTopicStore topics,
                         MaTopicEventStore topicEvents, PeerDirectory directory,
                         PeerContextBuilder context, PeerAnchorService peerAnchors,
                         TopicStateMachine machine, MultiAgentProps maProps) {
        super(settings, client, sessions, messages, om, prompts, tools, toolbox, subconscious,
                subagent, terminal, memory, live);
        this.maSessions = maSessions;
        this.maMessages = maMessages;
        this.topics = topics;
        this.topicEvents = topicEvents;
        this.directory = directory;
        this.context = context;
        this.peerAnchors = peerAnchors;
        this.machine = machine;
        this.maProps = maProps;
    }

    // ---------- 一次发言 ----------

    /**
     * 一次发言的结果：结论 + 落库后的消息 id（话题事件要按它指回正本）+ 这一次报出的下一轮发言欲望。
     *
     * <p>{@code desire} 是模型在收尾信封里顺带给的（0~10，见 {@link SpeechEnvelope}）；没给、或没按
     * 约定输出时为 null。它是选人的依据（见 {@link DesireSpeakerStrategy}），由 {@code MaTopicService}
     * 记进话题事件；本类不判越界，夹紧在落库那一步做。
     */
    public record Speech(ChatDtos.ChatReply reply, long messageId, Integer desire) {
    }

    /**
     * 让某位成员在某个话题里发一次言：把落点放进 ThreadLocal，交给父类的流循环去跑。
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
        PeerRow peer = directory.peer(peerId)
                .orElseThrow(() -> new IllegalStateException("成员不存在: " + peerId));
        String model = peer.modelId() != null ? peer.modelId()
                : maSessions.findById(topic.sessionId()).map(MaSessionStore.SessionRow::modelId)
                        .orElse(null);
        speakTopic.set(topicId);
        speakPeer.set(peerId);
        speakReplyId.remove();
        speakContext.remove();
        try {
            SpeechSink envelope = new SpeechSink(sink);
            /* 流出口交给 speak 工具：它在工具往返里就地说话（同一线程，见类注释） */
            speakSink.set(envelope);
            ChatDtos.ChatReply reply = chatStream(topic.sessionId(),
                    "〔" + peer.name() + " 发言〕", model, null, envelope);
            Long id = speakReplyId.get();
            String speech = envelope.speech();
            /* 收尾信封里顺带报出的发言欲望（先取 speech 触发收尾，再读 desire）；没报为 null */
            Integer desire = envelope.desire();
            /* 回执里的正文换成攒出来的发言：信封是给后端看的，下游（话题事件、页面）只该看到人话 */
            return new Speech(new ChatDtos.ChatReply(reply.sessionId(), reply.messageId(),
                    speech, reply.rawResponse(), reply.success(), reply.error()),
                    id == null ? -1L : id, desire);
        } finally {
            speakTopic.remove();
            speakPeer.remove();
            speakReplyId.remove();
            speakContext.remove();
            speakSink.remove();
        }
    }

    /**
     * 一对一私聊：会话里只剩这一位成员时，人说的每句话都是对它一个人说的。
     *
     * <p>这不是圆桌，所以不落话题、不跑状态机，直接走父类那条普通对话流程：system 是它自己那套
     * 「对话」人格（{@link PeerContextBuilder#persona}，<b>不含协作规程</b>），历史按 user/assistant
     * 原样回放，正文由增量直接流出。工具面只留检索与它自己声明的那些（见 {@link #toolSchemas}）。
     *
     * <p>消息仍进 {@code ma_*}：人的话与它的回答各一行（{@link #recordUserInput} /
     * {@link #recordReply}），圆桌页照常读到——只是没有话题与状态行。
     */
    public ChatDtos.ChatReply speakSolo(String sessionId, String peerId, String input, StreamSink sink) {
        PeerRow peer = directory.peer(peerId)
                .orElseThrow(() -> new IllegalStateException("成员不存在: " + peerId));
        String model = peer.modelId() != null ? peer.modelId()
                : maSessions.findById(sessionId).map(MaSessionStore.SessionRow::modelId)
                        .orElse(null);
        speakPeer.set(peerId);
        solo.set(Boolean.TRUE);
        speakReplyId.remove();
        speakContext.remove();
        try {
            return chatStream(sessionId, input, model, null, sink);
        } finally {
            speakPeer.remove();
            solo.remove();
            speakReplyId.remove();
            speakContext.remove();
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

        /** speak 工具说了一段：开一段新话（此前的正文到此为止），整段推给页面 */
        void say(String text) {
            flush();
            utterances.add(text);
            /* 整段一次到达，不必再走 content 增量：边界信号本身就带着这段话 */
            down.speech(text);
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

    // ---------- 可覆盖点：会话与消息的落点 ----------

    @Override
    protected HubRow resolveOrCreate(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return maSessions.latest().map(this::asHubRow).orElseGet(this::insertNew);
        }
        return maSessions.findById(hubId).map(this::asHubRow)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "多智能体会话不存在: " + hubId));
    }

    @Override
    protected HubRow insertNew() {
        /* 走到这里说明是「没带会话 id 直接开说」：没有勾选成员，member_ids 传 null 即全体名册 */
        String id = maSessions.insert(null);
        return maSessions.findById(id).map(this::asHubRow)
                .orElseThrow(() -> new IllegalStateException("ma_session 落库失败: " + id));
    }

    /** 成员发言不是「人的一次提问」：不落用户消息，也不写记忆投影（多智能体与其他体系隔离）；
     *  私聊时反过来——这就是人正常的一次提问，照旧落一行，好让历史里看得见自己说过什么 */
    @Override
    protected long recordUserInput(HubRow row, String input) {
        if (!isSolo()) {
            return -1L;
        }
        return maMessages.insert(row.id(), null, MaMessageStore.ROLE_USER, null, null, input,
                null, null, null, null);
    }

    /**
     * 成员发言落进 ma_message（role=peer + 发言人），人的话由 {@code MaTopicService} 自己落。
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
    protected long recordReply(HubRow row, String content, String reasoning, String trace,
                               String rounds) {
        String peerId = speakPeer.get();
        if (peerId == null) {
            return -1L;
        }
        /* 私聊：一次回答就是一行（正文直接落，没有 speak 分词那回事），照旧带上思考与请求体留痕 */
        if (isSolo()) {
            long id = maMessages.insert(row.id(), null, MaMessageStore.ROLE_PEER, peerId,
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
            long id = maMessages.insert(row.id(), speakTopic.get(), MaMessageStore.ROLE_PEER, peerId,
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
    protected List<ChatMessageStore.MessageRow> history(HubRow row) {
        return maMessages.bySession(row.id()).stream()
                /* 私聊的历史里只有人的话与它的回答；万一这张桌子原本是多人（成员被删后只剩一位），
                   旧的状态动作行也不该混进来当 assistant 回放 */
                .filter(m -> !isSolo() || !MaMessageStore.ROLE_EVENT.equals(m.role()))
                .map(MaChatService::asChatRow).toList();
    }

    @Override
    protected void touchSession(HubRow row, String title) {
        maSessions.touch(row.id(), title);
    }

    /** 多智能体会话没有「会话级身份」这回事（身份逐成员定），这里只记兜底模型 */
    @Override
    protected void rememberConfig(HubRow row, String modelId, String promptId) {
        maSessions.updateModel(row.id(), modelId);
    }

    // ---------- 可覆盖点：这次发言看什么、能调什么 ----------

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
    protected ObjectNode requestBody(HubRow row, ModelTarget target, String promptId) {
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
        msgs.addObject().put("role", "system").put("content", systemOf());
        msgs.addObject().put("role", "user").put("content", dispatch(row.id()));
        body.set("tools", toolSchemas());
        /* 留一份「这次到底发了什么」：随发言落进消息行，页面上点开这条发言就能回看 */
        speakContext.set(body.toString());
        return body;
    }

    /**
     * 调度器交给这位成员的那条 user：一份调度文书（{@code <dispatch>}），里面两段——
     * {@code <transcript>} 是本场对话的公开记录，{@code <situation>} 是此刻局面。
     *
     * <p>分节是为了把「谁说了什么」「圆桌上发生过什么」「此刻谁是什么状态」在结构上分开：
     * 三类内容混在一段散文里时，模型只能靠「谁：」这种前缀去猜哪句是发言、哪句是动作。
     */
    private String dispatch(String sessionId) {
        StringBuilder sb = new StringBuilder(MultiAgentPrompts.DISPATCH_HEAD).append('\n');
        sb.append(MultiAgentPrompts.TRANSCRIPT_HEAD).append('\n');
        for (MaMessageStore.MessageRow m : recent(sessionId)) {
            sb.append(lineOf(m)).append('\n');
        }
        sb.append(MultiAgentPrompts.TRANSCRIPT_TAIL).append('\n');
        TopicRow topic = topicNow();
        if (topic != null) {
            sb.append(context.situation(topic, speakPeer.get())).append('\n');
        }
        return sb.append(MultiAgentPrompts.DISPATCH_TAIL).toString();
    }

    /** 发言工具、状态机工具与终端工具由本类自己接住，其余（只读检索 / 工具箱）照父类的分发走 */
    @Override
    protected String runTool(JsonNode call, String batchRunId, String hubId) {
        String name = call.path("function").path("name").asText("");
        if (SpeakTool.NAME.equals(name)) {
            return speakTool(call);
        }
        if (STATE_TOOLS.contains(name)) {
            return stateTool(name, call);
        }
        if (ReadMyAnchorTool.NAME.equals(name)) {
            return readMyAnchorTool();
        }
        if (TerminalService.handles(name)) {
            return terminalTool(call);
        }
        return super.runTool(call, batchRunId, hubId);
    }

    /** 成员的检索来自圆桌这条流：留痕的 source 标为 multiagent（与首页对话区分开） */
    @Override
    protected String toolSource() {
        return "multiagent";
    }

    // ---------- 页面用的会话管理（走 ma_* 一套表） ----------

    public List<MultiAgentDtos.SessionView> sessions() {
        return maSessions.listAll().stream().map(this::toSessionView).toList();
    }

    /** 新建会话：memberIds 是勾选的成员（null / 空 = 全体名册） */
    public MultiAgentDtos.NewSession createSession(List<String> memberIds) {
        return new MultiAgentDtos.NewSession(maSessions.insert(directory.membersJson(memberIds)));
    }

    public MultiAgentDtos.SessionView session(String sessionId) {
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
        /* 顺序不能反：ma_topic_event.topic_id 的外键指着 ma_topic，先删话题会撞 FK（见 MaTopicStore#deleteBySession） */
        topicEvents.deleteBySession(sessionId);
        topics.deleteBySession(sessionId);
        maSessions.delete(sessionId);
    }

    /** 会话的全部消息（人的话与成员发言按时间序混排，页面按 role 区分渲染） */
    public List<MultiAgentDtos.MessageView> messages(String sessionId, String topicId) {
        List<MaMessageStore.MessageRow> rows = topicId == null || topicId.isBlank()
                ? maMessages.bySession(sessionId) : maMessages.byTopic(topicId);
        List<MultiAgentDtos.MessageView> out = new ArrayList<>();
        for (MaMessageStore.MessageRow m : rows) {
            out.add(new MultiAgentDtos.MessageView(m.id(), m.sessionId(), m.topicId(), m.role(),
                    m.peerId(), m.peerName(), m.content(), m.reasoning(), traceOf(m.trace()),
                    iso(m.createdAt())));
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
                .map(MaMessageStore.MessageRow::context)
                .filter(text -> text != null && !text.isBlank());
    }

    /** 把某位成员设为沉默/结束之后，页面刷新会话列表要看的最新状态；这里只做视图拼装 */
    private MultiAgentDtos.SessionView toSessionView(MaSessionStore.SessionRow row) {
        TopicRow topic = topics.openBySession(row.id()).orElse(null);
        /* 「这张桌子上坐着谁」在这里解成最终名单（没钉过成员 = 全体名册），页面直接拿去渲染 */
        List<String> memberIds = directory.peers(row.id()).stream().map(PeerRow::id).toList();
        return new MultiAgentDtos.SessionView(row.id(), row.title(), row.modelId(),
                topic == null ? null : topic.id(),
                topic == null ? MaTopicStore.STATUS_CLOSED : topic.status(),
                topic == null ? null : topic.floorPeer(), memberIds,
                row.createdAt() == null ? 0L : row.createdAt(), row.updatedAt());
    }

    // ---------- 私有 ----------

    /** 把 ma_session 行包成父类认识的行值——流循环因此不必知道有两套会话 */
    private HubRow asHubRow(MaSessionStore.SessionRow row) {
        return new HubRow(row.id(), row.title(), MODE, row.modelId(), null,
                true, true, Math.max(1, maProps.getMaxToolRounds()), false, false,
                row.createdAt(), row.updatedAt());
    }

    /** ma_message 行 → chat 的行值（父类的 history 约定；本类的 requestBody 另有带出处的拼法） */
    private static ChatMessageStore.MessageRow asChatRow(MaMessageStore.MessageRow m) {
        return new ChatMessageStore.MessageRow(m.id(), m.sessionId(),
                MaMessageStore.ROLE_USER.equals(m.role()) ? "user" : "assistant",
                m.content(), m.reasoning(), m.trace(), m.toolRounds(), null, null, m.createdAt());
    }

    /** 公开记录的来源：本会话全部消息，太长时只带最近若干条（见 MultiAgentProps#getHistoryLimit） */
    private List<MaMessageStore.MessageRow> recent(String sessionId) {
        List<MaMessageStore.MessageRow> all = maMessages.bySession(sessionId);
        int limit = Math.max(1, maProps.getHistoryLimit());
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    /**
     * 公开记录里的一行：人的话与成员的发言是 {@code <message speaker="谁">}，状态机动作是
     * {@code <event>}——两类内容在结构上就分开，模型不必靠前缀去猜这一行是发言还是动作。
     */
    private static String lineOf(MaMessageStore.MessageRow m) {
        if (MaMessageStore.ROLE_EVENT.equals(m.role())) {
            return "<event>" + m.content() + "</event>";
        }
        String who = MaMessageStore.ROLE_USER.equals(m.role()) ? "用户"
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
        out.addAll(tools.readOnlyToolsOf(ToolSurface.CHAT));
        if (!isSolo()) {
            out.addAll(tools.toolsOf(SPEAK_TOOLS, ToolSurface.PEER));
            out.addAll(tools.toolsOf(STATE_TOOLS, ToolSurface.PEER));
        }
        PeerRow peer = speakingPeer();
        List<String> extra = directory.declaredTools(peer);
        if (!extra.isEmpty()) {
            out.addAll(tools.toolsOf(extra, ToolSurface.CHAT));
        }
        /* 成员自己的人格锚点：属于「当前发言的成员」，圆桌与私聊都该给，也不需人勾选——
           恒定授予（grantedToolsOf 不过 agentsMayCall，它挡的正是这类只给成员的只读工具，见 ReadMyAnchorTool） */
        out.addAll(tools.grantedToolsOf(List.of(ReadMyAnchorTool.NAME), ToolSurface.CHAT));
        /* 绑定了工作空间才给终端工具：grantedToolsOf 不过 agentsMayCall——它挡的正是这一类 */
        if (!directory.declaredTerminals(peer).isEmpty()) {
            out.addAll(tools.grantedToolsOf(TERMINAL_TOOLS, ToolSurface.CHAT));
        }
        return out;
    }

    /** 此刻正在发言的那位成员（不在发言中时为 null） */
    private PeerRow speakingPeer() {
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
     * 未绑定锚点时由 {@link PeerAnchorService#text(String)} 给一句提示语。
     */
    private String readMyAnchorTool() {
        String peerId = speakPeer.get();
        return peerId == null ? "（此刻不在成员发言中，读不到人格锚点）" : peerAnchors.text(peerId);
    }

    /**
     * 终端工具的落地：<b>收在这位成员绑定的工作空间里</b>（授权名单交给 {@link TerminalService}）。
     *
     * <p>没绑工作空间却收到调用（工具面里并没有它，通常是模型幻觉）时直接拒绝——
     * 绝不能落到父类那条「不限目标」的分发上，否则多智能体这一侧就成了绕过授权的口子。
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
     * （{@code MaTopicService} 靠这一点把话题事件按正确先后补写进记录）。回给模型的那句话
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
