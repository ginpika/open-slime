package cc.ginpika.minis.agent.multiagent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cc.ginpika.minis.config.MultiAgentProps;
import org.junit.jupiter.api.Test;

/**
 * 发言节流的时间账：字数 ÷ 速度，再封顶。
 *
 * <p>这里钉的是「等多久」这笔账本身（纯计算，好断言）；真的 sleep 那一步由 {@code pace} 负责，
 * 只验证它如实返回算出来的毫秒数，不断言真实耗时（那是时序测试，会飘）。
 */
class SpeechPacerTest {

    private static SpeechPacer pacer(int charsPerSecond, long maxDelayMillis) {
        MultiAgentProps props = new MultiAgentProps();
        props.setSpeechCharsPerSecond(charsPerSecond);
        props.setSpeechMaxDelayMillis(maxDelayMillis);
        return new SpeechPacer(props);
    }

    @Test
    void delayIsProportionalToLength() {
        SpeechPacer pacer = pacer(40, 60_000L);
        assertEquals(2_500L, pacer.delayMillis("字".repeat(100))); // 100 字 ÷ 40 字/秒
        assertEquals(25L, pacer.delayMillis("字"));
    }

    @Test
    void delayIsCappedSoLongSpeechesDoNotStallTheTurn() {
        /* 400 字本该等 10 秒：封顶到 3 秒，长发言不至于把整轮拖住 */
        assertEquals(3_000L, pacer(40, 3_000L).delayMillis("字".repeat(400)));
    }

    @Test
    void zeroOrNegativeSpeedDisablesPacing() {
        assertEquals(0L, pacer(0, 3_000L).delayMillis("字".repeat(100)));
        assertEquals(0L, pacer(-1, 3_000L).delayMillis("字".repeat(100)));
    }

    @Test
    void zeroCapMeansNoWaitAtAll() {
        assertEquals(0L, pacer(40, 0L).delayMillis("字".repeat(100)));
    }

    @Test
    void blankTextIsNeverDelayed() {
        SpeechPacer pacer = pacer(40, 3_000L);
        assertEquals(0L, pacer.delayMillis(null));
        assertEquals(0L, pacer.delayMillis(""));
        assertEquals(0L, pacer.delayMillis("   "));
    }

    @Test
    void paceReportsTheDelayItWaited() {
        /* 1000 字/秒 → 100 字等 100 毫秒 */
        assertEquals(100L, pacer(1000, 3_000L).pace("字".repeat(100)));
        assertEquals(0L, pacer(0, 3_000L).pace("字".repeat(100)));
    }
}
