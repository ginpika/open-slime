package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 会话级发言版本（revision）的回归：按会话独立递增、可清零。
 *
 * <p>它是「旧结果不覆盖新状态」的判据（设计一 §五-1）：一次发言开始生成时记下 baseline，
 * 提交前与此刻比对——不一致即丢弃。这里钉住的正是这个计数器的语义。
 */
class RoundtableRevisionTest {

    @Test
    void bumpsIndependentlyPerSession() {
        RoundtableRevision revisions = new RoundtableRevision();
        assertEquals(0, revisions.current("s1"), "从未 bump 过即 0");
        assertEquals(1, revisions.bump("s1"));
        assertEquals(2, revisions.bump("s1"));
        /* 别的会话不受影响 */
        assertEquals(0, revisions.current("s2"));
        assertEquals(1, revisions.bump("s2"));
    }

    @Test
    void clearResetsASession() {
        RoundtableRevision revisions = new RoundtableRevision();
        revisions.bump("s1");
        revisions.clear("s1");
        assertEquals(0, revisions.current("s1"));
    }

    @Test
    void nullSessionIsSafe() {
        RoundtableRevision revisions = new RoundtableRevision();
        assertEquals(0, revisions.current(null));
        assertEquals(0, revisions.bump(null));
    }
}
