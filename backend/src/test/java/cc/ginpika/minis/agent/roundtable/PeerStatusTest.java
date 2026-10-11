package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import org.junit.jupiter.api.Test;

/**
 * 二维状态投影的回归（设计一 §四）：把轮次参与状态投影成「向不向讨论室发言」×「工作走到哪」。
 *
 * <p>重点钉住几个单枚举回答不了、二维才能表达的场面：
 * {@code SILENT + WAITING_EVENT}（不发言、在等事件）、{@code LISTENING + BLOCKED}（被征询卡住整条链）。
 */
class PeerStatusTest {

    private static void assertStatus(PeerState from, CommunicationState comm, ExecutionState exec) {
        PeerStatus status = PeerStatus.of(from);
        assertEquals(comm, status.communication(), "发言状态: " + from);
        assertEquals(exec, status.execution(), "执行状态: " + from);
    }

    @Test
    void projectsEachPeerStateToTwoDimensions() {
        assertStatus(PeerState.SPEAKING, CommunicationState.SPEAKING, ExecutionState.RUNNING);
        assertStatus(PeerState.ACTIVE, CommunicationState.LISTENING, ExecutionState.READY);
        assertStatus(PeerState.SILENT, CommunicationState.SILENT, ExecutionState.COMPLETED);
        /* 被征询待表态：整条推进链都在等它，是「被阻塞」而不是「就绪」 */
        assertStatus(PeerState.PROMPTED, CommunicationState.LISTENING, ExecutionState.BLOCKED);
        /* 请求用户表态：不发言，正在等外部事件 */
        assertStatus(PeerState.AWAIT_USER, CommunicationState.SILENT, ExecutionState.WAITING_EVENT);
        assertStatus(PeerState.PROPOSE_END, CommunicationState.SILENT, ExecutionState.COMPLETED);
        assertStatus(PeerState.INACTIVE, CommunicationState.SILENT, ExecutionState.COMPLETED);
    }

    @Test
    void nullFallsBackSafely() {
        assertEquals(CommunicationState.SILENT, PeerStatus.of(null).communication());
    }
}
