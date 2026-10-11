package cc.ginpika.minis.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.agent.subagent.ScratchpadStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 子代理面上下文源（STEP 6-③）的回归：{@link SubagentContextProvider} 只服务 SUBAGENT 面，
 * 且在人格基底之后追加「共享黑板 + 当前时间」。
 */
class SubagentContextProviderTest {

    /** 与 schema.sql 的 subagent_scratch 同构（去注释） */
    private static final String DDL = """
            CREATE TABLE subagent_scratch(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              session_id TEXT NOT NULL,
              author TEXT,
              exec_id INTEGER,
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL)""";

    private static ScratchpadStore pad() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute(DDL);
        return new ScratchpadStore(jdbc);
    }

    private static ContextRequest req(String sessionId, ContextRequest.Surface surface) {
        return new ContextRequest("m", "子代理人格", sessionId, false, null, 0, null, surface);
    }

    @Test
    void injectsBlackboardAndTimeForSubagentSurface() {
        ScratchpadStore pad = pad();
        pad.append("hub-1", "甲", 1L, "查到 X");

        String suffix = new SubagentContextProvider(pad).systemSuffix(req("hub-1", ContextRequest.Surface.SUBAGENT));

        assertTrue(suffix.contains("此前同伴的发现"));
        assertTrue(suffix.contains("甲"));
        assertTrue(suffix.contains("查到 X"));
        assertTrue(suffix.contains("当前时间："));
    }

    @Test
    void onlyTimeWhenNoSession() {
        String suffix = new SubagentContextProvider(pad()).systemSuffix(req(null, ContextRequest.Surface.SUBAGENT));

        assertFalse(suffix.contains("此前同伴的发现"));
        assertTrue(suffix.startsWith("当前时间："));
    }

    @Test
    void silentOnOtherSurfaces() {
        ScratchpadStore pad = pad();
        pad.append("hub-1", "甲", 1L, "查到 X");
        SubagentContextProvider provider = new SubagentContextProvider(pad);

        assertEquals("", provider.systemSuffix(req("hub-1", ContextRequest.Surface.CHAT)));
        assertEquals("", provider.systemSuffix(req("hub-1", ContextRequest.Surface.HEARTBEAT)));
    }
}
