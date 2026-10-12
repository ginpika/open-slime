package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.config.MemoryProps;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 轮内写缓冲的回归（memory-v6 §4.4）：圈定后延后到轮末提交；未圈定 / 关闭开关时立即执行。
 */
class MemoryStageTest {

    @AfterEach
    void cleanup() {
        MemoryStage.clear();
    }

    @Test
    void deferredUntilCommit() {
        List<String> ran = new ArrayList<>();
        MemoryStage.begin();

        assertTrue(MemoryStage.defer(() -> ran.add("publish"), new MemoryProps()));
        assertTrue(ran.isEmpty(), "轮内不执行——本轮注入因此看不到它");
        assertEquals(1, MemoryStage.pendingCount());

        assertEquals(1, MemoryStage.commit());
        assertEquals(List.of("publish"), ran);
        assertEquals(0, MemoryStage.pendingCount(), "提交后缓冲清空");
    }

    @Test
    void executesImmediatelyWithoutScope() {
        List<String> ran = new ArrayList<>();

        assertFalse(MemoryStage.defer(() -> ran.add("publish"), new MemoryProps()));

        assertEquals(List.of("publish"), ran, "没有圈定缓冲区（后台 / 管理页）时立即执行");
    }

    @Test
    void disabledSwitchRunsImmediately() {
        List<String> ran = new ArrayList<>();
        MemoryProps props = new MemoryProps();
        props.getStaging().setEnabled(false);
        MemoryStage.begin();

        assertFalse(MemoryStage.defer(() -> ran.add("publish"), props));
        assertEquals(List.of("publish"), ran);
        MemoryStage.commit();
    }
}
