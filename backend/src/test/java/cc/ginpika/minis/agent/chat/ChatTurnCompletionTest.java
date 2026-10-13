package cc.ginpika.minis.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.runtime.TaskCheckpointService;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.project.ProjectStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatTurnCompletionTest {
    private final HubSessionStore sessions = mock(HubSessionStore.class);
    private final ProjectStore projects = mock(ProjectStore.class);
    private final MemoryIngest memory = mock(MemoryIngest.class);
    private final WorkingSetService working = mock(WorkingSetService.class);
    private final TaskCheckpointService checkpoints = mock(TaskCheckpointService.class);
    private final SessionTitleService titles = mock(SessionTitleService.class);
    private final SessionEventBus events = mock(SessionEventBus.class);
    private final ChatExecution execution = mock(ChatExecution.class);
    private final ChatTurnCompletion completion = new ChatTurnCompletion(sessions, projects, memory,
            working, checkpoints, titles, events);
    private final HubRow row = row("old-project");
    private final MemoryExec scope = MemoryExec.chat("agent", "old-project", "s", 7L,
            "turn", MemoryIdentity.REAL);

    private static HubRow row(String project) {
        return new HubRow("s", "manual title", "ai", null, null, "agent", project,
                true, false, 30, false, false, 1L, 1L);
    }

    @Test void projectChangedByToolUsesLatestBindingForExtractionAndOriginalCheckpoint() {
        when(execution.turnTracked()).thenReturn(true);
        when(sessions.findById("s")).thenReturn(Optional.of(row("story")));
        when(projects.isNarrative("story")).thenReturn(true);
        when(working.checkpointDigest(scope)).thenReturn("checkpoint");
        completion.complete(execution, row, scope, 7L, 9L, "question", "answer");
        var captured = ArgumentCaptor.forClass(MemoryExec.class);
        verify(memory).tap(eq("s"), captured.capture(), eq("question"), eq("answer"), any());
        assertEquals("story", captured.getValue().projectId());
        assertEquals(MemoryIdentity.FICTION, captured.getValue().identityOrInferred());
        verify(checkpoints).save(7L, "checkpoint");
        verifyNoInteractions(titles);
        verify(events).publish(any());
    }

    @Test void temporaryChatSkipsMemoryButStillNotifies() {
        when(execution.turnTracked()).thenReturn(true);
        when(sessions.memoryOff("s")).thenReturn(true);
        when(sessions.findById("s")).thenReturn(Optional.of(row));
        completion.complete(execution, row, scope, 7L, 9L, "question", "answer");
        verifyNoInteractions(memory, working, checkpoints, titles);
        verify(events).publish(any());
    }

    @Test void roundtableSpeechHasNoDirectChatCompletionSideEffects() {
        completion.complete(execution, row, scope, 7L, 9L, "question", "answer");
        verifyNoInteractions(sessions, projects, memory, working, checkpoints, titles, events);
    }
}
