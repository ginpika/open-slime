package cc.ginpika.minis.todo;

import java.util.List;

/** Personal items and observed effort, independent of Agent execution tasks. */
public final class TodoDtos {
    private TodoDtos() {}

    public record Item(String id, String title, String note, String projectId, String projectName,
                       String projectStatus, String state, String plannedOn, Long dueAt,
                       long createdAt, long updatedAt, Long firstStartedAt, Long completedAt,
                       Long closedAt, long version, long spentMillis) {}
    public record Focus(String id, String todoId, String title, String projectId, String projectName,
                        long startedAt, Long endedAt, String source, String note, long version) {}
    public record Event(long id, String kind, String detail, long occurredAt) {}
    public record ProjectTag(String id, String name, String status) {}
    public record DayNote(String day, String body, long version) {}
    public record ProjectSummary(String id, String name, String status, long openCount, long doingCount,
                                 long completedCount, long droppedCount, long spentMillis) {}
    public record DayHour(String label, long startedAt, long endedAt) {}
    public record DayWork(String day, String zone, long now, long rangeStart, long rangeEnd,
                          List<DayHour> hours, List<Focus> focus, List<Item> items) {}
    public record Overview(String today, String zone, long now, long dayStart, long dayEnd,
                           List<Item> items, List<Focus> todayFocus, Focus active,
                           List<ProjectTag> projects, DayNote dayNote) {}
    public record Detail(Item item, List<Focus> focus, List<Event> events) {}
    public record Allocation(String todoId, String title, String projectId, String projectName, long millis) {}
    public record Footprint(String todoId, String title, String detail, long occurredAt) {}
    public record Review(String month, long now, long rangeStart, long rangeEnd,
                         long completedCount, long droppedCount, long spentMillis, int activeDays,
                         List<Allocation> allocations, List<Item> closed, List<Footprint> footprints,
                         List<DayNote> notes) {}

    public record ItemRequest(String title, String note, String projectId, String plannedOn,
                              Long dueAt, Long version) {}
    public record TransitionRequest(String action, Long version) {}
    public record StartRequest(String expectedActiveId) {}
    public record FocusRequest(String todoId, Long startedAt, Long endedAt, String note, Long version) {}
    public record NoteRequest(String body, Long version) {}
    public record Deleted(boolean deleted) {}
}
