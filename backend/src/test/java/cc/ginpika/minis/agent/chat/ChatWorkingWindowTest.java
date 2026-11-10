package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * R5 工作窗口的历史正文预算：小窗口按下限（8192）兜底，大窗口按「窗口 / 32」放宽，
 * 两者都不超过可用输入预算的占比上限——避免固定 8K 在百万级窗口上只占 0.8%。
 */
class ChatWorkingWindowTest {

    private static final int SHARE = 25;
    private static final long FLOOR = 8192;
    private static final long RESERVE = 8192;

    @Test
    void smallWindowsKeepThePilotFloorButRespectTheShareCeiling() {
        // 128K：窗口/32 = 4K 低于下限，按 8K 兜底；占比 29K 不构成约束
        assertEquals(8192, ChatContextService.historyTokenCap(131_072, RESERVE, SHARE, FLOOR));
        // 32K：占比 5.7K 比下限更紧，历史预算不得挤占当前输入
        assertEquals(5_734, ChatContextService.historyTokenCap(32_768, RESERVE, SHARE, FLOOR));
        // 16K 小窗口：占比优先，绝不超预算
        assertEquals(1_792, ChatContextService.historyTokenCap(16_384, RESERVE, SHARE, FLOOR));
    }

    @Test
    void millionTokenWindowScalesWithTheWindowInsteadOfStayingAtEightThousand() {
        // 1M：窗口/32 ≈ 31K，远大于下限，仍低于 25% 的上限
        assertEquals(31_250, ChatContextService.historyTokenCap(1_000_000, RESERVE, SHARE, FLOOR));
    }

    @Test
    void raisingTheFloorLiftsEveryWindowWithoutBreakingTheShareCeiling() {
        assertEquals(65_536, ChatContextService.historyTokenCap(1_000_000, RESERVE, SHARE, 65_536));
        // 32K 窗口即使把下限调到 64K，也只能用占比内的 5.7K
        assertEquals(5_734, ChatContextService.historyTokenCap(32_768, RESERVE, SHARE, 65_536));
    }
}
