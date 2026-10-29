package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.runtime.event.RuntimeEventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoundtableActivityTest {
    private final RuntimeEventStore events = mock(RuntimeEventStore.class);
    private final RoundtableMessageStore messages = mock(RoundtableMessageStore.class);
    private final RoundtableActivity activity = new RoundtableActivity(events, messages, new ObjectMapper());

    private RuntimeEventStore.EventRow event(long id, String type, String target, String task, String payload) {
        return new RuntimeEventStore.EventRow(id, "event-" + id, "selected", type, "alice", target,
                task, 20, null, payload, id * 1000);
    }

    @Test void mergesPublicSpeechAndDirectedSignalsWithoutInferringRecipientsFromReplyOrder() {
        when(messages.recentActivity("selected", 30)).thenReturn(List.of(
                new RoundtableMessageStore.ActivityRow(1, "peer", "alice", "Alice", "公开意见", null, 1000),
                new RoundtableMessageStore.ActivityRow(2, "peer", "bob", "Bob", "另一个意见", null, 2000)));
        when(events.recent("selected", 100)).thenReturn(List.of(
                event(3, "DISCUSSION_REQUESTED", null, null, "{\"with\":\"bob,user,bob\",\"reason\":\"需要决定\"}"),
                event(4, "TASK_BLOCKED", null, null, "{\"todoId\":\"task-1\",\"reason\":\"缺少资料\"}")));
        var result = activity.recent("selected");
        assertEquals("task-1", result.getFirst().taskId());
        assertEquals(List.of("bob", "user"), result.get(1).targets());
        assertEquals("需要决定", result.get(1).text());
        assertTrue(result.get(2).targets().isEmpty());
        assertTrue(result.get(3).targets().isEmpty());
        verify(events).recent("selected", 100);
        verify(messages).recentActivity("selected", 30);
    }

    @Test void toleratesLegacyPayloadAndCapsTheCombinedWindow() {
        when(messages.recentActivity("selected", 30)).thenReturn(List.of());
        when(events.recent("selected", 100)).thenReturn(java.util.stream.LongStream.rangeClosed(1, 40)
                .mapToObj(id -> event(id, "AGENT_REPORT", " bob , bob ", null, "broken-json")).toList());
        var result = activity.recent("selected");
        assertEquals(30, result.size());
        assertEquals("event:40", result.getFirst().id());
        assertEquals(List.of("bob"), result.getFirst().targets());
        assertEquals("", result.getFirst().text());
    }

    @Test void messageWindowIsScopedBoundedAndDoesNotLoadExecutionInternals() {
        var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE chat_message(id INTEGER PRIMARY KEY, session_id TEXT, role TEXT, "
                    + "peer_id TEXT, peer_name TEXT, content TEXT, event_kind TEXT, created_at INTEGER)");
            jdbc.update("INSERT INTO chat_message VALUES(1,'selected','peer','alice','Alice',?,NULL,1000)", "a".repeat(500));
            jdbc.update("INSERT INTO chat_message VALUES(2,'other','peer','bob','Bob','private',NULL,2000)");
            var result = new RoundtableMessageStore(jdbc).recentActivity("selected", 1);
            assertEquals(1, result.size());
            assertEquals(1, result.getFirst().id());
            assertEquals(280, result.getFirst().content().length());
        } finally {
            dataSource.destroy();
        }
    }
}
