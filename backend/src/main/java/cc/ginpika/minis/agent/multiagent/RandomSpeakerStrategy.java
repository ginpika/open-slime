package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.multiagent.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 随机：每次在可发言的人里掷一次骰子（{@link SpeakerStrategy} 的 {@code random} 实现）。
 *
 * <p>它和 {@link RotationSpeakerStrategy} 是同一类——都不看发言欲望，只决定顺序；差别只在
 * 「下一个是谁」是排好的还是掷出来的：
 * <ul>
 *   <li>轮询按名册座次依次交接，可复现、可复盘；</li>
 *   <li>随机没有座次，谁都可能接着开口，场面更像一桌真人抢话。</li>
 * </ul>
 *
 * <p><b>代价要认</b>：同样的输入复现不出同样的顺序，出问题时也没法按记录把「谁为什么被叫到」
 * 讲清楚——这正是本模块当初默认顺序轮转的原因（见 {@link RotationSpeakerStrategy}）。
 * 想要随机性又要留痕，就用 {@link DesireSpeakerStrategy}：那里每次叫谁都由成员自己报的欲望决定。
 *
 * <p>两条与轮询一致的护栏仍然保留：只在「活跃 / 待表态」里挑；刚说完的那位本级不接自己的话
 * （除非场上只剩它一个）。所以它不会把沉默者、交卷者叫起来，也不会让一个人连着说。
 */
@Component
public class RandomSpeakerStrategy implements SpeakerStrategy {

    /** 策略标识（配置值 {@code minis.multi-agent.speaker-strategy}） */
    public static final String KEY = "random";

    @Override
    public String key() {
        return KEY;
    }

    /** 开场者：在座的人里随机一位 */
    @Override
    public Optional<PeerRow> pickOpener(String sessionId, String topicId, List<PeerRow> peers) {
        return SpeakerStrategy.randomOf(peers);
    }

    /** 下一位：在「活跃 / 待表态」里随机挑一位，刚说完的那位本轮让开 */
    @Override
    public Optional<PeerRow> pickNext(String topicId, List<PeerRow> peers,
                                      Map<String, PeerState> states, String afterPeerId) {
        List<PeerRow> candidates = SpeakerStrategy.eligible(peers, states);
        return SpeakerStrategy.randomOf(SpeakerStrategy.excluding(candidates, afterPeerId));
    }
}
