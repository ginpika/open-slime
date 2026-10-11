package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.ChatService;
import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.roundtable.RoundtableMessageStore;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableSessionStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore.EventRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.config.RoundtableProps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 圆桌的推进器：人的一次发言进来，从这里开始由成员轮流说话。
 *
 * <p>一轮的全程（都由本类驱动，{@link RoundtableChatService} 只负责「让某位成员说一次」）：
 * <ol>
 *   <li>人的话落库；没有进行中的话题就开一个，并定出第一位发言者
 *       （话里 @ 了谁——可多位——就点名它们依次各答一句，没 @ 就交给当前应答者策略——见
 *       {@link SpeakerPicker} / {@link SpeakerStrategy}）；</li>
 *   <li>依次往下叫号：授予发言权 → 让 {@link RoundtableChatService#speak} 跑一次 → 记发言事件与它报出的
 *       下一轮发言欲望（欲望是选人策略的依据，见 {@link DesireSpeakerStrategy}）；</li>
 *   <li>每说完一位就做一次终止判定：全体提议结束就关；「除沉默者外都提议结束」就
 *       向沉默者征询一次（{@link TopicStateMachine#prompt}），被征询者下一轮必须二选一；</li>
 *   <li>被征询的沉默者一旦开口（而不是投结束票），撤回所有人刚才的结束提议
 *       （{@link TopicStateMachine#reopen}）——他们重新决策；重开次数封顶，防这条回路转不停。</li>
 * </ol>
 *
 * <p><b>人的每一句新话都开启新的一轮</b>：所以进来第一件事是把上一轮转入沉默的人放回活跃
 * （{@link TopicStateMachine#reviveSilent}）。沉默是「这一轮不说」，不是永远闭嘴；不唤醒他们，
 * 全桌沉默之后这张桌子就再也不会动了（选人只在「活跃 / 待表态」里挑，而全场沉默又凑不齐收场共识）。
 *
 * <p>三处上限都在 {@link RoundtableProps}：一次发言的工具往返、一轮最多推进几次发言、
 * 最多重开几次。人是随时可以插进来的：发新消息即<b>打断</b>当前发言（走
 * {@link RoundtableChatService#requestStop} 那条停止标志，与对话页的「停止」按钮同一机制），
 * 本轮则排进会话议程，等上一轮退出后才跑——同一会话的推进由此串行化
 * （见 {@link RoundtableAgenda}），不会出现两条消息交错写同一个话题。
 *
 * <p><b>会话里只剩一位成员时不走这里这一套</b>：那不是圆桌，而是用户与这位成员一对一私聊，
 * 直接交给 {@link RoundtableChatService#speakSolo}（见 {@link #sendMessage}）——不落话题、不跑状态机。
 */
@Service
public class RoundtableTopicService {

    /**
     * 要补写成消息行（{@link RoundtableMessageStore#ROLE_EVENT}）的跃迁种类。
     *
     * <p>不含 {@code speech}（发言本身已经有消息行）、也不含 {@code floor_granted} /
     * {@code floor_released}：授予与交回发言权是「发言」这一刻的伴生机制，不是成员的
     * 状态切换——记录里出现「甲 开始发言」而紧跟一条「甲：……」只会是噪声。
     */
    private static final Set<String> MIRRORED = Set.of(
            RoundtableTopicEventStore.KIND_TOPIC_OPEN, RoundtableTopicEventStore.KIND_ADDRESSED,
            RoundtableTopicEventStore.KIND_JOINED,
            RoundtableTopicEventStore.KIND_SILENT, RoundtableTopicEventStore.KIND_REVIVED,
            RoundtableTopicEventStore.KIND_PROPOSE_END,
            RoundtableTopicEventStore.KIND_PROMPT, RoundtableTopicEventStore.KIND_REOPEN,
            RoundtableTopicEventStore.KIND_INTERRUPT, RoundtableTopicEventStore.KIND_CLOSE,
            RoundtableTopicEventStore.KIND_ASK_USER, RoundtableTopicEventStore.KIND_RESUME,
            RoundtableTopicEventStore.KIND_TURN_END);

    /** 流式回调：实现方只覆写关心的阶段；正文由 SSE 逐段推给前端 */
    public interface StreamSink {

        /** 话题确定（开新话题或续上旧的） */
        default void topic(String topicId, String status) {
        }

        /** 轮到某位成员发言 */
        default void speaker(String peerId, String peerName) {
        }

        /** 增量片段（同对话流） */
        default void delta(String reasoning, String content) {
        }

        /**
         * 某位成员又开口说了一段（一次 speak）：text 就是这一段话。
         *
         * <p>一次发言可以由好几段组成（边说边想），前端据此让每一段各占一个气泡；它一旦出现，
         * 这一位此前那段正文就到此为止。落库时也是一段一行，于是刷新前后是同一个形状。
         */
        default void speech(String text) {
        }

        /** 一次工具调用（name + args） */
        default void tool(String name, String args) {
        }

        /** 某位成员的发言结束，state 是它这一轮结束时落到的状态 */
        default void speakerEnd(String peerId, String state) {
        }

        /**
         * 话题快照：谁在什么状态、发言权在谁手上。
         *
         * <p>每次状态有变就推一份（开话题、授予发言权、说完、征询、重开、收束），前端据此
         * **边聊边刷新状态条**——否则要等整轮跑完才拉一次，「谁在说、谁沉默了」全程看不见。
         */
        default void state(AgentDtos.TopicView topic) {
        }

        /** 话题收束 */
        default void topicEnd(String topicId) {
        }

        /**
         * 圆桌上发生了一件事（开话题、入场、转沉默、提议结束……），text 是一句自述式文案。
         *
         * <p>这些行同时也落进了 {@code chat_message}（见 {@link #mirrorEvents}），前端只是提前看到它：
         * 整轮跑完回读落库数据时，同一行会被正本替换掉，不会重复。
         */
        default void action(String text) {
        }
    }

    private final RoundtableSessionStore sessions;
    private final RoundtableMessageStore messages;
    private final RoundtableTopicStore topics;
    private final AgentDirectory directory;
    private final SpeakerPicker picker;
    private final TopicStateMachine machine;
    private final RoundtableChatService chat;
    private final RoundtableProps props;
    /** 共享任务区：开会时把受阻条目的负责人请到前排（见 {@link #nudgeBlockedOwners}） */
    private final RoundtableTodoStore taskBoard;

    public RoundtableTopicService(RoundtableSessionStore sessions, RoundtableMessageStore messages, RoundtableTopicStore topics,
                          AgentDirectory directory, SpeakerPicker picker, TopicStateMachine machine,
                          RoundtableChatService chat, RoundtableProps props,
                          RoundtableTodoStore taskBoard) {
        this.sessions = sessions;
        this.messages = messages;
        this.topics = topics;
        this.directory = directory;
        this.picker = picker;
        this.machine = machine;
        this.chat = chat;
        this.props = props;
        this.taskBoard = taskBoard;
    }

    // ---------- 人说话 ----------

    /**
     * 人往圆桌上说一句话：落消息 → 开/续话题 → 成员轮流应答。
     *
     * <p>这是同步方法（一次调用把这一轮跑完），流式增量通过 {@code sink} 往外推。
     *
     * <p><b>调用方必须把它排进会话议程</b>（见 {@link RoundtableAgenda}）：同一会话的推进由议程
     * 串行化——两条消息并发进来时，后到的那次排在队尾，不会与前一轮交错写同一个话题
     * （floor 互相覆盖、两人同时「发言中」）。「人的新话打断上一轮」也由议程入口先做
     * （{@link #interrupt}），本方法只负责跑这一轮。
     *
     * @param speakerStrategy 这一次的应答者策略标识（会话页「⋯」菜单选的）；空 / 不认识就用缺省
     */
    public AgentDtos.TurnResult sendMessage(String sessionId, String input, String modelId,
                                                 String speakerStrategy, StreamSink sink) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("input 为空");
        }
        /* 这一次的选人规则：请求指定了就用它，没指定 / 不认识就用缺省（见 SpeakerPicker#resolve） */
        SpeakerStrategy strategy = picker.resolve(speakerStrategy);
        RoundtableSessionStore.SessionRow session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "多智能体会话不存在: " + sessionId));
        if (modelId != null) {
            sessions.updateModel(sessionId, modelId);
        }
        List<AgentRow> peers = directory.peers(sessionId);
        if (peers.isEmpty()) {
            throw new IllegalStateException("这张桌子上还没有成员：请先在「多智能体 · 成员」页添加成员，"
                    + "或新建一个选了成员的会话");
        }
        /* 桌上只剩一位成员：这不是圆桌，是用户与这位成员一对一私聊——改走旧对话那条流
           （见 RoundtableChatService#speakSolo）。判定就放在这里、每说一句算一遍：成员被删之后，
           老会话自然落到这条路上，不必另存一个「会话模式」字段，也不会与既存数据打架 */
        if (peers.size() == 1) {
            return solo(sessionId, peers.get(0), text, sink);
        }
        return roundtable(sessionId, peers, text, strategy, sink);
    }

    /**
     * 一次圆桌推进（由 {@link RoundtableAgenda} 串行调度）：
     * 开 / 续话题 → 依次叫号 → 收尾。
     */
    private AgentDtos.TurnResult roundtable(String sessionId, List<AgentRow> peers, String text,
                                            SpeakerStrategy strategy, StreamSink sink) {
        Advance adv = open(sessionId, peers, text, strategy, sink);
        /* 同步路径：一口气跑完（行为与从前逐字一致）。事件驱动路径由
           {@link RoundtableDiscussionRuntime} 逐步驱动，在每次状态跃迁之间处理新到达的事件——
           「Turn 不是调度边界」正落在这里：循环的每一圈都是一次独立的状态跃迁。 */
        while (adv.step()) {
            /* 留空是刻意的：中断与插话由停止标志（ChatCancelledException）在 speak 内部生效 */
        }
        adv.finish();
        return new AgentDtos.TurnResult(sessionId, adv.topicId, statusOf(adv.topicId), adv.spoken());
    }

    /**
     * 一对一私聊：请这位成员单独回答。话题、入场、轮流发言这套调度在这里都不适用
     * （它本来就是为一群人设计的），所以不落任何话题与状态行。
     *
     * <p>流上照样先报「谁在说」、再出增量、最后收尾——与圆桌同一条事件口径，前端因此
     * 不必为单人会话分出一套渲染。返回的 TurnResult 没有话题（topicId 为空），
     * 页面据此不显示话题状态条。
     */
    private AgentDtos.TurnResult solo(String sessionId, AgentRow peer, String text,
                                           StreamSink sink) {
        sink.speaker(peer.id(), peer.name());
        chat.speakSolo(sessionId, peer.id(), text, forward(sink));
        sink.speakerEnd(peer.id(), PeerState.ACTIVE.name());
        return new AgentDtos.TurnResult(sessionId, null, null, 1);
    }

    /**
     * 人打断当前发言：正在说话的那位就此打住。两个入口——页面「停止」按钮，以及人又发了一句新话
     * （见 {@code AgentController#chatStream} 在投递前调用）。
     *
     * <p>只置停止标志，<b>不在这里写状态事件</b>：那条「某某的发言被打断」由正在跑的那次推进
     * 在观察到取消时统一补写（见 {@link #advance} 的取消分支）。两处都写会重复记一条；
     * 而没有推进在跑时，本来也就没有被打断的发言。
     */
    public boolean interrupt(String sessionId) {
        return chat.requestStop(sessionId);
    }

    // ---------- 推进回合 ----------

    /**
     * 把任务区里受阻条目的负责人请到前排（记一次举手：下一轮优先被叫到）。
     *
     * <p>受阻条目是「必须给出说法」的事，但它们只躺在任务区里——不点名，成员会当没看见。
     */
    private void nudgeBlockedOwners(String topicId, String sessionId) {
        for (RoundtableTodoStore.TodoRow t : taskBoard.openOnes(sessionId)) {
            if (RoundtableTodoStore.STATUS_BLOCKED.equals(t.status()) && t.ownerPeer() != null) {
                machine.interject(topicId, t.ownerPeer(), "有一条受阻的任务要处理");
            }
        }
    }

    /**
     * 收拾上一轮残留的发言权。
     *
     * <p>一次发言跑到一半断了（异常解栈、进程被杀、页面关了但后台还在写），会留下一位停在
     * 「发言中」且话题的 floor 还指着它。这种状态是死的：选人只看「活跃 / 待表态」，
     * 它永远轮不到，而判定又要全体表态，于是整个话题卡住再也不会动。
     * 新的一句人话进来时先把它放回活跃——那一轮反正已经不存在了。
     */
    private void healDanglingFloor(String topicId) {
        String floor = topics.topic(topicId).map(TopicRow::floorPeer).orElse(null);
        if (floor == null) {
            return;
        }
        if (machine.stateOf(topicId, floor) == PeerState.SPEAKING) {
            machine.releaseFloor(topicId, floor, PeerState.ACTIVE, "上一轮已中断，放回活跃");
        } else {
            machine.clearFloor(topicId);
        }
    }

    /**
     * 开启一次推进，返回<b>可被逐步驱动</b>的句柄（见 {@link Advance}）。
     *
     * <p>这里做的事与从前「roundtable 前半」逐字一致：开 / 续话题、全员在座、收拾残留发言权、
     * 处理等待用户、落轮次水位线、唤醒沉默者、落人的话、推话题与入场状态、定出第一位发言者。
     * 之后的「依次叫号」被打开成一次一次的状态跃迁（{@link Advance#step()}）——
     * 调度器（{@link RoundtableDiscussionRuntime}）因此可以在两次跃迁之间处理新到达的事件。
     */
    public Advance open(String sessionId, List<AgentRow> peers, String text, SpeakerStrategy strategy,
                        StreamSink sink) {
        return open(sessionId, peers, text, strategy, sink, false, null);
    }

    /**
     * 开启一次推进（完整版）：除上述外，还接收两个「新一轮」的提示——
     *
     * @param userRecorded 人的那一句话是否<b>已经</b>落库（见 {@link #recordUserMessage}）：是则这里跳过重复插入
     * @param preferPeerId 上一轮被打断的那位：把它排到最前，让它把没说完的话接着说完
     */
    public Advance open(String sessionId, List<AgentRow> peers, String text, SpeakerStrategy strategy,
                        StreamSink sink, boolean userRecorded, String preferPeerId) {
        List<AgentRow> addressed = picker.addressed(text, peers);
        TopicRow topic = topics.openBySession(sessionId).orElse(null);
        if (topic == null) {
            topic = machine.open(sessionId, RoundtableTopicStore.ORIGIN_USER, null);
        }
        /* 本次会话的成员全部到场：圆桌上坐着的人都在听。只让开场者入场，别人会永远轮不到 */
        machine.seatAll(topic.id(), peers.stream().map(AgentRow::id).toList());
        for (AgentRow a : addressed) {
            machine.join(topic.id(), a.id(), RoundtableTopicEventStore.KIND_ADDRESSED, null);
        }

        String topicId = topic.id();
        /* 任务区里受阻的条目：把负责人请到前排——这轮讨论正是要给它们一个说法。
           重启过 / 隔了很久之后也照此办理：只要还有受阻的活，就不能让全桌只顾着寒暄 */
        nudgeBlockedOwners(topicId, sessionId);
        /* 上一次停在「等人表态」：记下是谁在等（人来了先让它接着答），随后一并复位 */
        Optional<String> waiting = machine.awaitingUser(topicId);
        /* 人的新话开启新的一轮，先落轮次水位线：上一轮报出的发言欲望到此作废
           （见 TopicStateMachine#latestDesires） */
        machine.turnStart(topicId);
        /* **任何阶段都可重入**：把上一轮停在 沉默 / 提议结束 / 待表态 / 等待用户 的人全部放回活跃，
           并清掉残留的发言权位。不这样做，人插话后整桌可能停在「全员提议结束」而谁也挑不出来——
           推进链原地停摆，用户再怎么发也无人应答（见 TopicStateMachine#reenter） */
        machine.reenter(topicId);
        if (!userRecorded) {
            messages.insert(sessionId, topicId, RoundtableMessageStore.ROLE_USER, null, null, text,
                    null, null, null, null);
            sessions.touch(sessionId, abbreviate(text));
        }
        sink.topic(topicId, statusOf(topicId));
        pushState(topicId, sink);
        /* 先把「话题开始 / 各位入场」这几行推出去：它们没有理由等到第一位开口才出现 */
        mirrorEvents(topicId, sink);

        /* 人 @ 了谁就点名它们：这一轮只由被点到的几位依次各答一句，答完即收（见 Advance 的受限轮） */
        if (!addressed.isEmpty()) {
            return new Advance(sessionId, topicId, strategy, sink, addressed);
        }
        AgentRow first = pickFirstSpeaker(sessionId, topicId, peers, strategy, waiting, preferPeerId);
        return new Advance(sessionId, topicId, strategy, sink, first);
    }

    /**
     * 定出本轮第一位发言者，并<b>保证一定有</b>：所有选择都落空时退回名册第一位——
     * 否则整轮会因为没有发言人而直接空转（用户的话得不到任何回应）。
     *
     * <p>优先级：上一轮被打断的那位（把没说完的话接着说完）→ 刚被回应的等待者 →
     * 上一次发言者的下一位（策略）→ 策略开场者 → 名册第一位（兜底）。
     * （人 @ 到人时不经这里——那走受限轮，见 {@link #open}。）
     */
    private AgentRow pickFirstSpeaker(String sessionId, String topicId, List<AgentRow> peers,
                                      SpeakerStrategy strategy, Optional<String> waiting,
                                      String preferPeerId) {
        if (peers.isEmpty()) {
            return null;
        }
        AgentRow preferred = peerIn(peers, preferPeerId);
        if (preferred != null) {
            return preferred;
        }
        if (waiting.isPresent()) {
            AgentRow asker = peerIn(peers, waiting.get());
            if (asker != null) {
                return asker;
            }
        }
        AgentRow next = machine.lastSpeaker(topicId)
                .flatMap(id -> nextSpeaker(topicId, id, strategy)).orElse(null);
        if (next != null) {
            return next;
        }
        return strategy.pickOpener(sessionId, topicId, peers).orElse(peers.get(0));
    }

    /** 在本次会话的成员里按 id 找人（不在其中返回 null） */
    private static AgentRow peerIn(List<AgentRow> peers, String peerId) {
        if (peerId == null) {
            return null;
        }
        return peers.stream().filter(p -> p.id().equals(peerId)).findFirst().orElse(null);
    }

    /**
     * 先把人的一句话落进记录（在任何打断发生<b>之前</b>调用）。
     *
     * <p>顺序即因果：人的新话是「某某被打断」的因，所以它必须先于那条打断提示落库；否则记录里
     * 先看到「被打断」、后看到原因，读起来像两件无关的事（踩过这个坑）。落库后由
     * {@link #open} 带 {@code userRecorded=true} 跳过重复插入。
     */
    public void recordUserMessage(String sessionId, String text) {
        String topicId = topics.openBySession(sessionId).map(TopicRow::id).orElse(null);
        messages.insert(sessionId, topicId, RoundtableMessageStore.ROLE_USER, null, null, text,
                null, null, null, null);
        sessions.touch(sessionId, abbreviate(text));
    }

    /**
     * 自主续推：没有「人的新话」，仅因外部事件（场外报告 / 请讨论 / 任务完成）把进行中的话题再推一轮。
     *
     * <p>这是「成员自己埋头做 → 做完 / 遇阻 → 通知彼此 → 接着讨论」闭环的驱动点：人的一句话不再是
     * 唯一的驱动源（设计一 §二：Event 才是系统推进的基本单位）。
     *
     * <p>与 {@link #open} 的区别：不落人的话、不开新话题——它只是把上一轮的沉默者唤回、按策略选一位
     * 接着开口。没有进行中的话题时返回 null（不凭空重开一张已经收场的桌子）。
     */
    public Advance openAutonomous(String sessionId, List<AgentRow> peers, SpeakerStrategy strategy,
                                  StreamSink sink) {
        TopicRow topic = topics.openBySession(sessionId).orElse(null);
        if (topic == null) {
            return null;
        }
        String topicId = topic.id();
        machine.seatAll(topicId, peers.stream().map(AgentRow::id).toList());
        healDanglingFloor(topicId);
        machine.resume(topicId);
        /* 新的一轮：落水位线（上一轮的发言欲望作废），并把沉默者放回活跃——否则全桌沉默之后再没人能被叫到 */
        machine.turnStart(topicId);
        machine.reviveSilent(topicId);
        /* 「任何阶段都可重入」的兜底：若此刻连一个能被叫到的人都没有（全桌停在提议结束 / 待表态 /
           等待用户这类状态），把它们一并放回活跃——否则这一轮没人开口，事件就白报了 */
        if (SpeakerStrategy.eligible(peers, machine.states(topicId)).isEmpty()) {
            machine.reenter(topicId);
        }
        sink.topic(topicId, statusOf(topicId));
        pushState(topicId, sink);
        mirrorEvents(topicId, sink);
        Optional<String> last = machine.lastSpeaker(topicId);
        AgentRow first = (last.isPresent()
                ? nextSpeaker(topicId, last.get(), strategy)
                : strategy.pickOpener(sessionId, topicId, peers)).orElse(null);
        if (first == null) {
            first = peers.isEmpty() ? null : peers.get(0);   // 最后一层：绝不空转
        }
        /* 自主续推没有「点名」这回事：照常一轮轮推下去 */
        return new Advance(sessionId, topicId, strategy, sink, first);
    }

    /**
     * 定向续推：执行态的某位成员受阻，点名要某（几）位同伴给个说法（受阻成员的「找谁」决策）。
     *
     * <p>与 {@link #openAutonomous} 的区别：只把被点名的这几位叫起来依次各说一句——复用「人 @ 了成员」
     * 那条 {@link Advance} 的受限轮（每人答完即收，其他人不接话）。其余成员不受打扰，
     * 于是「受阻只需和需要沟通的那几位沟通一轮」在调度上成立。
     *
     * <p>话题不存在、或一个有效目标都没有时返回 null（不凭空重开一张已经收场的桌子）。
     */
    public Advance openDirected(String sessionId, List<AgentRow> peers, List<AgentRow> targets,
                                StreamSink sink) {
        TopicRow topic = topics.openBySession(sessionId).orElse(null);
        List<AgentRow> queue = new ArrayList<>();
        if (targets != null) {
            for (AgentRow t : targets) {
                if (t != null && queue.stream().noneMatch(q -> q.id().equals(t.id()))) {
                    queue.add(t);
                }
            }
        }
        if (topic == null || queue.isEmpty()) {
            return null;
        }
        String topicId = topic.id();
        /* 名册上的人都「在座」（只对非活跃者写入场，幂等）：后续全桌轮不至于因为有人没入场而叫不到 */
        machine.seatAll(topicId, peers.stream().map(AgentRow::id).toList());
        /* 上一次停在「等待用户」也没关系：把等待复位 */
        machine.resume(topicId);
        machine.turnStart(topicId);
        /* 点名：让它们进场（即便此刻沉默 / 已提议结束也能开口），并各落一条「被点名」让记录看得见 */
        for (AgentRow t : queue) {
            machine.join(topicId, t.id(), RoundtableTopicEventStore.KIND_ADDRESSED, "受阻定向沟通");
        }
        sink.topic(topicId, statusOf(topicId));
        pushState(topicId, sink);
        mirrorEvents(topicId, sink);
        /* 受限轮：只由队列里的人依次各答一句，说完即收（与「人 @ 了成员」同一机制） */
        return new Advance(sessionId, topicId, picker.resolve(null), sink, queue);
    }

    /**
     * 一次推进的调度句柄：把原来「一口气跑完整轮的 while」打开成可被逐步驱动的形式。
     *
     * <p>设计一的核心边界：<b>Turn 是一次推理，Event 才是系统推进的基本单位</b>。每调用一次
     * {@link #step()} 就完成一次状态跃迁（授予发言权 → 让一位成员说一次 → 提交 → 终止判定 → 选下一位）；
     * 两次跃迁之间，调度器可以处理取消、插话与场外报告事件，据此决定继续、重排或收束。
     *
     * <p>取消语义：{@link #cancel()} 只标记「这一步之后不再继续」；正在跑的那次 {@code speak} 由
     * 停止标志（{@code ChatCancelledException}）中断，已说出的部分照常落库。
     */
    public final class Advance {

        private final String sessionId;
        private final SpeakerStrategy strategy;
        private final StreamSink sink;
        /** 本轮发过言的成员（按顺序）：本轮结束的清单用它拼，不另外折叠事件流 */
        private final List<String> spoke = new ArrayList<>();
        private final int max;
        private final String topicId;
        private AgentRow next;
        private int spoken;
        private boolean cancelled;
        /**
         * 本轮是「受限轮」：只有队列里的人开口（人 @ 多位的点名轮、或受阻定向沟通轮），
         * 每位各答一句、说完即收，其他人不接话。null = 普通轮（照常按应答者策略往下轮）。
         */
        private final List<AgentRow> restricted;
        /** 受限轮里下一位在 {@link #restricted} 中的下标（首位已作为 {@link #next}） */
        private int restrictedIdx;
        /** 本轮因人的新话被打断的那位（没人被打断时为 null）；新一轮据此把它排到最前，把话接着说 */
        private String interruptedPeer;

        /** 普通轮：首位由应答者策略 / 兜底定出，之后照常一轮轮往下推 */
        Advance(String sessionId, String topicId, SpeakerStrategy strategy, StreamSink sink,
                AgentRow first) {
            this(sessionId, topicId, strategy, sink, first, null);
        }

        /** 受限轮：只由 {@code queue} 里的人依次各答一句（人 @ 多位的点名轮 / 受阻定向沟通轮） */
        Advance(String sessionId, String topicId, SpeakerStrategy strategy, StreamSink sink,
                List<AgentRow> queue) {
            this(sessionId, topicId, strategy, sink, queue.isEmpty() ? null : queue.get(0), queue);
        }

        private Advance(String sessionId, String topicId, SpeakerStrategy strategy, StreamSink sink,
                        AgentRow first, List<AgentRow> restricted) {
            this.sessionId = sessionId;
            this.topicId = topicId;
            this.strategy = strategy;
            this.sink = sink;
            this.restricted = restricted;
            this.restrictedIdx = 1;   // 首位已经作为 next，队列从第二个起
            int configured = props.getMaxSpeechesPerTurn();
            /* 0 / 负数 = 不设上限（讨论自己走到收场或被打断为止，见 RoundtableProps#maxSpeechesPerTurn） */
            this.max = configured <= 0 ? Integer.MAX_VALUE : configured;
            this.next = first;
        }

        /** 本次推进的话题 id */
        public String topicId() {
            return topicId;
        }

        /** 本轮实际说了几次 */
        public int spoken() {
            return spoken;
        }

        /** 是否已被取消（用户取消 / 插话重排） */
        public boolean cancelled() {
            return cancelled;
        }

        /** 请求取消：这一步之后不再继续 */
        public void cancel() {
            this.cancelled = true;
        }

        /** 本轮因人的新话被打断的那位（没有则 null）；新一轮据此让它接着把话说完 */
        public String interruptedPeer() {
            return interruptedPeer;
        }

        /** 还有下一位可跑吗（受限轮不受「一轮最多几次发言」约束——队列本身就是上限） */
        public boolean runnable() {
            return !cancelled && next != null && (restricted != null || spoken < max);
        }

        /**
         * 执行一次状态跃迁；返回 true 表示「还可以继续」（对应从前 while 的下一次迭代）。
         *
         * <p>无论走哪个出口（收束 / 等人表态 / 被打断 / 到上限），收尾都由 {@link #finish()} 落定。
         */
        public boolean step() {
            if (!runnable()) {
                return false;
            }
            /* 上一位的跃迁在这里补成消息行：它的发言行此刻已经落库，先后顺序才对得上 */
            mirrorEvents(topicId, sink);
            machine.grantFloor(topicId, next.id());
            sink.speaker(next.id(), next.name());
            pushState(topicId, sink);
            RoundtableChatService.Speech speech;
            try {
                speech = chat.speak(topicId, next.id(), forward(sink));
            } catch (ChatService.ChatCancelledException cancelledEx) {
                /* 人说了一句打断（或按了停止）：当前这位的话停在这里，本轮到此为止 */
                machine.interrupt(topicId, RoundtableTopicEventStore.ACTOR_USER, next.id(), "被打断");
                this.interruptedPeer = next.id();
                sink.speakerEnd(next.id(), PeerState.ACTIVE.name());
                pushState(topicId, sink);
                this.cancelled = true;
                return false;
            } catch (RuntimeException e) {
                /* 这次发言根本没跑起来（如 LLM 未配置）：把发言权交回、状态回落，
                   免得话题永远卡在「某某发言中」——它其实一句话都没说 */
                machine.releaseFloor(topicId, next.id(), PeerState.ACTIVE,
                        "发言未能完成：" + e.getMessage());
                sink.speakerEnd(next.id(), PeerState.ACTIVE.name());
                pushState(topicId, sink);
                throw e;
            }
            if (speech.stale()) {
                /* 版本校验失败（设计一 §五-1）：生成期间人又插了新话，这次结果已被取代——
                   丢弃它，不记成正式发言、也不进本轮上下文；发言权交回，本轮作废，
                   由调度器基于最新状态重新调度。旧结果不覆盖新状态。 */
                machine.releaseFloor(topicId, next.id(), PeerState.ACTIVE, "结果已过期（人已补充新要求）");
                sink.speakerEnd(next.id(), PeerState.ACTIVE.name());
                pushState(topicId, sink);
                this.cancelled = true;
                return false;
            }
            /* 生成 → 校验 → 发布：整段生成完毕、版本校验通过后，才把这次发言按序发布出去
               （发布权在协调器；并行的、或被取代的生成走不到这里） */
            chat.publish(speech, forward(sink));
            machine.speech(topicId, next.id(), speech.reply().message(), speech.messageId());
            recordDesire(topicId, next.id(), speech.desire());

            PeerState after = machine.stateOf(topicId, next.id());
            if (after == PeerState.PROMPTED) {
                /* 被要求表态却什么都没说：代码替它收尾——静默不算表态，按结束处理，否则判定链停死 */
                machine.proposeEnd(topicId, next.id(), "被征询表态但未表态，按结束处理");
                after = PeerState.PROPOSE_END;
            }
            if (after == PeerState.SPEAKING) {
                /* 没碰任何状态机工具：说完就还想听，回到活跃等下一次轮 */
                machine.releaseFloor(topicId, next.id(), PeerState.ACTIVE, null);
                after = PeerState.ACTIVE;
            } else {
                machine.clearFloor(topicId);
            }
            sink.speakerEnd(next.id(), after.name());
            pushState(topicId, sink);
            spoken++;
            spoke.add(next.name());

            /* 有人请用户表态：整桌就此打住——剩下的人都在等这位用户给的态度，再叫下一位没有意义 */
            if (after == PeerState.AWAIT_USER) {
                return false;
            }

            /* 点名 / 定向轮（受限轮）：队列里的人依次各答一句，说完即收——其他人不必再挨个接一遍话。
               若这一下恰好凑齐「全员提议结束」，顺势收束；但不做向沉默者征询那套动作 */
            if (restricted != null) {
                if (machine.allProposedEnd(topicId)) {
                    close(topicId, sink);
                }
                next = restrictedIdx < restricted.size() ? restricted.get(restrictedIdx++) : null;
                return runnable();
            }

            /* 被征询的沉默者开口了（而不是投结束票）：撤回所有人的结束提议，大家重新决策 */
            if (after == PeerState.ACTIVE && isClosing(topicId)) {
                machine.reopen(topicId);
                sink.topic(topicId, RoundtableTopicStore.STATUS_OPEN);
                pushState(topicId, sink);
            }

            if (settle(topicId, sink)) {
                return false;
            }
            next = nextSpeaker(topicId, next.id(), strategy).orElse(null);
            return runnable();
        }

        /**
         * 收尾：落本轮客观清单，再补写最后一批状态动作行。
         *
         * <p><b>次序是刻意的</b>：清单事件先 append，再 {@link #mirrorEvents} —— 否则这条事件当轮
         * 没被镜像，会拖到下一轮 {@code open()} 才补写，于是「本轮结束」跑到用户新话之后，
         * 看起来像「用户一说完就收场了」（踩过这个坑）。
         *
         * <p>本轮若是被人的新话打断，清单改写「本轮被打断」而不是「本轮结束」——避免被误读成收场。
         */
        public void finish() {
            /* 受限轮的收场由队列定，不是「发言次数上限」打断的 */
            boolean budgetExhausted = restricted == null && spoken >= max && !spoke.isEmpty();
            machine.turnEnd(topicId, interruptedPeer != null
                    ? "本轮被打断：" + (spoke.isEmpty() ? "无人发言" : String.join("、", spoke) + " 发言")
                    : turnSummary(spoke, budgetExhausted));
            mirrorEvents(topicId, sink);
        }
    }

    /** 本轮结束的一句话清单：只写事实（谁发了言、是不是被次数上限打断），不写「谁为什么没说」 */
    private static String turnSummary(List<String> spoke, boolean budgetExhausted) {
        String who = spoke.isEmpty() ? "无人发言" : String.join("、", spoke) + " 发言";
        return "本轮结束：" + who + (budgetExhausted ? "（发言次数已达上限）" : "");
    }

    /**
     * 终止判定：能收就收，收不了就看看是不是该向沉默者征询。
     *
     * @return true 表示话题已经收束
     */
    private boolean settle(String topicId, StreamSink sink) {
        if (machine.allProposedEnd(topicId)) {
            close(topicId, sink);
            return true;
        }
        if (!machine.allNonSilentProposedEnd(topicId)) {
            return false;
        }
        List<String> silent = machine.silentPeers(topicId);
        if (silent.isEmpty()) {
            /* 「除沉默者外都提议结束」但一个沉默者也没有——说明只剩发言中的人，等它说完再看 */
            return false;
        }
        TopicRow topic = topics.topic(topicId).orElse(null);
        int round = topic == null ? 0 : topic.resolveRound();
        if (round >= Math.max(0, props.getMaxResolveRounds())) {
            /* 重开次数封顶：沉默者视为同意结束，收束（否则「发言 → 重开」可以一直转） */
            close(topicId, sink);
            return true;
        }
        machine.markClosing(topicId);
        sink.topic(topicId, RoundtableTopicStore.STATUS_CLOSING);
        for (String peerId : silent) {
            machine.prompt(topicId, peerId, "除你之外的人都提议结束了：请立刻在「发言」与「结束」之间二选一");
        }
        pushState(topicId, sink);
        return false;
    }

    /** 收束话题并告知前端 */
    private void close(String topicId, StreamSink sink) {
        machine.close(topicId);
        pushState(topicId, sink);
        sink.topicEnd(topicId);
    }

    /** 推一份话题快照给前端（状态条边聊边刷新）；话题已不存在时静默跳过 */
    private void pushState(String topicId, StreamSink sink) {
        topics.topic(topicId).ifPresent(row -> sink.state(toTopicView(row)));
    }

    /**
     * 下一位该谁：有人举手（抢答）就先给它，否则交给当前应答者策略（见 {@link SpeakerStrategy}）。
     *
     * <p>抢答是策略之上的优先级——它表达的是「有要紧话必须现在说」，比任何排序规则都硬，
     * 所以先于策略裁决。剩下的「谁被叫到」由策略在「活跃 / 待表态」里挑：某位成员从没被叫到，
     * 通常是它的状态为「非活跃」（没入场），策略永远轮不到它。
     */
    private Optional<AgentRow> nextSpeaker(String topicId, String afterPeerId, SpeakerStrategy strategy) {
        Map<String, PeerState> states = machine.states(topicId);
        String hand = machine.handRaiser(topicId).orElse(null);
        if (hand != null && !hand.equals(afterPeerId)) {
            PeerState state = states.getOrDefault(hand, PeerState.INACTIVE);
            if (state == PeerState.ACTIVE || state == PeerState.PROMPTED) {
                return directory.peer(hand);
            }
        }
        /* 选人只在本次会话的成员里进行：名册后来多了人，也不会插进这张已经开着的桌子 */
        String sessionId = topics.topic(topicId).map(TopicRow::sessionId).orElse(null);
        return strategy.pickNext(topicId, directory.peers(sessionId), states, afterPeerId);
    }

    /**
     * 记下这位成员报出的「下一轮发言欲望」（0~10）——选人策略据此定序（见 {@link DesireSpeakerStrategy}）。
     *
     * <p>夹紧在这里做：模型给的是自然语言里抠出来的整数，越界（999 / 负数）不该把整桌的定序带偏。
     * 没报（模型漏了、或没按信封约定输出）就不记，取用时按默认欲望兜底——缺席不会让谁被落下。
     */
    private void recordDesire(String topicId, String peerId, Integer desire) {
        if (desire != null) {
            machine.desire(topicId, peerId, DesireSpeakerStrategy.clamp(desire));
        }
    }

    // ---------- 只读（页面用） ----------

    /** 某会话的全部话题（新→旧） */
    public List<AgentDtos.TopicView> topics(String sessionId) {
        return topics.topicsBySession(sessionId).stream().map(this::toTopicView).toList();
    }

    /** 某会话当前进行中的话题（页面据它高亮「讨论中」） */
    public Optional<AgentDtos.TopicView> currentTopic(String sessionId) {
        return topics.openBySession(sessionId).map(this::toTopicView);
    }

    private AgentDtos.TopicView toTopicView(TopicRow row) {
        Map<String, PeerState> states = machine.states(row.id());
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> comms = new LinkedHashMap<>();
        Map<String, String> execs = new LinkedHashMap<>();
        /* 列本次会话的成员（含「非活跃」）：状态条要让人一眼看到这张桌子上都有谁、各自什么状态；
           只列已开口的人，第一句之前那一条就是空的 */
        for (AgentRow peer : directory.peers(row.sessionId())) {
            PeerState state = states.getOrDefault(peer.id(), PeerState.INACTIVE);
            names.put(peer.name(), state.name());
            /* 二维状态（设计一 §四）：同一份事件折叠出的轮次参与状态，投影成
               「向不向讨论室发言」×「工作走到哪」——于是「不说话但在跑」这类场面可表达 */
            PeerStatus status = PeerStatus.of(state);
            comms.put(peer.name(), status.communication().name());
            execs.put(peer.name(), status.execution().name());
        }
        return new AgentDtos.TopicView(row.id(), row.sessionId(), row.status(),
                row.floorPeer(), row.resolveRound(), names, comms, execs,
                machine.participants(row.id()), row.createdAt(), row.closedAt());
    }

    // ---------- 私有 ----------

    /** 成员发言的流式回调转发：只把增量、发言分段与工具调用转出去（父类的 meta 带的是 -1，无意义） */
    private ChatService.StreamSink forward(StreamSink sink) {
        return new ChatService.StreamSink() {
            @Override
            public void delta(String reasoning, String content) {
                sink.delta(reasoning, content);
            }

            @Override
            public void speech(String text) {
                sink.speech(text);
            }

            @Override
            public void tool(String name, String args) {
                sink.tool(name, args);
            }
        };
    }

    /**
     * 把话题事件流里的跃迁补写成消息行（{@link RoundtableMessageStore#ROLE_EVENT}）——圆桌的记录因此
     * 不只是「谁说了什么」，还有「谁在什么时候转沉默、投了结束票、被打断」。
     *
     * <p><b>为什么按水位线补写，而不是事件一落库就写</b>：状态工具（go_silent / propose_end /
     * interject）是模型在<b>发言中途</b>调用的，那一刻这位成员的发言行还没落库（发言行是整轮跑完
     * 才写的）。就地写会让记录变成「乙 转入沉默」排在「乙：……」之前，读不通。按
     * {@code chat_message.event_seq} 这条水位线、在发言行之后补写，先后顺序才对得上。
     *
     * <p>水位线存在行里（见 {@link RoundtableMessageStore#maxEventSeq}），所以本方法随时可以重跑：
     * 中途异常少写的那几条，下一轮开头会补上，不重不漏。
     */
    private void mirrorEvents(String topicId, StreamSink sink) {
        TopicRow topic = topics.topic(topicId).orElse(null);
        if (topic == null) {
            return;
        }
        int watermark = messages.maxEventSeq(topicId);
        for (EventRow e : machine.timeline(topicId)) {
            if (e.seq() <= watermark || !MIRRORED.contains(e.kind())) {
                continue;
            }
            String text = line(e);
            if (text == null) {
                continue;
            }
            messages.insertEvent(topic.sessionId(), topicId, e.seq(), e.peerId(),
                    nameOf(e.peerId()), text, e.kind());
            /* 顺手推给前端：状态行不必等整轮跑完回读才出现 */
            sink.action(text);
        }
    }

    /**
     * 一条跃迁的自述式文案。刻意不带「人名：」前缀：它不是某个人说的话，而是圆桌上发生的事，
     * 模型与人都该一眼看出这一行是「动作」，不是「发言」。
     */
    private String line(EventRow e) {
        if (RoundtableTopicEventStore.KIND_TOPIC_OPEN.equals(e.kind())) {
            return "话题开始";
        }
        /* 本轮结束：content 已经是完整的一句话（由推进方拼好），不加人名前缀、不加括号 */
        if (RoundtableTopicEventStore.KIND_TURN_END.equals(e.kind())) {
            String text = e.content() == null ? "" : e.content().strip();
            return text.isEmpty() ? null : text;
        }
        String who = nameOf(e.peerId());
        if (who == null || who.isBlank()) {
            return null;
        }
        return switch (e.kind()) {
            case RoundtableTopicEventStore.KIND_ADDRESSED -> who + " 被点名";
            case RoundtableTopicEventStore.KIND_JOINED -> who + " 入场";
            case RoundtableTopicEventStore.KIND_INTERJECT -> who + " 举手抢答" + reason(e);
            case RoundtableTopicEventStore.KIND_SILENT -> who + " 转入沉默" + reason(e);
            case RoundtableTopicEventStore.KIND_REVIVED -> who + " 重新参与讨论";
            case RoundtableTopicEventStore.KIND_PROPOSE_END -> who + " 提议结束" + reason(e);
            case RoundtableTopicEventStore.KIND_PROMPT -> who + " 被要求立刻表态";
            /* 重开是逐人一条事件（每个此前提议结束的人各撤一条），所以按人写、不写成一件事 */
            case RoundtableTopicEventStore.KIND_REOPEN -> who + " 撤回结束提议，重新决策";
            case RoundtableTopicEventStore.KIND_ASK_USER -> who + " 请用户表态" + reason(e);
            case RoundtableTopicEventStore.KIND_RESUME -> who + " 收到用户回应，继续";
            case RoundtableTopicEventStore.KIND_INTERRUPT -> who + " 的发言被打断";
            case RoundtableTopicEventStore.KIND_CLOSE -> who + " 退场";
            default -> null;
        };
    }

    /** 跃迁的理由（事件 content 存的就是它）；没有就不加括号 */
    private static String reason(EventRow e) {
        String text = e.content() == null ? "" : e.content().strip();
        return text.isEmpty() ? "" : "（" + text + "）";
    }

    private boolean isClosing(String topicId) {
        return topics.topic(topicId)
                .map(t -> RoundtableTopicStore.STATUS_CLOSING.equals(t.status()))
                .orElse(false);
    }

    private String statusOf(String topicId) {
        return topics.topic(topicId).map(TopicRow::status).orElse(RoundtableTopicStore.STATUS_CLOSED);
    }

    private String nameOf(String peerId) {
        return peerId == null ? null : directory.nameOf(peerId);
    }

    /** 会话标题：首句截断（与对话页同一规则） */
    private static String abbreviate(String input) {
        String s = input.strip();
        return s.length() <= 18 ? s : s.substring(0, 18);
    }
}
