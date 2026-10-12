package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 工作集的回归（memory-v6 §13 · T12）：写入必须绑定会话 / 任务、关闭后不再注入、
 * 收尾蒸馏只整理「本轮之前」的工作项。
 */
class WorkingSetServiceTest {

    private static final String DDL = """
            CREATE TABLE memory_work(
              id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, task_id INTEGER,
              agent_id TEXT, project_id TEXT, kind TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
              ref_type TEXT, ref_id INTEGER, status TEXT NOT NULL DEFAULT 'open',
              expires_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""";

    private static MemoryWorkStore store() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        return new MemoryWorkStore(j);
    }

    private static MemoryExec exec(String sessionId, Long taskId) {
        return new MemoryExec(null, "a1", "p1", sessionId, taskId, MemorySurface.CHAT, false, "t1",
                MemoryIdentity.REAL);
    }

    @Test
    void recordRequiresSessionOrTaskBinding() {
        MemoryWorkStore works = store();
        WorkingSetService service = new WorkingSetService(works, mock(MemoryIngest.class), new MemoryProps());

        long bound = service.record(exec("s1", null), "progress", "标题", "正文", null, null);
        long orphan = service.record(exec(null, null), "progress", "标题", "正文", null, null);

        assertTrue(bound > 0, "绑会话的工作项可落库");
        assertEquals(0, orphan, "无会话 / 任务绑定的工作状态不落库（L3 约束）");
        assertEquals(1, service.open(exec("s1", null), 10).size());
    }

    @Test
    void closeRemovesFromOpenSet() {
        MemoryWorkStore works = store();
        WorkingSetService service = new WorkingSetService(works, mock(MemoryIngest.class), new MemoryProps());
        long id = service.record(exec("s1", null), "temp_conclusion", null, "临时结论", null, null);

        assertEquals(1, service.close(id));
        assertTrue(service.open(exec("s1", null), 10).isEmpty());
        assertEquals(1, service.bySession("s1", 10).size(), "关闭不是删除：按会话仍可回查");
    }

    @Test
    void distillOnlyTouchesItemsOlderThanCutoff() {
        MemoryWorkStore works = store();
        MemoryIngest ingest = mock(MemoryIngest.class);
        org.mockito.Mockito.when(ingest.distill(any(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        WorkingSetService service = new WorkingSetService(works, ingest, new MemoryProps());
        long older = service.record(exec("s1", null), "progress", null, "早先的进度", null, null);
        sleep(5);
        long cutoff = System.currentTimeMillis();
        sleep(5);
        long fresh = service.record(exec("s1", null), "progress", null, "本轮刚记的结论", null, null);

        int distilled = service.distill(exec("s1", null), cutoff);

        assertEquals(1, distilled, "只蒸馏截止时刻之前的工作项");
        verify(ingest).distill(any(MemoryExec.class), anyString(), anyString());
        List<MemoryWorkStore.WorkRow> still = service.open(exec("s1", null), 10);
        assertEquals(1, still.size());
        assertEquals(fresh, still.get(0).id(), "本轮刚记的留到下一次收尾");
        assertEquals("distilled", service.bySession("s1", 10).stream()
                .filter(w -> w.id() == older).findFirst().orElseThrow().status());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void checkpointDigestCarriesOpenWork() {
        MemoryWorkStore works = store();
        WorkingSetService service = new WorkingSetService(works, mock(MemoryIngest.class), new MemoryProps());
        service.record(exec("s1", 3L), "progress", "第三步", "替换并发实现", null, null);

        String digest = service.checkpointDigest(exec("s1", 3L));

        assertTrue(digest.contains("\"sessionId\":\"s1\""), "检查点带会话归属");
        assertTrue(digest.contains("替换并发实现"), "检查点带当前工作集摘要");
    }
}
