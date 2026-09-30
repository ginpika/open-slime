package cc.ginpika.minis.agent.multiagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.multiagent.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 随机策略：随机的东西不好靠肉眼看，所以这里钉的是它<b>不随机的性质</b>——
 * 候选范围（只在「活跃 / 待表态」里）、绝不自己接自己、以及确实在变。
 */
class RandomSpeakerStrategyTest {

    /** 掷骰子的次数：用来把「可能会变」钉成「确实会变」（200 次全落同一个，概率约 2^-199） */
    private static final int ROUNDS = 200;

    private final SpeakerStrategy strategy = new RandomSpeakerStrategy();

    private static PeerRow peer(String id) {
        return new PeerRow(id, "成员" + id, null, null, null, null, 0, null, null, null, 0L, 0L);
    }

    @Test
    void pickNextStaysInsideEligibleAndNeverFollowsItself() {
        List<PeerRow> peers = List.of(peer("a"), peer("b"), peer("c"), peer("d"));
        Map<String, PeerState> states = Map.of(
                "a", PeerState.ACTIVE,
                "b", PeerState.PROMPTED,
                "c", PeerState.SILENT,
                "d", PeerState.PROPOSE_END);

        /* 可发言的只有 a、b；刚说完的 a 本轮让开——于是每次都只能是 b，绝不轮到 c / d / a */
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ROUNDS; i++) {
            seen.add(strategy.pickNext("t", peers, states, "a").orElseThrow().id());
        }
        assertEquals(Set.of("b"), seen);
    }

    @Test
    void pickNextVariesOverTheWholeEligibleSet() {
        List<PeerRow> peers = List.of(peer("a"), peer("b"), peer("c"));
        Map<String, PeerState> states = Map.of(
                "a", PeerState.ACTIVE, "b", PeerState.ACTIVE, "c", PeerState.ACTIVE);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ROUNDS; i++) {
            seen.add(strategy.pickNext("t", peers, states, "a").orElseThrow().id());
        }
        assertEquals(Set.of("b", "c"), seen);
    }

    @Test
    void pickNextFallsBackToTheOnlySpeakerRatherThanStalling() {
        List<PeerRow> peers = List.of(peer("a"), peer("b"));
        Map<String, PeerState> states = Map.of("a", PeerState.ACTIVE, "b", PeerState.SILENT);

        /* 场上只剩刚说完的 a 可发言：退回它，而不是返回空把这桌卡住 */
        assertEquals("a", strategy.pickNext("t", peers, states, "a").orElseThrow().id());
    }

    @Test
    void pickNextIsEmptyWhenNobodyCanSpeak() {
        List<PeerRow> peers = List.of(peer("a"), peer("b"));
        Map<String, PeerState> states = Map.of("a", PeerState.SILENT, "b", PeerState.SILENT);

        assertTrue(strategy.pickNext("t", peers, states, "a").isEmpty());
    }

    @Test
    void pickOpenerCoversEverySeatAndHandlesAnEmptyTable() {
        List<PeerRow> peers = List.of(peer("a"), peer("b"), peer("c"));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ROUNDS; i++) {
            seen.add(strategy.pickOpener("s", "t", peers).orElseThrow().id());
        }
        assertEquals(Set.of("a", "b", "c"), seen);
        assertTrue(strategy.pickOpener("s", "t", List.of()).isEmpty());
    }
}
