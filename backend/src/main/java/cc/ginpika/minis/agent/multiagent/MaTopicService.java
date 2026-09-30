package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.chat.ChatService;
import cc.ginpika.minis.agent.multiagent.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.multiagent.dto.MultiAgentDtos;
import cc.ginpika.minis.agent.multiagent.store.MaMessageStore;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import cc.ginpika.minis.agent.multiagent.store.MaSessionStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicEventStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicEventStore.EventRow;
import cc.ginpika.minis.agent.multiagent.store.MaTopicStore;
import cc.ginpika.minis.agent.multiagent.store.MaTopicStore.TopicRow;
import cc.ginpika.minis.config.MultiAgentProps;
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
 * <p>一轮的全程（都由本类驱动，{@link MaChatService} 只负责「让某位成员说一次」）：
 * <ol>
 *   <li>人的话落库；没有进行中的话题就开一个，并定出第一位发言者
 *       （话里 @ 了谁就是谁，没 @ 就交给当前应答者策略——见 {@link SpeakerPicker} /
 *       {@link SpeakerStrategy}）；</li>
 *   <li>依次往下叫号：授予发言权 → 让 {@link MaChatService#speak} 跑一次 → 记发言事件与它报出的
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
 * <p>三处上限都在 {@link MultiAgentProps}：一次发言的工具往返、一轮最多推进几次发言、
 * 最多重开几次。人是随时可以插进来的：发新消息即打断当前发言（{@link #interrupt}，
 * 走的是父类那条停止标志，与对话页的「停止」按钮同一机制）。
 *
 * <p><b>会话里只剩一位成员时不走这里这一套</b>：那不是圆桌，而是用户与这位成员一对一私聊，
 * 直接交给 {@link MaChatService#speakSolo}（见 {@link #sendMessage}）——不落话题、不跑状态机。
 */
@Service
public class MaTopicService {

    /**
     * 要补写成消息行（{@link MaMessageStore#ROLE_EVENT}）的跃迁种类。
     *
     * <p>不含 {@code speech}（发言本身已经有消息行）、也不含 {@code floor_granted} /
     * {@code floor_released}：授予与交回发言权是「发言」这一刻的伴生机制，不是成员的
     * 状态切换——记录里出现「甲 开始发言」而紧跟一条「甲：……」只会是噪声。
     */
    private static final Set<String> MIRRORED = Set.of(
            MaTopicEventStore.KIND_TOPIC_OPEN, MaTopicEventStore.KIND_ADDRESSED,
            MaTopicEventStore.KIND_JOINED, MaTopicEventStore.KIND_INTERJECT,
            MaTopicEventStore.KIND_SILENT, MaTopicEventStore.KIND_REVIVED,
            MaTopicEventStore.KIND_PROPOSE_END,
            MaTopicEventStore.KIND_PROMPT, MaTopicEventStore.KIND_REOPEN,
            MaTopicEventStore.KIND_INTERRUPT, MaTopicEventStore.KIND_CLOSE,
            MaTopicEventStore.KIND_ASK_USER, MaTopicEventStore.KIND_RESUME);

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
        default void state(MultiAgentDtos.TopicView topic) {
        }

        /** 话题收束 */
        default void topicEnd(String topicId) {
        }

        /**
         * 圆桌上发生了一件事（开话题、入场、转沉默、提议结束……），text 是一句自述式文案。
         *
         * <p>这些行同时也落进了 {@code ma_message}（见 {@link #mirrorEvents}），前端只是提前看到它：
         * 整轮跑完回读落库数据时，同一行会被正本替换掉，不会重复。
         */
        default void action(String text) {
        }
    }

    private final MaSessionStore sessions;
    private final MaMessageStore messages;
    private final MaTopicStore topics;
    private final PeerDirectory directory;
    private final SpeakerPicker picker;
    private final TopicStateMachine machine;
    private final MaChatService chat;
    private final MultiAgentProps props;

    public MaTopicService(MaSessionStore sessions, MaMessageStore messages, MaTopicStore topics,
                          PeerDirectory directory, SpeakerPicker picker, TopicStateMachine machine,
                          MaChatService chat, MultiAgentProps props) {
        this.sessions = sessions;
        this.messages = messages;
        this.topics = topics;
        this.directory = directory;
        this.picker = picker;
        this.machine = machine;
        this.chat = chat;
        this.props = props;
    }

    // ---------- 人说话 ----------

    /**
     * 人往圆桌上说一句话：落消息 → 开/续话题 → 成员轮流应答。
     *
     * <p>这是同步方法（一次调用把这一轮跑完），流式增量通过 {@code sink} 往外推；
     * 调用方把它放在自己的线程里跑（控制器用的是虚拟线程 + SSE）。
     *
     * @param speakerStrategy 这一次的应答者策略标识（会话页「⋯」菜单选的）；空 / 不认识就用缺省
     */
    public MultiAgentDtos.TurnResult sendMessage(String sessionId, String input, String modelId,
                                                 String speakerStrategy, StreamSink sink) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("input 为空");
        }
        /* 这一次的选人规则：请求指定了就用它，没指定 / 不认识就用缺省（见 SpeakerPicker#resolve） */
        SpeakerStrategy strategy = picker.resolve(speakerStrategy);
        MaSessionStore.SessionRow session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "多智能体会话不存在: " + sessionId));
        if (modelId != null) {
            sessions.updateModel(sessionId, modelId);
        }
        List<PeerRow> peers = directory.peers(sessionId);
        if (peers.isEmpty()) {
            throw new IllegalStateException("这张桌子上还没有成员：请先在「多智能体 · 成员」页添加成员，"
                    + "或新建一个选了成员的会话");
        }
        /* 桌上只剩一位成员：这不是圆桌，是用户与这位成员一对一私聊——改走旧对话那条流
           （见 MaChatService#speakSolo）。判定就放在这里、每说一句算一遍：成员被删之后，
           老会话自然落到这条路上，不必另存一个「会话模式」字段，也不会与既存数据打架 */
        if (peers.size() == 1) {
            return solo(sessionId, peers.get(0), text, sink);
        }

        Optional<PeerRow> addressed = picker.addressed(text, peers);
        TopicRow topic = topics.openBySession(sessionId).orElse(null);
        if (topic == null) {
            topic = machine.open(sessionId, MaTopicStore.ORIGIN_USER, null);
        }
        /* 本次会话的成员全部到场：圆桌上坐着的人都在听。只让开场者入场，别人会永远轮不到 */
        machine.seatAll(topic.id(), peers.stream().map(PeerRow::id).toList());
        if (addressed.isPresent()) {
            machine.join(topic.id(), addressed.get().id(), MaTopicEventStore.KIND_ADDRESSED, null);
        }

        String topicId = topic.id();
        healDanglingFloor(topicId);
        /* 上一次停在「等人表态」：人来了，把等待中的那位放回活跃，并让它先接着答 */
        Optional<String> waiting = machine.awaitingUser(topicId);
        machine.resume(topicId);
        /* 人的新话开启新的一轮：把上一轮转入沉默的人放回活跃。全桌都沉默时这一步尤其关键——
           沉默者不在可被叫到之列，不唤醒他们这张桌子就再也不会动了（见 TopicStateMachine#reviveSilent） */
        machine.reviveSilent(topicId);
        messages.insert(sessionId, topicId, MaMessageStore.ROLE_USER, null, null, text,
                null, null, null, null);
        sessions.touch(sessionId, abbreviate(text));
        sink.topic(topicId, statusOf(topicId));
        pushState(topicId, sink);
        /* 先把「话题开始 / 各位入场」这几行推出去：它们没有理由等到第一位开口才出现 */
        mirrorEvents(topicId, sink);

        /* 续在旧话题上时从「上一次发言的那位」往后轮转；新话题则由策略定开场者（顺序轮转按上一轮
           的开场者顺延，发言欲望优先队列则取欲望最高的一位——首轮人人平手，于是随机） */
        PeerRow first = addressed.orElseGet(() -> {
            /* 刚被用户回应的那位先答：它就是在等这句话 */
            if (waiting.isPresent()) {
                PeerRow asker = directory.peer(waiting.get()).orElse(null);
                if (asker != null) {
                    return asker;
                }
            }
            Optional<String> last = machine.lastSpeaker(topicId);
            if (last.isPresent()) {
                return nextSpeaker(topicId, last.get(), strategy).orElse(null);
            }
            return strategy.pickOpener(sessionId, topicId, peers).orElse(null);
        });
        int speeches = advance(topicId, first, strategy, sink);
        /* 收尾再补一次：本轮最后的表态、征询与收束都在 advance 返回前才落定 */
        mirrorEvents(topicId, sink);
        return new MultiAgentDtos.TurnResult(sessionId, topicId, statusOf(topicId), speeches);
    }

    /**
     * 一对一私聊：请这位成员单独回答。话题、入场、轮流发言这套调度在这里都不适用
     * （它本来就是为一群人设计的），所以不落任何话题与状态行。
     *
     * <p>流上照样先报「谁在说」、再出增量、最后收尾——与圆桌同一条事件口径，前端因此
     * 不必为单人会话分出一套渲染。返回的 TurnResult 没有话题（topicId 为空），
     * 页面据此不显示话题状态条。
     */
    private MultiAgentDtos.TurnResult solo(String sessionId, PeerRow peer, String text,
                                           StreamSink sink) {
        sink.speaker(peer.id(), peer.name());
        chat.speakSolo(sessionId, peer.id(), text, forward(sink));
        sink.speakerEnd(peer.id(), PeerState.ACTIVE.name());
        return new MultiAgentDtos.TurnResult(sessionId, null, null, 1);
    }

    /** 人打断当前发言（前端「停止」按钮，或人又发了新消息）：正在说话的那位就此打住 */
    public boolean interrupt(String sessionId, String reason) {
        boolean stopped = chat.requestStop(sessionId);
        TopicRow topic = topics.openBySession(sessionId).orElse(null);
        if (topic != null && topic.floorPeer() != null) {
            machine.interrupt(topic.id(), MaTopicEventStore.ACTOR_USER, topic.floorPeer(),
                    reason == null ? "人插话" : reason);
        }
        return stopped;
    }

    // ---------- 推进回合 ----------

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
     * 依次叫号，直到话题收束或到达上限。
     *
     * @return 本轮实际说了几次
     */
    private int advance(String topicId, PeerRow first, SpeakerStrategy strategy, StreamSink sink) {
        int spoken = 0;
        PeerRow next = first;
        int max = Math.max(1, props.getMaxSpeechesPerTurn());
        while (next != null && spoken < max) {
            /* 上一位的跃迁在这里补成消息行：它的发言行此刻已经落库，先后顺序才对得上 */
            mirrorEvents(topicId, sink);
            machine.grantFloor(topicId, next.id());
            sink.speaker(next.id(), next.name());
            pushState(topicId, sink);
            MaChatService.Speech speech;
            try {
                speech = chat.speak(topicId, next.id(), forward(sink));
            } catch (ChatService.ChatCancelledException cancelled) {
                /* 人说了一句打断（或按了停止）：当前这位的话停在这里，本轮到此为止 */
                machine.interrupt(topicId, MaTopicEventStore.ACTOR_USER, next.id(), "被打断");
                sink.speakerEnd(next.id(), PeerState.ACTIVE.name());
                pushState(topicId, sink);
                return spoken;
            } catch (RuntimeException e) {
                /* 这次发言根本没跑起来（如 LLM 未配置）：把发言权交回、状态回落，
                   免得话题永远卡在「某某发言中」——它其实一句话都没说 */
                machine.releaseFloor(topicId, next.id(), PeerState.ACTIVE,
                        "发言未能完成：" + e.getMessage());
                sink.speakerEnd(next.id(), PeerState.ACTIVE.name());
                pushState(topicId, sink);
                throw e;
            }
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

            /* 有人请用户表态：整桌就此打住——剩下的人都在等这位用户给的态度，再叫下一位没有意义 */
            if (after == PeerState.AWAIT_USER) {
                return spoken;
            }

            /* 被征询的沉默者开口了（而不是投结束票）：撤回所有人的结束提议，大家重新决策 */
            if (after == PeerState.ACTIVE && isClosing(topicId)) {
                machine.reopen(topicId);
                sink.topic(topicId, MaTopicStore.STATUS_OPEN);
                pushState(topicId, sink);
            }

            if (settle(topicId, sink)) {
                return spoken;
            }
            next = nextSpeaker(topicId, next.id(), strategy).orElse(null);
        }
        /* 到达上限仍未收束：留给下一句人话继续（话题保持进行中，不算失败） */
        return spoken;
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
        sink.topic(topicId, MaTopicStore.STATUS_CLOSING);
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
    private Optional<PeerRow> nextSpeaker(String topicId, String afterPeerId, SpeakerStrategy strategy) {
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
    public List<MultiAgentDtos.TopicView> topics(String sessionId) {
        return topics.topicsBySession(sessionId).stream().map(this::toTopicView).toList();
    }

    /** 某会话当前进行中的话题（页面据它高亮「讨论中」） */
    public Optional<MultiAgentDtos.TopicView> currentTopic(String sessionId) {
        return topics.openBySession(sessionId).map(this::toTopicView);
    }

    private MultiAgentDtos.TopicView toTopicView(TopicRow row) {
        Map<String, PeerState> states = machine.states(row.id());
        Map<String, String> names = new LinkedHashMap<>();
        /* 列本次会话的成员（含「非活跃」）：状态条要让人一眼看到这张桌子上都有谁、各自什么状态；
           只列已开口的人，第一句之前那一条就是空的 */
        for (PeerRow peer : directory.peers(row.sessionId())) {
            names.put(peer.name(), states.getOrDefault(peer.id(), PeerState.INACTIVE).name());
        }
        return new MultiAgentDtos.TopicView(row.id(), row.sessionId(), row.status(),
                row.floorPeer(), row.resolveRound(), names, machine.participants(row.id()),
                row.createdAt(), row.closedAt());
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
     * 把话题事件流里的跃迁补写成消息行（{@link MaMessageStore#ROLE_EVENT}）——圆桌的记录因此
     * 不只是「谁说了什么」，还有「谁在什么时候转沉默、投了结束票、被打断」。
     *
     * <p><b>为什么按水位线补写，而不是事件一落库就写</b>：状态工具（go_silent / propose_end /
     * interject）是模型在<b>发言中途</b>调用的，那一刻这位成员的发言行还没落库（发言行是整轮跑完
     * 才写的）。就地写会让记录变成「乙 转入沉默」排在「乙：……」之前，读不通。按
     * {@code ma_message.event_seq} 这条水位线、在发言行之后补写，先后顺序才对得上。
     *
     * <p>水位线存在行里（见 {@link MaMessageStore#maxEventSeq}），所以本方法随时可以重跑：
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
                    nameOf(e.peerId()), text);
            /* 顺手推给前端：状态行不必等整轮跑完回读才出现 */
            sink.action(text);
        }
    }

    /**
     * 一条跃迁的自述式文案。刻意不带「人名：」前缀：它不是某个人说的话，而是圆桌上发生的事，
     * 模型与人都该一眼看出这一行是「动作」，不是「发言」。
     */
    private String line(EventRow e) {
        if (MaTopicEventStore.KIND_TOPIC_OPEN.equals(e.kind())) {
            return "话题开始";
        }
        String who = nameOf(e.peerId());
        if (who == null || who.isBlank()) {
            return null;
        }
        return switch (e.kind()) {
            case MaTopicEventStore.KIND_ADDRESSED -> who + " 被点名";
            case MaTopicEventStore.KIND_JOINED -> who + " 入场";
            case MaTopicEventStore.KIND_INTERJECT -> who + " 举手抢答" + reason(e);
            case MaTopicEventStore.KIND_SILENT -> who + " 转入沉默" + reason(e);
            case MaTopicEventStore.KIND_REVIVED -> who + " 重新参与讨论";
            case MaTopicEventStore.KIND_PROPOSE_END -> who + " 提议结束" + reason(e);
            case MaTopicEventStore.KIND_PROMPT -> who + " 被要求立刻表态";
            /* 重开是逐人一条事件（每个此前提议结束的人各撤一条），所以按人写、不写成一件事 */
            case MaTopicEventStore.KIND_REOPEN -> who + " 撤回结束提议，重新决策";
            case MaTopicEventStore.KIND_ASK_USER -> who + " 请用户表态" + reason(e);
            case MaTopicEventStore.KIND_RESUME -> who + " 收到用户回应，继续";
            case MaTopicEventStore.KIND_INTERRUPT -> who + " 的发言被打断";
            case MaTopicEventStore.KIND_CLOSE -> who + " 退场";
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
                .map(t -> MaTopicStore.STATUS_CLOSING.equals(t.status()))
                .orElse(false);
    }

    private String statusOf(String topicId) {
        return topics.topic(topicId).map(TopicRow::status).orElse(MaTopicStore.STATUS_CLOSED);
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
