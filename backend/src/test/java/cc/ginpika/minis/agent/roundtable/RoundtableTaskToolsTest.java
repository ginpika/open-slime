package cc.ginpika.minis.agent.roundtable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import cc.ginpika.minis.ai.core.tool.multiagent.RequestDiscussionTool;
import cc.ginpika.minis.ai.core.tool.multiagent.UpdateTodoTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RoundtableTaskToolsTest {
    private final ObjectMapper om = new ObjectMapper();
    private final RoundtableTodoStore board = mock(RoundtableTodoStore.class);
    private final AgentDirectory directory = mock(AgentDirectory.class);
    private final RuntimeEventBus events = mock(RuntimeEventBus.class);
    private final RoundtableTaskTools tools = new RoundtableTaskTools(om, board, directory, events);
    private final RoundtableTodoStore.TodoRow todo = new RoundtableTodoStore.TodoRow(
            "todo", "session", "topic", "peer", "work", null, null, 0, "doing", "peer",
            null, null, null, 1, 1L, 1L);

    private com.fasterxml.jackson.databind.JsonNode call(String arguments) {
        var call = om.createObjectNode();
        call.putObject("function").put("arguments", arguments);
        return call;
    }

    @Test void userDecisionPersistsTargetAndEmitsDiscussionEvent() {
        String reply = tools.runTool(RequestDiscussionTool.NAME,
                call("{\"reason\":\"need a decision\",\"with\":\"用户\"}"),
                "session", "peer", "topic", todo);
        assertTrue(reply.contains("用户拍板"));
        verify(board).setBlockWith("todo", RoundtableTodoStore.BLOCK_WITH_USER);
        var event = ArgumentCaptor.forClass(RuntimeEvent.class);
        verify(events).publish(event.capture());
        assertEquals(RuntimeEventType.DISCUSSION_REQUESTED, event.getValue().type());
    }

    @Test void unresolvedTargetDoesNotWritePartialBlockedState() {
        when(board.todo("todo")).thenReturn(Optional.of(todo));
        when(directory.peers("session")).thenReturn(List.of());
        assertTrue(tools.runTool(UpdateTodoTool.NAME,
                call("{\"id\":\"todo\",\"status\":\"blocked\",\"reason\":\"need help\",\"with\":\"unknown\"}"),
                "session", "peer", "topic", todo).contains("认不出的名字"));
        verify(board, never()).update(anyString(), any(), any(), any(), any(), any());
        verifyNoInteractions(events);
    }

    @Test void missingInvocationIdentityDoesNotTouchBoard() {
        assertTrue(tools.runTool(UpdateTodoTool.NAME, call("{}"), null, "peer", null, null)
                .contains("不可用"));
        verifyNoInteractions(board, directory, events);
    }
}
