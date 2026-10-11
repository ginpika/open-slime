package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回归：公开记录的窗口选取必须「预算越宽、留得越多」。
 *
 * <p>这是一个真实事故：窗口循环写反了方向（从最旧往后累加、累到预算就用掉一个名额），
 * 于是<b>预算充裕时反而几乎把整段记录丢光</b>，只剩最后一行——而最后一行是「入场」这类事件行，
 * 不是人本轮的问题。成员读到的记录里没有题目，就纷纷以为「局面里还没有实际话题」而集体沉默。
 *
 * <p>它是私有逻辑、原先没有任何测试覆盖，才让编译与既有测试全绿地漏了过去——所以这里把规则一条条钉住。
 */
class RoundtableTranscriptWindowTest {

    @Test
    void generousBudgetKeepsEverything() {
        /* 预算远大于总量：一行都不该丢（这正是那次事故的反面） */
        assertEquals(List.of(0, 1, 2, 3),
                RoundtableChatService.windowOf(List.of(10L, 10L, 10L, 10L), 128, 100_000, -1));
    }

    @Test
    void tightBudgetKeepsTheNewestOnes() {
        /* 预算 250、每行 100：只留最后两行（200 ≤ 250；再加一行 300 > 250） */
        assertEquals(List.of(2, 3),
                RoundtableChatService.windowOf(List.of(100L, 100L, 100L, 100L), 128, 250, -1));
    }

    @Test
    void countLimitCapsFirst() {
        /* 条数上限 2：预算再多也只回放最后两行 */
        assertEquals(List.of(3, 4),
                RoundtableChatService.windowOf(List.of(1L, 1L, 1L, 1L, 1L), 2, 100_000, -1));
    }

    @Test
    void keepsLastLineEvenWhenItAloneBlowsTheBudget() {
        /* 预算为 0：仍然保留最后一行——空着比只给半句强 */
        assertEquals(List.of(2),
                RoundtableChatService.windowOf(List.of(50L, 50L, 50L), 128, 0, -1));
    }

    @Test
    void questionIsAlwaysCarriedEvenWhenOutsideTheWindow() {
        /* 人的话在第 0 行、窗口只够到最后一行：它必须被补上，且补在最末（紧邻局面） */
        assertEquals(List.of(3, 0),
                RoundtableChatService.windowOf(List.of(100L, 100L, 100L, 100L), 128, 150, 0));
    }

    @Test
    void questionInsideTheWindowIsNotDuplicated() {
        /* 人的话本来就在窗口内：按原序回放，不重复 */
        assertEquals(List.of(2, 3),
                RoundtableChatService.windowOf(List.of(100L, 100L, 100L, 100L), 128, 250, 3));
    }

    @Test
    void emptyHistoryYieldsNothing() {
        assertEquals(List.of(), RoundtableChatService.windowOf(List.of(), 128, 100_000, -1));
    }
}
