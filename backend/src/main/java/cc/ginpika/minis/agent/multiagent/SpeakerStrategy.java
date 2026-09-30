package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.multiagent.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 应答者策略：圆桌上「这一轮该谁说话」的裁决规则。
 *
 * <p>选人从「怎么选」里拆出来，是因为圆桌不止一种选法：真人围坐时既有人按座次轮流开口，
 * 也有人凭「我这会儿特别想说」抢话。两者都对，只是场合不同，所以做成可替换的策略——
 * 由 {@code minis.multi-agent.speaker-strategy} 选定，{@link SpeakerPicker} 负责入口与点名，
 * 具体排序交给当前生效的这一位。
 *
 * <p>三个实现（都由 {@code minis.multi-agent.speaker-strategy} 给缺省、由每次发言的请求逐次覆盖）：
 * <ul>
 *   <li>{@link RotationSpeakerStrategy}（{@code rotation}，缺省）——固定顺序轮转：按名册顺序把话头
 *       依次交给下一位。可复现、可复盘：同样的输入复现得出同样的场面，出问题时也能按记录复盘。</li>
 *   <li>{@link RandomSpeakerStrategy}（{@code random}）——随机：每次在可发言的人里掷一次骰子。
 *       没有座次，场面更像真人抢话，代价是同样的输入复现不出同样的顺序。</li>
 *   <li>{@link DesireSpeakerStrategy}（{@code desire}）——发言欲望优先队列：每位成员每次说完都报
 *       一个「下一轮发言欲望」（0~10），欲望高的先开口；这是把「谁更想说话」交给成员自己定。</li>
 * </ul>
 *
 * <p>策略只决定「谁」，不改变任何状态：授予发言权、状态跃迁仍归 {@link TopicStateMachine}，
 * 抢答（interject）的优先级也仍在 {@code MaTopicService#nextSpeaker} 里先于策略生效。
 */
public interface SpeakerStrategy {

    /** 策略标识（与配置值对应） */
    String key();

    /**
     * 无指向发言时定一位开场者（新话题、还没人说过话时用）。
     *
     * @param sessionId 会话 id（顺序轮转用它找回上一次的开场者当游标）
     * @param topicId   话题 id（欲望优先用它读本次话题已收集的欲望）
     * @param peers     这张桌子上坐着的成员（名册顺序）
     */
    Optional<PeerRow> pickOpener(String sessionId, String topicId, List<PeerRow> peers);

    /**
     * 某位说完之后，下一位该谁。
     *
     * @param topicId     话题 id（欲望优先据此取本次话题的欲望）
     * @param peers       这张桌子上坐着的成员（名册顺序）
     * @param states      每位成员此刻的状态（由 {@link TopicStateMachine} 折叠而来）
     * @param afterPeerId 刚说完的那位（策略据此避免自己接自己）
     */
    Optional<PeerRow> pickNext(String topicId, List<PeerRow> peers,
                               Map<String, PeerState> states, String afterPeerId);

    /**
     * 可被叫到的成员：活跃 / 待表态。
     *
     * <p>只有这两类会被选中——沉默者明确表示这一轮不开口，提议结束者已经交卷，非活跃者还没入场，
     * 三者都不该被点名；需要沉默者开口时走征询（见 {@code MaTopicService#settle}）。
     */
    static List<PeerRow> eligible(List<PeerRow> peers, Map<String, PeerState> states) {
        return peers.stream().filter(peer -> {
            PeerState state = states.getOrDefault(peer.id(), PeerState.INACTIVE);
            return state == PeerState.ACTIVE || state == PeerState.PROMPTED;
        }).toList();
    }

    /**
     * 剔除刚说完的那位：它这一句已经说完了，不该立刻接自己的话。
     *
     * <p>剔完为空（场上只剩它一个可发言）时退回原集合——否则整桌就卡住了。
     */
    static List<PeerRow> excluding(List<PeerRow> candidates, String excludeId) {
        if (excludeId == null) {
            return candidates;
        }
        List<PeerRow> rest = candidates.stream()
                .filter(peer -> !peer.id().equals(excludeId)).toList();
        return rest.isEmpty() ? candidates : rest;
    }

    /** 从候选里随机取一位；空集合返回空 */
    static Optional<PeerRow> randomOf(List<PeerRow> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }
}
