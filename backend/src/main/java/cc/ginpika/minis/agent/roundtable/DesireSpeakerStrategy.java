package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 发言欲望优先队列：谁更想说话，谁先开口（{@link SpeakerStrategy} 的 {@code desire} 实现）。
 *
 * <p>规则就一句话：把在座的成员看成一个优先队列，欲望高的先出列。</p>
 * <ul>
 *   <li>每个人的初始欲望是 {@link #DEFAULT_DESIRE}（人人相等）；</li>
 *   <li>轮到自己发言，说完之后交出一个「下一轮发言欲望」（0~10）——由模型按自己的角色与场面判断
 *       （提示词里要求，见 {@code CoordinationBlock}），后端把它记进话题事件；</li>
 *   <li>于是它带着新欲望重新回到候选里，下一次仍从「欲望最高的队首」取人。</li>
 * </ul>
 *
 * <p>队列并不需要真的存在：每人最近一次报出的欲望存在事件流里（{@link TopicStateMachine#latestDesires}），
 * 「从队首取一位」在这里等价于「在可发言的人里挑欲望最高的一位」。这也意味着它天然可重启——
 * 进程没了，欲望仍在库里，下一句人话接着按同样的规则叫号。
 *
 * <p><b>首轮随机</b>：新话题里谁都还没报过欲望，人人都是默认值，平手时随机定序（见
 * {@link #highest}）。随机只在平手时出现，不是每一轮都掷骰子。
 *
 * <p><b>刚说完的那位本轮让开</b>：它报的是「下一轮」的欲望，不该立刻接自己的话；场上只剩它一个
 * 可发言时才允许自己接自己（否则整桌就卡住了）。
 */
@Component
public class DesireSpeakerStrategy implements SpeakerStrategy {

    /** 策略标识（配置值 {@code minis.roundtable.speaker-strategy}） */
    public static final String KEY = "desire";

    /** 欲望下界（模型报得更低就夹到这里） */
    public static final int MIN_DESIRE = 0;
    /** 欲望上界（模型报得更高就夹到这里） */
    public static final int MAX_DESIRE = 10;
    /**
     * 没报过欲望时的默认值：<b>取中位，不是上界</b>。
     *
     * <p>漏报（模型没按信封约定输出）是常态，这个值决定了「没表态」在队里的位置——
     * 取上界等于把「没表态」当成「最想说话」，它会排在真报了 9 分的人前面；
     * 取中位则落在「想说的」之后、「明确不想说的」之前。首轮人人相等，于是仍由随机定序。
     */
    public static final int DEFAULT_DESIRE = 5;

    private final TopicStateMachine machine;

    public DesireSpeakerStrategy(TopicStateMachine machine) {
        this.machine = machine;
    }

    /** 把模型报出的欲望收进 0~10：越界即夹紧，不让一个 999 的欲望把整桌的定序带偏 */
    public static int clamp(int value) {
        return Math.max(MIN_DESIRE, Math.min(MAX_DESIRE, value));
    }

    @Override
    public String key() {
        return KEY;
    }

    /** 开场者：本次话题里欲望最高的一位；都没报过则人人平手，随机定一位 */
    @Override
    public Optional<AgentRow> pickOpener(String sessionId, String topicId, List<AgentRow> peers) {
        return SpeakerStrategy.randomOf(topByDesire(peers, machine.latestDesires(topicId)));
    }

    /**
     * 下一位：在「活跃 / 待表态」里挑欲望最高的一位——等价于从优先队列队首取人。
     *
     * <p>刚说完的那位先排除在外（除非场上只剩它一个可发言），避免它立刻接自己的话。
     */
    @Override
    public Optional<AgentRow> pickNext(String topicId, List<AgentRow> peers,
                                      Map<String, PeerState> states, String afterPeerId) {
        List<AgentRow> pool = SpeakerStrategy.excluding(
                SpeakerStrategy.eligible(peers, states), afterPeerId);
        return SpeakerStrategy.randomOf(topByDesire(pool, machine.latestDesires(topicId)));
    }

    /** 欲望最高的那几位（同欲望的都收进来，由调用方随机挑一个——随机性只在平手时出现） */
    private static List<AgentRow> topByDesire(List<AgentRow> candidates, Map<String, Integer> desires) {
        int max = Integer.MIN_VALUE;
        List<AgentRow> top = new ArrayList<>();
        for (AgentRow peer : candidates) {
            int desire = desires.getOrDefault(peer.id(), DEFAULT_DESIRE);
            if (desire > max) {
                max = desire;
                top.clear();
                top.add(peer);
            } else if (desire == max) {
                top.add(peer);
            }
        }
        return top;
    }
}
