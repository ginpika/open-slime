package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicEventStore.EventRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 话题状态机：信号进来，事件落库，状态折叠出去。
 *
 * <p>这是整个多智能体模块唯一的裁决者——成员的状态只能经由本类的跃迁方法改变，
 * 别处一律不许直接写状态。两个原则：
 * <ul>
 *   <li><b>事件是正本，状态是投影</b>——{@link #states(String)} 把 {@code roundtable_topic_event}
 *       按 seq 折叠出「每个 actor 此刻的状态」，不存任何状态列。于是不存在两处状态打架的可能，
 *       重启后也不会有「幻影状态」（那些后台线程本来也没了，而事件流本来就代表发生过的历史）。</li>
 *   <li><b>每次跃迁都写 from → to</b>——留痕自带解释力：页面上直接看得出「谁从什么变成了什么」。</li>
 * </ul>
 *
 * <p><b>谁是「沉默者」</b>：本话题中登场过、且当前状态为 {@link PeerState#SILENT} 的成员。
 * 「登场过」= 在本话题事件里出现过（被点名、被授予发言权、发过言、表过态）。
 * 名册里从头到尾没露过面的成员不算参与者，不参与「全体提议结束」的判定——
 * 否则名册一长，话题就永远收不了场。
 */
@Component
public class TopicStateMachine {

    /** 成员在某个话题下的状态（默认 {@link #INACTIVE}：不在这个话题里） */
    public enum PeerState {

        /** 非活跃：不在这个话题里（话题未开始，或已经收束） */
        INACTIVE("非活跃"),
        /** 活跃：已入场、在听、可被点名 */
        ACTIVE("活跃"),
        /** 发言中：持有发言权 */
        SPEAKING("发言中"),
        /** 沉默：继续听，但不打算发言 */
        SILENT("沉默"),
        /** 待表态：被征询后必须在「发言」与「提议结束」之间二选一 */
        PROMPTED("待表态"),
        /** 等待用户：请求了用户的态度/许可，全桌暂停中（人一发言它就回到活跃） */
        AWAIT_USER("等待用户"),
        /** 提议结束：已出结论或不再参与（全体都到这里时话题关闭） */
        PROPOSE_END("提议结束");

        private final String label;

        PeerState(String label) {
            this.label = label;
        }

        /** 中文标签（前端与状态块展示用） */
        public String label() {
            return label;
        }

        /** 是否算「已经表态可以结束」 */
        public boolean proposedEnd() {
            return this == PROPOSE_END;
        }
    }

    private final RoundtableTopicStore topics;
    private final RoundtableTopicEventStore events;

    public TopicStateMachine(RoundtableTopicStore topics, RoundtableTopicEventStore events) {
        this.topics = topics;
        this.events = events;
    }

    // ---------- 开与关 ----------

    /**
     * 开一个话题：由某人的一条发言引发。这里只落话题本身与「话题开始」事件，
     * 谁在座交给 {@link #seatAll}（名册上的人都该到场）。
     *
     * @param origin      {@link RoundtableTopicStore#ORIGIN_USER} 或 {@link RoundtableTopicStore#ORIGIN_PEER}
     * @param originPeer  发起人（origin=peer 时）
     */
    public TopicRow open(String sessionId, String origin, String originPeer) {
        String id = UUID.randomUUID().toString();
        topics.insert(new TopicRow(id, sessionId, origin, originPeer, RoundtableTopicStore.STATUS_OPEN,
                null, 0, System.currentTimeMillis(), null));
        events.append(id, actorOf(origin, originPeer), RoundtableTopicEventStore.KIND_TOPIC_OPEN,
                PeerState.INACTIVE.name(), null, originPeer, null, null);
        return topics.topic(id).orElseThrow();
    }

    /**
     * 全体在座：把名册上的人置为「活跃」（已入场、在听、可被点名）。
     *
     * <p><b>为什么必须全员在座</b>：选下一位只在「活跃 / 待表态」里挑，没入场的成员状态是
     * 「非活跃」，永远轮不到——只让开场者一个人入场，结果就是他把整场话说完（踩过这个坑）。
     * 圆桌的语义本来就是「坐着的人都在听」，所以开场即全员在座。
     *
     * <p>只对此刻「非活跃」的人写入场事件：续话题时重复调用是幂等的，也不会把事件表刷满。
     */
    public void seatAll(String topicId, List<String> peerIds) {
        if (peerIds == null || peerIds.isEmpty()) {
            return;
        }
        Map<String, PeerState> states = states(topicId);
        for (String peerId : peerIds) {
            if (states.getOrDefault(peerId, PeerState.INACTIVE) == PeerState.INACTIVE) {
                move(topicId, peerId, RoundtableTopicEventStore.KIND_JOINED, PeerState.ACTIVE, null, null);
            }
        }
    }

    /** 让某位成员进场（置为活跃） */
    public void join(String topicId, String peerId, String kind, String reason) {
        move(topicId, peerId, kind, PeerState.ACTIVE, reason, null);
    }

    /** 授予发言权：置为发言中，并把话题的 floor 交给它 */
    public void grantFloor(String topicId, String peerId) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_FLOOR_GRANTED, PeerState.SPEAKING, null, null);
        topics.setFloor(topicId, peerId);
    }

    /**
     * 记一次发言（内容与消息 id）。这是「谁说了什么」的留痕，<b>不改变状态</b>——from/to 都留空。
     *
     * <p>状态留空是必需的，不是省略：这条事件是在整轮跑完之后才追加的，而这位成员的表态
     * （propose_end / go_silent）是它在<b>发言中途</b>调用工具时落下的。若这里顺手把状态写成
     * SPEAKING，那条表态会被就地冲掉——状态按事件序折叠取最后一条，于是「提议结束」刚记下就没了，
     * 「全体提议结束」的判定永远凑不齐，话题再也收不了场，只能一路跑到发言次数上限。
     */
    public void speech(String topicId, String peerId, String content, Long messageId) {
        events.append(topicId, peerId, RoundtableTopicEventStore.KIND_SPEECH, null, null, null, content,
                messageId);
    }

    /**
     * 记一次「下一轮发言欲望」（0~10）：选人策略据此定序（见 {@link DesireSpeakerStrategy}）。
     *
     * <p>与 {@link #speech} 一样<b>不改变状态</b>（from/to 都留空）：它是「我下一轮还想不想说」的
     * 表态，不是「我此刻处在哪」的跃迁——顺手写状态会把成员发言中途落下的 propose_end / go_silent
     * 冲掉（那正是 speech 踩过的坑，见其注释）。
     */
    public void desire(String topicId, String peerId, int value) {
        events.append(topicId, peerId, RoundtableTopicEventStore.KIND_DESIRE, null, null, null,
                Integer.toString(value), null);
    }

    /**
     * 说完让出：发言权交回，成员落到「活跃」（还想听）或「沉默」（这一轮不再开口）。
     * 打断走 {@link #interrupt}，那会额外记一条 interrupt。
     */
    public void releaseFloor(String topicId, String peerId, PeerState next, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_FLOOR_RELEASED, next, reason, null);
        topics.setFloor(topicId, null);
    }

    /** 打断：记下「谁被打断了」，被打断者回到活跃（它的话就停在那里了） */
    public void interrupt(String topicId, String actor, String victimPeerId, String reason) {
        events.append(topicId, actor, RoundtableTopicEventStore.KIND_INTERRUPT, PeerState.SPEAKING.name(),
                PeerState.ACTIVE.name(), victimPeerId, reason, null);
        topics.setFloor(topicId, null);
    }

    /**
     * 抢话（举手）：声明「我打算发言」。
     *
     * <p>为什么要举手而不是直接打断：同一时刻圆桌上只有一个成员在跑（同步回合制），
     * 别人不可能在某位发言的中途插进它的流里——真正能中断一次流的是人（新消息 / 停止）。
     * 所以成员之间的「抢答」落成一次举手：记进事件流让大家看得见，下一位优先交给它。
     */
    public void interject(String topicId, String peerId, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_INTERJECT, PeerState.ACTIVE, reason, null);
    }

    /** 当前举着手的人：最后一次举手、且还没轮到它说话的那位；没人举手返回空 */
    public Optional<String> handRaiser(String topicId) {
        String hand = null;
        for (EventRow e : events.byTopic(topicId)) {
            if (RoundtableTopicEventStore.KIND_INTERJECT.equals(e.kind())) {
                hand = e.actor();
            } else if (RoundtableTopicEventStore.KIND_SPEECH.equals(e.kind())
                    && e.actor().equals(hand)) {
                hand = null;
            }
        }
        return Optional.ofNullable(hand);
    }

    /** 只清发言权位（成员已自行表态、状态已经落定，不必再补一条「说完让出」事件） */
    public void clearFloor(String topicId) {
        topics.setFloor(topicId, null);
    }

    /** 转入沉默 */
    public void silent(String topicId, String peerId, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_SILENT, PeerState.SILENT, reason, null);
    }

    /**
     * 新一轮开始：把本话题里转入沉默的成员放回活跃（人的每一句新话都开启新的一轮）。
     *
     * <p>沉默管的是「<b>这一轮</b>我不说」，不是永远闭嘴（见 {@link PeerState#SILENT} 与局面里的
     * 交代）——所以人一开口，上一轮说「不说了」的人重新回到可被叫到的行列。
     *
     * <p><b>不做这件事，这张桌子会僵住</b>：选人只在「活跃 / 待表态」里挑（见
     * {@link SpeakerStrategy#eligible}），全员沉默之后谁也挑不到；而终止判定的两个入口
     * （{@link #allProposedEnd} / {@link #allNonSilentProposedEnd}）在场全为沉默时都不成立，
     * 于是话题既收不了场、也再没人开口——人再怎么说话都不会有应答。
     *
     * @return 被放回活跃的人数
     */
    public int reviveSilent(String topicId) {
        int revived = 0;
        for (Map.Entry<String, PeerState> entry : states(topicId).entrySet()) {
            if (entry.getValue() == PeerState.SILENT) {
                move(topicId, entry.getKey(), RoundtableTopicEventStore.KIND_REVIVED, PeerState.ACTIVE,
                        "新一轮开始，重新参与", null);
                revived++;
            }
        }
        return revived;
    }

    /**
     * 重入：人的一句新话开启新一轮，把本话题里<b>任何非活跃参与者</b>都复位为活跃，
     * 于是「无论上一轮停在哪个阶段，新一轮都一定能继续」。
     *
     * <p>为什么必须比 {@link #reviveSilent} 更彻底：选人只在「活跃 / 待表态」里挑
     * （见 {@link SpeakerStrategy#eligible}），而人插话时上一轮可能正停在
     * {@link PeerState#PROPOSE_END}（全员投了收场票）、{@link PeerState#PROMPTED}（被征询中）
     * 或 {@link PeerState#AWAIT_USER}（等用户表态）。这些状态不放开，人的新话就<b>找不到任何发言人</b>
     * ——推进链原地停摆，用户再怎么发也无人应答（踩过这个坑）。
     *
     * <p>刻意不动「重开计数」（{@code resolve_round}）：那是「沉默者发言 → 全员重新决策」那条
     * 回路的护栏，与人的新话无关。同时清掉发言权位、把征询 / 等待中的话题拉回进行中。
     *
     * @return 被复位的人数
     */
    public int reenter(String topicId) {
        int revived = 0;
        for (Map.Entry<String, PeerState> entry : states(topicId).entrySet()) {
            if (entry.getValue() != PeerState.ACTIVE) {
                move(topicId, entry.getKey(), RoundtableTopicEventStore.KIND_REENTER, PeerState.ACTIVE,
                        "人的新话开启新一轮，重新参与", null);
                revived++;
            }
        }
        topics.setFloor(topicId, null);
        topics.setStatus(topicId, RoundtableTopicStore.STATUS_OPEN);
        return revived;
    }

    /**
     * 新一轮开始的水位线：人的一句新话开启新的一轮，上一轮报出的发言欲望到此作废
     * （见 {@link #latestDesires}）。
     *
     * <p>只写一条<b>不带跃迁</b>的事件（from/to 都空），不改变任何成员的状态——把沉默者放回活跃
     * 是 {@link #reviveSilent} 的事，两件事分开，免得一个方法改两组语义。人对话题开始事件、
     * 以及状态查询都是透明的（{@link #participants} 跳过人发的事件，{@link #states} 只看 toState）。
     */
    public void turnStart(String topicId) {
        events.append(topicId, RoundtableTopicEventStore.ACTOR_USER,
                RoundtableTopicEventStore.KIND_TURN_START, null, null, null, null, null);
    }

    /**
     * 本轮结束：记一条客观清单（这一轮谁发了言、是不是被发言次数上限打断）。
     *
     * <p>它是为「它为什么没说话」留的第一手材料，但<b>只记事实、不写推断</b>——不发言的原因由各成员
     * 此刻的状态给出（{@link PeerState#SILENT} = 自己不说、{@link PeerState#PROPOSE_END} = 投了结束票、
     * {@link PeerState#ACTIVE} = 没轮到）。把猜测写成事实，比不记更糟。
     *
     * @param summary 人类可读的一句清单（由推进方按本轮实际发生的事拼出）
     */
    public void turnEnd(String topicId, String summary) {
        events.append(topicId, RoundtableTopicEventStore.ACTOR_USER,
                RoundtableTopicEventStore.KIND_TURN_END, null, null, null, summary, null);
    }

    /** 提议结束（投一票） */
    public void proposeEnd(String topicId, String peerId, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_PROPOSE_END, PeerState.PROPOSE_END, reason, null);
    }

    /** 向沉默者征询：要求它立刻在「发言」与「提议结束」之间二选一 */
    public void prompt(String topicId, String peerId, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_PROMPT, PeerState.PROMPTED, reason, null);
    }

    /**
     * 请求用户表态（{@code ask_user}）：把决定权交回给人——全桌暂停、话题置 waiting，等用户发言。
     *
     * <p>它和 propose_end / go_silent / interject 不是一类：那三条都是成员内部的事，换个状态接着叫
     * 下一位就行；这一条要停下整条推进链（见 {@code RoundtableTopicService#advance}），因为再往下推也推不动
     * ——剩下的人都在等这位用户给的态度。
     */
    public void askUser(String topicId, String peerId, String reason) {
        move(topicId, peerId, RoundtableTopicEventStore.KIND_ASK_USER, PeerState.AWAIT_USER, reason, null);
        topics.setStatus(topicId, RoundtableTopicStore.STATUS_WAITING);
    }

    /**
     * 用户已回应：等待中的成员回到活跃，话题恢复进行。
     *
     * <p>没人在等就什么都不做——不能无条件置 open，那会把已经收束的话题又打开。
     */
    public void resume(String topicId) {
        Optional<String> waiting = awaitingUser(topicId);
        if (waiting.isEmpty()) {
            return;
        }
        move(topicId, waiting.get(), RoundtableTopicEventStore.KIND_RESUME, PeerState.ACTIVE,
                "用户已回应", null);
        topics.setStatus(topicId, RoundtableTopicStore.STATUS_OPEN);
    }

    /**
     * 重开：某位沉默者开口了，所有「提议结束」的成员撤回为「活跃」重新决策
     * （它们刚才的结论建立在信息不全之上）。同时把重开次数 +1。
     *
     * @return 重开后的累计次数
     */
    public int reopen(String topicId) {
        for (Map.Entry<String, PeerState> e : states(topicId).entrySet()) {
            if (e.getValue().proposedEnd()) {
                move(topicId, e.getKey(), RoundtableTopicEventStore.KIND_REOPEN, PeerState.ACTIVE,
                        "有人重新发言，撤回结束提议", null);
            }
        }
        topics.setStatus(topicId, RoundtableTopicStore.STATUS_OPEN);
        return topics.incResolveRound(topicId);
    }

    /** 收束：全体参与者回到非活跃，话题置 closed（幂等） */
    public void close(String topicId) {
        for (String peerId : participants(topicId)) {
            move(topicId, peerId, RoundtableTopicEventStore.KIND_CLOSE, PeerState.INACTIVE, null, null);
        }
        topics.close(topicId);
    }

    /** 话题进入「向沉默者征询」阶段 */
    public void markClosing(String topicId) {
        topics.setStatus(topicId, RoundtableTopicStore.STATUS_CLOSING);
    }

    // ---------- 查询（全部由事件折叠而来） ----------

    /** 每个登场过的成员此刻的状态（键为成员 id） */
    public Map<String, PeerState> states(String topicId) {
        Map<String, PeerState> states = new LinkedHashMap<>();
        for (EventRow e : events.byTopic(topicId)) {
            String to = e.toState();
            if (to != null && !to.isBlank()) {
                states.put(e.actor(), PeerState.valueOf(to));
            }
        }
        return states;
    }

    /** 某成员此刻的状态（没登场过 = 非活跃） */
    public PeerState stateOf(String topicId, String peerId) {
        return states(topicId).getOrDefault(peerId, PeerState.INACTIVE);
    }

    /**
     * 本话题登场过的成员（按首次出现顺序）。
     *
     * <p>「话题开始」那条事件不算数：它的 actor 只是「谁起的头」，此时该成员还没有任何状态
     * （状态为零、却被当成参与者，会让「全体提议结束」永远凑不齐）。人也从来不是参与者。
     */
    public List<String> participants(String topicId) {
        List<String> seen = new ArrayList<>();
        for (EventRow e : events.byTopic(topicId)) {
            if (RoundtableTopicEventStore.KIND_TOPIC_OPEN.equals(e.kind())
                    || RoundtableTopicEventStore.ACTOR_USER.equals(e.actor())) {
                continue;
            }
            if (!seen.contains(e.actor())) {
                seen.add(e.actor());
            }
        }
        return seen;
    }

    /** 本话题当前处于沉默的成员（「沉默者」——征询的对象） */
    public List<String> silentPeers(String topicId) {
        Map<String, PeerState> states = states(topicId);
        List<String> silent = new ArrayList<>();
        for (String peerId : participants(topicId)) {
            if (states.getOrDefault(peerId, PeerState.INACTIVE) == PeerState.SILENT) {
                silent.add(peerId);
            }
        }
        return silent;
    }

    /**
     * 本话题此刻在等用户表态的那一位（若有）。
     *
     * <p>按事件序取「最后一位转到等待用户、且此后没有别的状态」的人。理论上至多一位：
     * 一有人请求，整条推进链就停了，不会再有人被叫起来请求。恢复时先让它接着答。
     */
    public Optional<String> awaitingUser(String topicId) {
        String waiting = null;
        for (EventRow e : events.byTopic(topicId)) {
            String to = e.toState();
            if (to == null || to.isBlank()) {
                continue;
            }
            if (PeerState.AWAIT_USER.name().equals(to)) {
                waiting = e.actor();
            } else if (e.actor() != null && e.actor().equals(waiting)) {
                /* 它不再是「等待用户」了（已被恢复，或又有了别的状态） */
                waiting = null;
            }
        }
        return Optional.ofNullable(waiting);
    }

    /**
     * 是否「除沉默者外都提议结束」：参与者里除沉默者之外的每一位都已提议结束，且至少有一位。
     *
     * <p>这是进入征询阶段的触发条件。注意「活跃 / 发言中 / 待表态」都不算——还在说话、
     * 或还没表态的人不能替他们收场。
     */
    public boolean allNonSilentProposedEnd(String topicId) {
        Map<String, PeerState> states = states(topicId);
        int cast = 0;
        for (String peerId : participants(topicId)) {
            PeerState state = states.getOrDefault(peerId, PeerState.INACTIVE);
            if (state == PeerState.SILENT) {
                continue;
            }
            if (!state.proposedEnd()) {
                return false;
            }
            cast++;
        }
        return cast > 0;
    }

    /** 是否全体参与者都已提议结束（征询之后达成的最终共识） */
    public boolean allProposedEnd(String topicId) {
        Map<String, PeerState> states = states(topicId);
        boolean any = false;
        for (String peerId : participants(topicId)) {
            any = true;
            if (!states.getOrDefault(peerId, PeerState.INACTIVE).proposedEnd()) {
                return false;
            }
        }
        return any;
    }

    /** 某话题的事件流（观测台回放用，按 seq 升序） */
    public List<EventRow> timeline(String topicId) {
        return events.byTopic(topicId);
    }

    /** 最近一位发过言的成员（接着往下轮转的游标；还没人说过返回空） */
    public Optional<String> lastSpeaker(String topicId) {
        String last = null;
        for (EventRow e : events.byTopic(topicId)) {
            if (RoundtableTopicEventStore.KIND_SPEECH.equals(e.kind())) {
                last = e.actor();
            }
        }
        return Optional.ofNullable(last);
    }

    /**
     * 每位成员本轮报出的「下一轮发言欲望」（没报过的成员不在表里，取用时按默认值兜底）。
     *
     * <p>按事件序折叠，后报的盖过先报的——这正是「说完带着新欲望重新入队」在事件流里的样子。
     * 坏数据（解析不出数字）当作没报过：不让一条脏事件把整桌的定序卡住。
     *
     * <p><b>欲望的有效期只有一轮</b>：只折叠最后一次 {@link RoundtableTopicEventStore#KIND_TURN_START}
     * 之后的事件（水位线由 {@link #turnStart} 写）。不这样做，上一轮报过高分、这一轮漏报的人会
     * 继续以旧高分排在队首——「没表态」于是被当成了「最想说话」。还没有水位线时（老话题 / 首轮）
     * 从话题开头算起。
     */
    public Map<String, Integer> latestDesires(String topicId) {
        Map<String, Integer> desires = new LinkedHashMap<>();
        if (topicId == null || topicId.isBlank()) {
            return desires;
        }
        List<EventRow> timeline = events.byTopic(topicId);
        int fromSeq = 0;
        for (EventRow e : timeline) {
            if (RoundtableTopicEventStore.KIND_TURN_START.equals(e.kind())) {
                fromSeq = e.seq();
            }
        }
        for (EventRow e : timeline) {
            if (e.seq() <= fromSeq
                    || !RoundtableTopicEventStore.KIND_DESIRE.equals(e.kind()) || e.content() == null) {
                continue;
            }
            try {
                desires.put(e.actor(), Integer.parseInt(e.content().strip()));
            } catch (NumberFormatException ignored) {
                /* 坏数据当没报过 */
            }
        }
        return desires;
    }

    // ---------- 私有 ----------

    /** 一次跃迁：先取当前态作 from，再写事件 */
    private void move(String topicId, String peerId, String kind, PeerState to, String reason,
                      Long messageId) {
        PeerState from = stateOf(topicId, peerId);
        events.append(topicId, peerId, kind, from.name(), to.name(), peerId, reason, messageId);
    }

    /** 话题开始事件的 actor：人开的话题归人，成员开的话题归它自己 */
    private static String actorOf(String origin, String originPeer) {
        return RoundtableTopicStore.ORIGIN_PEER.equals(origin) && originPeer != null
                ? originPeer : RoundtableTopicEventStore.ACTOR_USER;
    }
}
