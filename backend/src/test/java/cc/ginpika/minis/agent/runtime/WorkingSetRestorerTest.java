package cc.ginpika.minis.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.memory.store.MemoryWorkStore;
import cc.ginpika.minis.config.MemoryProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 工作状态恢复的回归（memory-v6 §9.2 · T9/T22）：检查点 → 工作集；幂等；空检查点不产生噪声。
 */
class WorkingSetRestorerTest {

    private static final String DDL = """
            CREATE TABLE memory_work(
              id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, task_id INTEGER,
              agent_id TEXT, project_id TEXT, kind TEXT NOT NULL, title TEXT, body TEXT NOT NULL,
              ref_type TEXT, ref_id INTEGER, status TEXT NOT NULL DEFAULT 'open',
              expires_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)""";

    private record Fixture(MemoryWorkStore works, WorkingSetService service, TaskStore tasks) {
    }

    private static Fixture fixture() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate j = new JdbcTemplate(ds);
        for (String stmt : DDL.split("\\n(?=CREATE )")) {
            j.execute(stmt);
        }
        MemoryWorkStore works = new MemoryWorkStore(j);
        WorkingSetService service = new WorkingSetService(works, mock(MemoryIngest.class),
                new MemoryProps());
        return new Fixture(works, service, mock(TaskStore.class));
    }

    private static TaskStore.TaskRow task(long id) {
        return new TaskStore.TaskRow(id, null, TaskStore.KIND_SESSION, TaskStore.ST_INTERRUPTED,
                "中断的活", "s1", "a1", null, null, null, null, "chat", 1L, 1L);
    }

    private static WorkingSetRestorer restorer(Fixture f) {
        return new WorkingSetRestorer(f.tasks(), f.service(), f.works(), new ObjectMapper());
    }

    @Test
    void restoresCheckpointIntoWorkingSetOnce() {
        Fixture f = fixture();
        when(f.tasks().task(7L)).thenReturn(Optional.of(task(7L)));
        when(f.tasks().checkpointOf(7L)).thenReturn("""
                {"sessionId":"s1","turnId":"42","work":[
                  {"kind":"progress","title":"第三步","body":"替换并发实现"}]}""");
        WorkingSetRestorer restorer = restorer(f);

        assertEquals(1, restorer.restore(7L), "有检查点 → 还原一条工作状态");
        assertEquals(0, restorer.restore(7L), "幂等：同一任务不重复还原");

        MemoryWorkStore.WorkRow restored = f.service().bySession("s1", 10).get(0);
        assertEquals(WorkingSetRestorer.RESTORE_TITLE, restored.title());
        assertTrue(restored.body().contains("替换并发实现"), "还原内容带上「做到哪一步」");
        assertEquals(7L, restored.taskId(), "还原出的工作项仍绑在原任务上");
    }

    @Test
    void skipsWhenCheckpointMissing() {
        Fixture f = fixture();
        when(f.tasks().task(9L)).thenReturn(Optional.of(task(9L)));
        when(f.tasks().checkpointOf(9L)).thenReturn(null);

        assertEquals(0, restorer(f).restore(9L));
        assertTrue(f.service().bySession("s1", 10).isEmpty(), "没有检查点就不制造工作项");
    }

    @Test
    void emptyWorkListFallsBackToReadableNote() {
        Fixture f = fixture();
        when(f.tasks().task(11L)).thenReturn(Optional.of(task(11L)));
        when(f.tasks().checkpointOf(11L)).thenReturn("{\"sessionId\":\"s1\",\"work\":[]}");
        WorkingSetRestorer restorer = restorer(f);

        assertEquals(1, restorer.restore(11L), "检查点在但工作集为空：仍留一条可读提示");

        assertTrue(f.service().bySession("s1", 10).get(0).body().contains("被中断"));
    }

    @Test
    void restoreInterruptedWalksAllRestorable() {
        Fixture f = fixture();
        when(f.tasks().idsWithCheckpoint(anyString())).thenReturn(List.of(7L, 9L));
        when(f.tasks().task(7L)).thenReturn(Optional.of(task(7L)));
        when(f.tasks().checkpointOf(7L)).thenReturn("{\"work\":[{\"kind\":\"progress\",\"body\":\"甲\"}]}");
        when(f.tasks().task(9L)).thenReturn(Optional.of(task(9L)));
        when(f.tasks().checkpointOf(9L)).thenReturn("{\"work\":[{\"kind\":\"progress\",\"body\":\"乙\"}]}");

        assertEquals(2, restorer(f).restoreInterrupted(), "逐条还原可恢复任务");
    }
}
