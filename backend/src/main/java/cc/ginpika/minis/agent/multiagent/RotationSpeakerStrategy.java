package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.multiagent.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import cc.ginpika.minis.agent.multiagent.store.MaTopicStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 顺序轮转：按名册顺序把话头依次交给下一位（{@link SpeakerStrategy} 的 {@code rotation} 实现）。
 *
 * <p>这是圆桌最初的选人规则，也是「默认的固定顺序轮转也是一种应答者策略」里的那一种。它被完整
 * 保留下来，理由有两条：一是可复盘——顺序由名册与事件流共同决定，同样的输入复现得出同样的场面，
 * 出问题时能按记录把「谁为什么被叫到」讲清楚；二是可切换——想让圆桌更像开会而不是抢话，
 * 把 {@code minis.multi-agent.speaker-strategy} 拨回 {@code rotation} 即可。
 *
 * <p>被点到的人是谁，每次都写进话题事件（{@code floor_granted}），事后可回放。
 */
@Component
public class RotationSpeakerStrategy implements SpeakerStrategy {

    /** 策略标识（配置值 {@code minis.multi-agent.speaker-strategy}） */
    public static final String KEY = "rotation";

    private final MaTopicStore topics;

    public RotationSpeakerStrategy(MaTopicStore topics) {
        this.topics = topics;
    }

    @Override
    public String key() {
        return KEY;
    }

    /**
     * 开场者：接着本会话上一轮的开场者往后顺延一位。
     *
     * <p>用「上一话题的发起者」当游标，不另外存轮转状态：重启后它仍然正确，
     * 也不会因为会话里插进别的动作就错位。
     */
    @Override
    public Optional<PeerRow> pickOpener(String sessionId, String topicId, List<PeerRow> peers) {
        if (peers.isEmpty()) {
            return Optional.empty();
        }
        String lastOpener = topics.latestBySession(sessionId)
                .map(MaTopicStore.TopicRow::originPeer)
                .orElse(null);
        int index = 0;
        if (lastOpener != null) {
            for (int i = 0; i < peers.size(); i++) {
                if (peers.get(i).id().equals(lastOpener)) {
                    index = i + 1;
                    break;
                }
            }
        }
        return Optional.of(peers.get(index % peers.size()));
    }

    /** 下一位：从刚说完的那位之后按名册顺序找第一位「活跃 / 待表态」的人 */
    @Override
    public Optional<PeerRow> pickNext(String topicId, List<PeerRow> peers,
                                      Map<String, PeerState> states, String afterPeerId) {
        if (peers.isEmpty()) {
            return Optional.empty();
        }
        int start = 0;
        if (afterPeerId != null) {
            for (int i = 0; i < peers.size(); i++) {
                if (peers.get(i).id().equals(afterPeerId)) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int step = 0; step < peers.size(); step++) {
            PeerRow peer = peers.get((start + step) % peers.size());
            PeerState state = states.getOrDefault(peer.id(), PeerState.INACTIVE);
            if (state == PeerState.ACTIVE || state == PeerState.PROMPTED) {
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }
}
