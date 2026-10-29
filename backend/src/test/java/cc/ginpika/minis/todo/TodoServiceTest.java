package cc.ginpika.minis.todo;

import static cc.ginpika.minis.todo.TodoDtos.*;
import static org.junit.jupiter.api.Assertions.*;

import cc.ginpika.minis.project.ProjectStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class TodoServiceTest {
    static class Time extends Clock {
        Instant value=Instant.parse("2026-10-16T06:25:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(value,zone); }
        public Instant instant() { return value; }
        void advance(long seconds) { value=value.plusSeconds(seconds); }
    }
    static class Fixture implements AutoCloseable {
        final SingleConnectionDataSource ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        final JdbcTemplate jdbc=new JdbcTemplate(ds);
        final TodoStore store=new TodoStore(jdbc);
        final ProjectStore projects=new ProjectStore(jdbc);
        final Time clock=new Time();
        final TodoService service=new TodoService(store,projects,clock);
        final TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Fixture() {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("coding/schema.sql"),new ClassPathResource("todo/schema.sql")).execute(ds);
        }
        <T> T write(Supplier<T> action) { return tx.execute(status->action.get()); }
        Item create(String owner,String title) { return write(()->service.create(owner,new ItemRequest(title,"",null,null,null,null))); }
        Focus start(String owner,String id,String active) { return write(()->service.start(owner,id,new StartRequest(active))); }
        Item transition(Item item,String action) { return write(()->service.transition("me",item.id(),new TransitionRequest(action,item.version()))); }
        Focus log(Item t,String start,String end) { return write(()->service.addFocus("me",new FocusRequest(t.id(),Instant.parse(start).toEpochMilli(),Instant.parse(end).toEpochMilli(),"补记",null))); }
        public void close() { ds.destroy(); }
    }

    @Test void ownerBoundaryIncludesEveryReadAndWrite() {
        try(var f=new Fixture()) {
            var t=f.create("me","我的事情"); var active=f.start("me",t.id(),null);
            assertTrue(f.service.overview("other","Asia/Shanghai",null).items().isEmpty());
            assertNull(f.service.overview("other","UTC",null).active());
            assertThrows(ResponseStatusException.class,()->f.service.detail("other",t.id()));
            assertThrows(ResponseStatusException.class,()->f.write(()->f.service.stop("other",active.id())));
            assertThrows(ResponseStatusException.class,()->f.write(()->f.service.replace("other",t.id(),new ItemRequest("偷改","",null,null,null,0L))));
            assertTrue(f.service.review("other","2026-10","UTC",null).allocations().isEmpty());
        }
    }

    @Test void onePersistentTimerSwitchesAtomicallyAndPausedTimeIsExcluded() {
        try(var f=new Fixture()) {
            var a=f.create("me","第一件"); var b=f.create("me","第二件");
            var first=f.start("me",a.id(),null); f.clock.advance(600);
            var second=f.start("me",b.id(),first.id()); f.clock.advance(300);
            assertEquals(600_000,f.service.detail("me",a.id()).item().spentMillis());
            assertEquals(second.id(),new TodoService(f.store,f.projects,f.clock).overview("me","UTC",null).active().id());
            assertThrows(ResponseStatusException.class,()->f.start("me",a.id(),first.id()));
            assertEquals(second.id(),f.service.overview("me","UTC",null).active().id());
            f.write(()->f.service.stop("me",second.id())); f.clock.advance(3600);
            assertEquals(300_000,f.service.detail("me",b.id()).item().spentMillis());
            f.write(()->f.service.stop("me",first.id())); // An old pause cannot stop another timer.
            assertNull(f.service.overview("me","UTC",null).active());
        }
    }

    @Test void closingStopsTimerAndRepeatedCompletionDoesNotMintAchievements() {
        try(var f=new Fixture()) {
            var t=f.create("me","一个想法"); f.start("me",t.id(),null); f.clock.advance(60);
            t=f.service.detail("me",t.id()).item(); var done=f.transition(t,"complete");
            assertNull(f.service.overview("me","UTC",null).active());
            assertEquals(1,f.service.review("me","2026-10","UTC",null).completedCount());
            f.transition(done,"complete");
            assertEquals(1,f.service.detail("me",t.id()).events().stream().filter(e->e.kind().equals("completed")).count());
            var reopened=f.transition(done,"reopen");
            assertEquals(0,f.service.review("me","2026-10","UTC",null).completedCount());
            var dropped=f.transition(reopened,"drop");
            assertEquals(60_000,dropped.spentMillis());
            assertEquals(1,f.service.review("me","2026-10","UTC",null).droppedCount());
            assertEquals(0,f.service.review("me","2026-10","UTC",null).completedCount());
        }
    }

    @Test void focusCorrectionValidatesFutureOverlapOwnershipAndVersions() {
        try(var f=new Fixture()) {
            var t=f.create("me","工作");
            var first=f.log(t,"2026-10-16T00:00:00Z","2026-10-16T01:00:00Z");
            assertThrows(ResponseStatusException.class,()->f.log(t,"2026-10-16T00:30:00Z","2026-10-16T01:30:00Z"));
            assertThrows(ResponseStatusException.class,()->f.log(t,"2026-10-16T07:00:00Z","2026-10-16T08:00:00Z"));
            assertThrows(ResponseStatusException.class,()->f.log(t,"2026-10-16T01:00:00Z","2026-10-16T00:00:00Z"));
            var second=f.log(t,"2026-10-16T01:00:00Z","2026-10-16T02:00:00Z"); // adjacent is allowed
            var fixed=f.write(()->f.service.editFocus("me",first.id(),new FocusRequest(t.id(),Instant.parse("2026-10-16T00:15:00Z").toEpochMilli(),first.endedAt(),"修正",first.version())));
            assertEquals(1,fixed.version());
            assertThrows(ResponseStatusException.class,()->f.write(()->f.service.deleteFocus("me",first.id(),0)));
            f.write(()->f.service.deleteFocus("me",second.id(),second.version()));
            assertEquals(45*60_000,f.service.detail("me",t.id()).item().spentMillis());
            assertTrue(f.service.detail("me",t.id()).events().stream().anyMatch(e->e.kind().equals("focus_deleted")));
        }
    }

    @Test void sameProjectIsOnlyAFilterAndDeletionKeepsPersonalHistory() {
        try(var f=new Fixture()) {
            f.projects.insert("p",null,"我的助手","software",false,"active",null);
            var t=f.write(()->f.service.create("me",new ItemRequest("工作","","p",null,null,null)));
            f.log(t,"2026-10-15T00:00:00Z","2026-10-15T01:00:00Z");
            t=f.service.detail("me",t.id()).item();f.transition(t,"complete");
            f.projects.delete("p");
            var review=f.service.review("me","2026-10","UTC","p");
            assertEquals(1,review.completedCount()); assertEquals(3_600_000,review.spentMillis());
            assertEquals("我的助手",review.closed().get(0).projectName());
            assertEquals("deleted",review.closed().get(0).projectStatus());
            assertEquals(0,f.service.review("me","2026-10","UTC","unassigned").completedCount());
        }
    }

    @Test void midnightMonthAndDaylightSavingUseRealZonedBoundaries() {
        try(var f=new Fixture()) {
            var t=f.create("me","跨天投入");
            f.log(t,"2026-09-30T15:30:00Z","2026-09-30T16:30:00Z"); // Shanghai Sep30 23:30 -> Oct1 00:30
            assertEquals(1_800_000,f.service.review("me","2026-09","Asia/Shanghai",null).spentMillis());
            var oct=f.service.review("me","2026-10","Asia/Shanghai",null);
            assertEquals(1_800_000,oct.spentMillis()); assertEquals(1,oct.activeDays());
            f.log(t,"2026-03-08T06:30:00Z","2026-03-08T07:30:00Z"); // New York DST skips 02:00
            assertEquals(3_600_000,f.service.review("me","2026-03","America/New_York",null).spentMillis());
            f.log(t,"2026-10-15T15:30:00Z","2026-10-15T16:30:00Z");
            var today=f.service.overview("me","Asia/Shanghai",null);
            assertEquals(1,today.todayFocus().size());
            var crossing=today.todayFocus().get(0);
            assertEquals(1_800_000,Math.min(today.dayEnd(),crossing.endedAt())-Math.max(today.dayStart(),crossing.startedAt()));
            f.clock.value=Instant.parse("2026-03-08T16:00:00Z");
            var spring=f.service.overview("me","America/New_York",null);
            assertEquals(23*3_600_000L,spring.dayEnd()-spring.dayStart());
            f.clock.value=Instant.parse("2025-11-02T16:00:00Z");
            var autumn=f.service.overview("me","America/New_York",null);
            assertEquals(25*3_600_000L,autumn.dayEnd()-autumn.dayStart());
            assertThrows(ResponseStatusException.class,()->f.service.overview("me","bad-zone",null));
        }
    }

    @Test void projectSummariesIncludeOldClosedItemsEmptyAndDeletedProjectsWithoutCountingSessionsAsItems() {
        try(var f=new Fixture()) {
            f.projects.insert("p","me","助手","software",false,"active",null);
            f.projects.insert("empty","me","尚未开始","software",false,"active",null);
            f.projects.insert("private","other","别人的项目","software",false,"active",null);
            var t=f.write(()->f.service.create("me",new ItemRequest("历史工作","","p",null,null,null)));
            f.log(t,"2026-09-15T00:00:00Z","2026-09-15T01:00:00Z");
            f.log(t,"2026-09-16T00:00:00Z","2026-09-16T00:30:00Z");
            f.transition(f.service.detail("me",t.id()).item(),"complete");
            f.jdbc.update("UPDATE personal_todo SET closed_at=? WHERE id=?",Instant.parse("2026-09-16T01:00:00Z").toEpochMilli(),t.id());
            var unassigned=f.create("me","还没有分类"); f.start("me",unassigned.id(),null); f.clock.advance(60);
            f.create("other","别人的事情");
            var summaries=f.service.projectSummaries("me");
            var p=summaries.stream().filter(s->s.id().equals("p")).findFirst().orElseThrow();
            assertEquals(1,p.completedCount()); assertEquals(0,p.openCount()); assertEquals(5_400_000,p.spentMillis());
            assertEquals(0,summaries.stream().filter(s->s.id().equals("empty")).findFirst().orElseThrow().openCount());
            assertFalse(summaries.stream().anyMatch(s->s.id().equals("private")));
            var u=summaries.stream().filter(s->s.id().equals("unassigned")).findFirst().orElseThrow();
            assertEquals(1,u.openCount()); assertEquals(1,u.doingCount()); assertEquals(60_000,u.spentMillis());
            f.projects.delete("p");
            var deleted=f.service.projectSummaries("me").stream().filter(s->s.id().equals("p")).findFirst().orElseThrow();
            assertEquals("deleted",deleted.status()); assertEquals("助手",deleted.name()); assertEquals(1,deleted.completedCount());
        }
    }

    @Test void dayWorkIsOwnerAndProjectScopedAndKeepsEveryRealHourIncludingDst() {
        try(var f=new Fixture()) {
            var t=f.create("me","跨午夜工作");
            f.log(t,"2026-10-15T15:30:00Z","2026-10-15T16:30:00Z");
            var day=f.service.day("me",null,"Asia/Shanghai",null);
            assertEquals("2026-10-16",day.day()); assertEquals(24,day.hours().size());
            assertTrue(day.hours().get(23).label().endsWith("24:00"));
            assertEquals(1,day.focus().size());
            assertEquals(t.id(),day.items().getFirst().id());
            assertEquals(1,f.service.day("me","2026-10-15","Asia/Shanghai",null).focus().size());
            assertTrue(f.service.day("other",null,"Asia/Shanghai",null).focus().isEmpty());
            assertTrue(f.service.day("other",null,"Asia/Shanghai",null).items().isEmpty());
            assertTrue(f.service.day("me",null,"Asia/Shanghai","other-project").focus().isEmpty());
            assertTrue(f.service.day("me",null,"Asia/Shanghai","other-project").items().isEmpty());
            assertThrows(ResponseStatusException.class,()->f.service.day("me","2026-10-17","Asia/Shanghai",null));
            assertThrows(ResponseStatusException.class,()->f.service.day("me","2026-02-30","UTC",null));
            for(String date:java.util.List.of("2026-03-08","2025-11-02")) {
                var dst=f.service.day("me",date,"America/New_York",null);
                assertEquals(date.equals("2026-03-08")?23:25,dst.hours().size());
                assertEquals(dst.rangeStart(),dst.hours().getFirst().startedAt());
                assertEquals(dst.rangeEnd(),dst.hours().getLast().endedAt());
                for(int i=1;i<dst.hours().size();i++) assertEquals(dst.hours().get(i-1).endedAt(),dst.hours().get(i).startedAt());
            }
        }
    }

    @Test void historicalDayIncludesCompletionWithoutInferringAnyEffort() {
        try(var f=new Fixture()) {
            var t=f.create("me","不计时也能完成");
            f.transition(t,"complete");
            f.clock.advance(86400);
            var yesterday=f.service.day("me","2026-10-16","Asia/Shanghai",null);
            assertTrue(yesterday.focus().isEmpty());
            assertEquals(t.id(),yesterday.items().getFirst().id());
            assertEquals(0,yesterday.items().getFirst().spentMillis());
            assertTrue(f.service.day("me",null,"Asia/Shanghai",null).items().isEmpty());
        }
    }

    @Test void optimisticEditsAndNotesDoNotOverwriteAnotherWindow() {
        try(var f=new Fixture()) {
            var t=f.create("me","想法");
            var updated=f.write(()->f.service.replace("me",t.id(),new ItemRequest("准备做","",null,"2026-10-16",null,t.version())));
            assertEquals("ready",updated.state());
            assertThrows(ResponseStatusException.class,()->f.write(()->f.service.replace("me",t.id(),new ItemRequest("旧窗口","",null,null,null,t.version()))));
            var note=f.write(()->f.service.saveNote("me","2026-10-16","UTC",new NoteRequest("今天留下了什么",-1L)));
            assertEquals(0,note.version());
            assertThrows(ResponseStatusException.class,()->f.write(()->f.service.saveNote("me","2026-10-16","UTC",new NoteRequest("过时内容",-1L))));
            assertEquals("今天留下了什么",f.service.overview("me","UTC",null).dayNote().body());
            new ResourceDatabasePopulator(new ClassPathResource("todo/schema.sql")).execute(f.ds);
            assertEquals("准备做",f.service.detail("me",t.id()).item().title());
        }
    }

    @Test void concurrentWindowsCannotCreateTwoRunningTimers(@TempDir Path directory) throws Exception {
        var config=new org.sqlite.SQLiteConfig(); config.setBusyTimeout(5000);
        var ds=new org.sqlite.SQLiteDataSource(config); ds.setUrl("jdbc:sqlite:"+directory.resolve("concurrency.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),new ClassPathResource("coding/schema.sql"),new ClassPathResource("todo/schema.sql")).execute(ds);
        var jdbc=new JdbcTemplate(ds); var clock=new Time();
        var service=new TodoService(new TodoStore(jdbc),new ProjectStore(jdbc),clock);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var a=tx.execute(s->service.create("me",new ItemRequest("A","",null,null,null,null)));
        var b=tx.execute(s->service.create("me",new ItemRequest("B","",null,null,null,null)));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> left=()->{
                gate.await();
                try { tx.execute(s->service.start("me",a.id(),new StartRequest(null))); return 200; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            java.util.concurrent.Callable<Integer> right=()->{
                gate.await();
                try { tx.execute(s->service.start("me",b.id(),new StartRequest(null))); return 200; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            var first=pool.submit(left); var second=pool.submit(right); gate.countDown();
            assertEquals(java.util.Set.of(200,409),java.util.Set.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS)));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM personal_focus WHERE ended_at IS NULL",Integer.class));
        }
    }
}
