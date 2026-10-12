package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 运行指标的回归（memory-v6 §17.2）：计数真的会动、派生值（快照覆盖率）算得对。
 */
class MemoryMetricsTest {

    @AfterEach
    void cleanup() {
        MemoryMetrics.reset();
    }

    @Test
    void countersAndDerivedCoverage() {
        MemoryMetrics.scopeMissing();
        MemoryMetrics.invariantViolation();
        MemoryMetrics.invariantViolation();
        MemoryMetrics.writeConflict();
        MemoryMetrics.budgetEvicted(3);
        MemoryMetrics.budgetEvicted(0);       // 0 不计
        MemoryMetrics.crossDomainBlocked();
        MemoryMetrics.snapshotBuilt();
        MemoryMetrics.snapshotBuilt();
        MemoryMetrics.snapshotSaved();

        Map<String, Object> m = MemoryMetrics.snapshot();

        assertEquals(1L, ((Number) m.get("scopeMissing")).longValue());
        assertEquals(2L, ((Number) m.get("invariantViolation")).longValue());
        assertEquals(1L, ((Number) m.get("writeConflict")).longValue());
        assertEquals(3L, ((Number) m.get("budgetEvicted")).longValue());
        assertEquals(1L, ((Number) m.get("crossDomainBlocked")).longValue());
        assertEquals(2L, ((Number) m.get("snapshotBuilt")).longValue());
        assertEquals(1L, ((Number) m.get("snapshotSaved")).longValue());
        assertEquals(0.5d, ((Number) m.get("snapshotCoverage")).doubleValue(), "覆盖率 = saved / built");
    }

    @Test
    void emptyMetricsStillReportZeroCoverage() {
        Map<String, Object> m = MemoryMetrics.snapshot();

        assertEquals(0d, ((Number) m.get("snapshotCoverage")).doubleValue(), "没有构建过快照时覆盖率记 0，不除零");
    }
}
