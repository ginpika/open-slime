package cc.ginpika.minis.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.MemorySurface;
import org.junit.jupiter.api.Test;

/**
 * 记忆策略的不变量回归（memory-v6 §2.3 · I3–I6 与 §8.1 读谓词的 Java 镜像）。
 *
 * <p>这些断言是「策略是标准条款的可执行形式」的证明：矩阵违例必须被拒、L3 必须绑会话、L4 必须走发布。
 */
class MemoryPolicyTest {

    @Test
    void userProfileOnlyAcceptsL1WithoutBindings() {
        assertNull(MemoryPolicy.violation("USER", "USER_PROFILE", "L1", null, null, null, null));
        assertNotNull(MemoryPolicy.violation("USER", "USER_PROFILE", "L1", "p1", null, null, null),
                "I3：owner=USER 不得绑定项目");
        assertNotNull(MemoryPolicy.violation("USER", "AGENT", "L1", null, null, null, null),
                "owner=USER 只能承载 USER_PROFILE");
        assertNotNull(MemoryPolicy.violation("USER", "USER_PROFILE", "L2", null, null, null, null),
                "画像只能是 L1");
    }

    @Test
    void projectMustBindProjectId() {
        assertNull(MemoryPolicy.violation("PROJECT", "PROJECT", "L1", "p1", null, null, null));
        assertNotNull(MemoryPolicy.violation("PROJECT", "PROJECT", "L1", null, null, null, null),
                "I4：owner=PROJECT 必须绑定 project_id");
        assertNotNull(MemoryPolicy.violation("PROJECT", "USER_PROFILE", "L1", "p1", null, null, null),
                "项目域不得承载画像");
    }

    @Test
    void agentLayerConstraints() {
        assertNull(MemoryPolicy.violation("AGENT", "AGENT", "L2", null, "a1", null, null));
        assertNull(MemoryPolicy.violation("AGENT", "AGENT", "L3", null, "a1", "s1", null));
        assertNull(MemoryPolicy.violation("AGENT", "AGENT", "L3", null, "a1", null, 7L));
        assertNotNull(MemoryPolicy.violation("AGENT", "AGENT", "L1", null, "a1", null, null),
                "AGENT 不允许 L1（共享权威）");
        assertNotNull(MemoryPolicy.violation("AGENT", "AGENT", "L3", null, "a1", null, null),
                "I5：L3 必须绑定 session 或 task");
        assertNotNull(MemoryPolicy.violation("AGENT", "AGENT", "L2", null, null, null, null),
                "I4：owner=AGENT 必须绑定 agent_id");
    }

    @Test
    void taskOwnerMustBindAndStayL3() {
        assertNull(MemoryPolicy.violation("TASK", "TASK_EPISODIC", "L3", null, "a1", "s1", null));
        assertNull(MemoryPolicy.violation("TASK", "TASK_EPISODIC", "L3", null, null, null, 3L));
        assertNotNull(MemoryPolicy.violation("TASK", "TASK_EPISODIC", "L3", null, null, null, null),
                "I4：owner=TASK 必须绑定 session 或 task");
        assertNotNull(MemoryPolicy.violation("TASK", "TASK_EPISODIC", "L2", null, null, "s1", null),
                "owner=TASK 只能是 L3");
    }

    @Test
    void unknownOwnerIsRejected() {
        assertNotNull(MemoryPolicy.violation(null, "AGENT", "L2", null, "a1", null, null));
        assertNotNull(MemoryPolicy.violation("BOGUS", "AGENT", "L2", null, "a1", null, null));
    }

    @Test
    void readMirrorEnforcesSessionIsolationAndL4Provenance() {
        MemoryExec exec = new MemoryExec(null, "a1", "p1", "s1", 5L, MemorySurface.CHAT, false, "t1",
                MemoryIdentity.REAL);

        assertTrue(MemoryPolicy.canRead(exec, "USER", "USER_PROFILE", null, null, null, null, "L1"));
        assertTrue(MemoryPolicy.canRead(exec, "PROJECT", "PROJECT", "p1", null, null, null, "L1"));
        assertTrue(MemoryPolicy.canRead(exec, "AGENT", "AGENT", null, "a1", null, null, "L2"));
        assertTrue(MemoryPolicy.canRead(exec, "TASK", "TASK_EPISODIC", null, "a1", "s1", null, "L3"),
                "同一会话的任务工作记忆可读");

        assertTrue(!MemoryPolicy.canRead(exec, "PROJECT", "PROJECT", "p2", null, null, null, "L1"),
                "别的项目不可读（跨项目隔离）");
        assertTrue(!MemoryPolicy.canRead(exec, "AGENT", "AGENT", null, "a2", null, null, "L2"),
                "别的 Agent 的私有记忆不可读");
        assertTrue(!MemoryPolicy.canRead(exec, "AGENT", "AGENT", null, "a1", "s9", null, "L3"),
                "同一 Agent 但别的会话的 L3 不可读（v6 会话隔离）");
        assertTrue(!MemoryPolicy.canRead(exec, "AGENT", "AGENT", null, "a1", "s1", null, "L4"),
                "I6：AGENT×L4 必须经发布（R5）才可读");
    }

    @Test
    void humanScopeSeesEverything() {
        MemoryExec human = MemoryExec.system(null);
        assertEquals(true, human.human());
        assertTrue(MemoryPolicy.canRead(human, "PROJECT", "PROJECT", "p2", null, null, null, "L3"));
        assertTrue(MemoryPolicy.canRead(human, "USER", "USER_PROFILE", null, null, null, null, "L1"));
    }

    @Test
    void noScopeSeesOnlyProfile() {
        MemoryExec none = MemoryExec.none();
        assertEquals(false, none.human());
        assertTrue(MemoryPolicy.canRead(none, "USER", "USER_PROFILE", null, null, null, null, "L1"));
        assertTrue(!MemoryPolicy.canRead(none, "AGENT", "AGENT", null, "a1", null, null, "L2"));
        assertTrue(!MemoryPolicy.canRead(none, "PROJECT", "PROJECT", "p1", null, null, null, "L1"));
    }
}
