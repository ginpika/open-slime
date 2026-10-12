package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 会话自动命名的输出清洗（纯函数，不起 Spring 上下文）。 */
class SessionTitleServiceTest {

    @Test
    void stripsWrappingQuotesAndPrefix() {
        assertEquals("记忆架构设计", SessionTitleService.sanitize("「记忆架构设计」"));
        assertEquals("记忆架构设计", SessionTitleService.sanitize("标题：记忆架构设计。"));
        assertEquals("记忆架构设计", SessionTitleService.sanitize("\n 记忆架构设计 \n"));
    }

    @Test
    void clipsToSixteenChars() {
        String longText = "一二三四五六七八九十一二三四五六七八九十";
        assertEquals(16, SessionTitleService.sanitize(longText).length());
    }

    @Test
    void handlesNullAndBlank() {
        assertEquals("", SessionTitleService.sanitize(null));
        assertEquals("", SessionTitleService.sanitize("   "));
    }
}
