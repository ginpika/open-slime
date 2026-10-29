package cc.ginpika.minis.todo;

import static cc.ginpika.minis.todo.TodoDtos.*;

import cc.ginpika.minis.project.ProjectStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Personal state is authoritative here; memory and Agent runtimes cannot infer effort. */
@Service("personalTodoService")
public class TodoService {
    private final TodoStore store;
    private final ProjectStore projects;
    private final Clock clock;

    @Autowired
    public TodoService(TodoStore store, ProjectStore projects) { this(store,projects,Clock.systemUTC()); }
    TodoService(TodoStore store, ProjectStore projects, Clock clock) {
        this.store=store; this.projects=projects; this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Overview overview(String owner, String zoneName, String project) {
        ZoneId zone=zone(zoneName); long now=clock.millis(); LocalDate day=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long start=day.atStartOfDay(zone).toInstant().toEpochMilli(),end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return new Overview(day.toString(),zone.getId(),now,start,end,store.current(owner,start,end,project,now),
                store.focus(owner,start,end,project,now),store.active(owner).orElse(null),store.projects(owner),store.note(owner,day.toString()));
    }

    @Transactional(readOnly=true)
    public Detail detail(String owner, String id) {
        return new Detail(item(owner,id),store.focusFor(owner,id),store.events(owner,id));
    }

    @Transactional(readOnly=true)
    public List<ProjectSummary> projectSummaries(String owner) {
        return store.projectSummaries(owner,clock.millis());
    }

    @Transactional(readOnly=true)
    public DayWork day(String owner, String dayText, String zoneName, String project) {
        ZoneId zone=zone(zoneName); long now=clock.millis();
        LocalDate today=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate day=blank(dayText)==null?today:date(dayText);
        if(day.isAfter(today)) throw bad("不能查看尚未发生的一天");
        var start=day.atStartOfDay(zone); var end=day.plusDays(1).atStartOfDay(zone);
        List<DayHour> hours=new ArrayList<>();
        var format=java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        // Advance on the instant timeline so repeated/missing DST hours remain accurate.
        for(var cursor=start;cursor.isBefore(end);) {
            var next=cursor.plusHours(1); if(next.isAfter(end)) next=end;
            String label=cursor.format(format)+" – "+(next.equals(end)?"24:00":next.format(format));
            if(!start.getOffset().equals(end.getOffset())) label+=" ("+cursor.getOffset()+")";
            hours.add(new DayHour(label,cursor.toInstant().toEpochMilli(),next.toInstant().toEpochMilli())); cursor=next;
        }
        long from=start.toInstant().toEpochMilli(),to=end.toInstant().toEpochMilli();
        return new DayWork(day.toString(),zone.getId(),now,from,to,hours,store.focus(owner,from,to,project,now),store.dayItems(owner,from,to,project,now));
    }

    @Transactional
    public Item create(String owner, ItemRequest input) {
        store.lock(owner); ItemRequest req=validate(input); String name=project(owner,req.projectId(),null);
        String id=UUID.randomUUID().toString(); long now=clock.millis();
        store.insert(owner,id,req,name,req.plannedOn()==null ? "idea" : "ready",now);
        store.event(owner,id,"created","记下想法",now);
        if(req.plannedOn()!=null) store.event(owner,id,"planned","安排到 "+req.plannedOn(),now);
        return item(owner,id);
    }

    @Transactional
    public Item replace(String owner, String id, ItemRequest input) {
        store.lock(owner); Item t=item(owner,id); ItemRequest req=validate(input); version(req.version(),t.version());
        String name=project(owner,req.projectId(),t);
        String state=t.state();
        if("idea".equals(state)&&req.plannedOn()!=null) state="ready";
        if("ready".equals(state)&&req.plannedOn()==null) state="idea";
        long now=clock.millis();
        if(store.replace(owner,id,req,name,state,now)!=1) throw conflict("事项已更新，请刷新后重试");
        if(!Objects.equals(req.plannedOn(),t.plannedOn()))
            store.event(owner,id,"planned",req.plannedOn()==null?"暂不安排日期":"改为计划 "+req.plannedOn(),now);
        if(!Objects.equals(req.title(),t.title())||!Objects.equals(req.note(),t.note())||!Objects.equals(req.projectId(),t.projectId())||!Objects.equals(req.dueAt(),t.dueAt()))
            store.event(owner,id,"edited","更新事项资料",now);
        return item(owner,id);
    }

    @Transactional
    public Item transition(String owner, String id, TransitionRequest req) {
        store.lock(owner); Item t=item(owner,id);
        if(req==null) throw bad("请指定事项操作"); version(req.version(),t.version());
        String state; String kind; String text;
        switch(req.action()==null?"":req.action()) {
            case "complete" -> { state="done"; kind="completed"; text="完成"; }
            case "drop" -> { state="dropped"; kind="dropped"; text="主动放下，保留这段尝试"; }
            case "reopen" -> {
                if(isOpen(t)) return t;
                state=t.firstStartedAt()!=null?"doing":t.plannedOn()!=null?"ready":"idea";
                kind="reopened"; text="重新打开，原历程保留";
            }
            default -> throw bad("未知事项操作");
        }
        if(state.equals(t.state())) return t;
        long now=clock.millis();
        store.active(owner).filter(f->f.todoId().equals(id)).ifPresent(f->stopActive(owner,f,now));
        store.state(owner,t,state,now); store.event(owner,id,kind,text,now);
        return item(owner,id);
    }

    @Transactional
    public Focus start(String owner, String id, StartRequest req) {
        store.lock(owner); Item t=item(owner,id);
        if(!isOpen(t)) throw bad("请先重新打开这个事项");
        if(req==null) throw bad("请带上当前计时状态");
        Focus active=store.active(owner).orElse(null);
        if(!Objects.equals(req.expectedActiveId(),active==null?null:active.id()))
            throw conflict("计时已在其他页面变更，请刷新后重试");
        if(active!=null&&active.todoId().equals(id)) return active;
        long now=clock.millis();
        if(active!=null) stopActive(owner,active,now);
        String fid=UUID.randomUUID().toString();
        store.insertFocus(owner,fid,id,now,null,"timer","");
        if(!"doing".equals(t.state())) store.state(owner,t,"doing",now);
        store.event(owner,id,"started",t.firstStartedAt()==null?"第一次开始投入":"继续投入",now);
        return focus(owner,fid);
    }

    @Transactional
    public Focus stop(String owner, String id) {
        store.lock(owner); Focus f=focus(owner,id);
        if(f.endedAt()==null) stopActive(owner,f,clock.millis());
        return focus(owner,id);
    }

    private void stopActive(String owner, Focus f, long now) {
        if(now<f.startedAt()) throw conflict("系统时间早于计时开始，请核对系统时间");
        store.stop(owner,f.id(),now); store.event(owner,f.todoId(),"paused","结束一段投入",now);
    }

    @Transactional
    public Focus addFocus(String owner, FocusRequest input) {
        store.lock(owner); FocusRequest req=validateFocus(input); Item t=item(owner,req.todoId());
        if(store.overlaps(owner,"",req.startedAt(),req.endedAt())) throw conflict("与已有投入记录重叠，请核对时段");
        String id=UUID.randomUUID().toString(); long now=clock.millis();
        store.insertFocus(owner,id,t.id(),req.startedAt(),req.endedAt(),"manual",req.note());
        if(isOpen(t)&&!"doing".equals(t.state())) store.state(owner,t,"doing",now);
        store.event(owner,t.id(),"focus_added","补记投入："+Instant.ofEpochMilli(req.startedAt())+" 至 "+Instant.ofEpochMilli(req.endedAt()),now);
        return focus(owner,id);
    }

    @Transactional
    public Focus editFocus(String owner, String id, FocusRequest input) {
        store.lock(owner); Focus f=focus(owner,id); FocusRequest req=validateFocus(input); version(req.version(),f.version());
        if(f.endedAt()==null) throw bad("请先暂停，再修正这段投入");
        if(!f.todoId().equals(req.todoId())) throw bad("不能将已有投入移动到其他事项");
        if(store.overlaps(owner,id,req.startedAt(),req.endedAt())) throw conflict("与已有投入记录重叠，请核对时段");
        store.editFocus(owner,id,req);
        store.event(owner,f.todoId(),"focus_edited","修正投入："+Instant.ofEpochMilli(req.startedAt())+" 至 "+Instant.ofEpochMilli(req.endedAt()),clock.millis());
        return focus(owner,id);
    }

    @Transactional
    public Deleted deleteFocus(String owner, String id, long expectedVersion) {
        store.lock(owner); Focus f=focus(owner,id); version(expectedVersion,f.version());
        if(f.endedAt()==null) throw bad("请先暂停，再删除这段投入");
        store.deleteFocus(owner,id);
        store.event(owner,f.todoId(),"focus_deleted","删除一段投入："+Instant.ofEpochMilli(f.startedAt())+" 至 "+Instant.ofEpochMilli(f.endedAt()),clock.millis());
        return new Deleted(true);
    }

    @Transactional
    public DayNote saveNote(String owner, String day, String zoneName, NoteRequest req) {
        store.lock(owner); LocalDate date=date(day); ZoneId zone=zone(zoneName);
        if(date.isAfter(LocalDate.now(clock.withZone(zone)))) throw bad("不能提前记录未来一天的回顾");
        if(req==null) throw bad("请填写回顾内容");
        String body=text(req.body(),4000,"每日回顾"); version(req.version(),store.note(owner,day).version());
        store.note(owner,day,body,clock.millis()); return store.note(owner,day);
    }

    @Transactional(readOnly=true)
    public Review review(String owner, String monthText, String zoneName, String project) {
        ZoneId zone=zone(zoneName); YearMonth month;
        try { month=YearMonth.parse(monthText); }
        catch(DateTimeException|NullPointerException e) { throw bad("月份格式应为 YYYY-MM"); }
        if(month.getYear()<1970||month.getYear()>9998) throw bad("月份超出支持范围");
        long now=clock.millis(),from=month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                to=month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        List<Item> closed=store.closed(owner,from,to,project,now);
        List<Focus> focus=store.focus(owner,from,to,project,now);
        var totals=new LinkedHashMap<String,Long>(); var refs=new LinkedHashMap<String,Focus>(); var days=new HashSet<LocalDate>();
        long spent=0;
        for(Focus f:focus) {
            long start=Math.max(from,f.startedAt()),end=Math.min(to,f.endedAt()==null?now:f.endedAt());
            if(end<=start) continue;
            long duration=end-start; spent+=duration;
            totals.merge(f.todoId(),duration,Long::sum); refs.put(f.todoId(),f);
            LocalDate first=Instant.ofEpochMilli(start).atZone(zone).toLocalDate(),last=Instant.ofEpochMilli(end-1).atZone(zone).toLocalDate();
            for(LocalDate d=first;!d.isAfter(last);d=d.plusDays(1)) days.add(d);
        }
        List<Allocation> allocations=totals.entrySet().stream().map(e->{
            Focus f=refs.get(e.getKey()); return new Allocation(e.getKey(),f.title(),f.projectId(),f.projectName(),e.getValue());
        }).sorted(Comparator.comparingLong(Allocation::millis).reversed()).toList();
        List<Footprint> footprints=new ArrayList<>();
        store.firstCompletion(owner).filter(f->f.occurredAt()>=from&&f.occurredAt()<to).filter(f->{
            Item t=item(owner,f.todoId()); return matches(t,project);
        }).ifPresent(footprints::add);
        closed.stream().filter(t->"done".equals(t.state())&&t.firstStartedAt()!=null)
                .filter(t->footprints.stream().noneMatch(f->f.todoId().equals(t.id()))).findFirst()
                .ifPresent(t->footprints.add(new Footprint(t.id(),"一个想法，走到了完成",
                        "从 "+Instant.ofEpochMilli(t.createdAt()).atZone(zone).toLocalDate()+" 记下，到 "+Instant.ofEpochMilli(t.completedAt()).atZone(zone).toLocalDate()+" 完成",t.completedAt())));
        return new Review(month.toString(),now,from,to,closed.stream().filter(t->"done".equals(t.state())).count(),
                closed.stream().filter(t->"dropped".equals(t.state())).count(),spent,days.size(),allocations,closed,footprints,
                project==null||project.isBlank()?store.notes(owner,month.atDay(1).toString(),month.plusMonths(1).atDay(1).toString()):List.of());
    }

    private static boolean matches(Item t,String project) {
        return project==null||project.isBlank()||("unassigned".equals(project)?t.projectId()==null:project.equals(t.projectId()));
    }
    private Item item(String owner,String id) { return store.find(owner,id,clock.millis()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"事项不存在")); }
    private Focus focus(String owner,String id) { return store.focusById(owner,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"投入记录不存在")); }
    private static boolean isOpen(Item t) { return !List.of("done","dropped").contains(t.state()); }
    private String project(String owner, String id, Item existing) {
        if(id==null) return null;
        var found=projects.findById(id);
        if(found.isPresent()&&found.get().userId()!=null&&!found.get().userId().equals(owner)) throw bad("不能使用这个项目标签");
        if(existing!=null&&id.equals(existing.projectId())) return found.map(ProjectStore.ProjectRow::name).orElse(existing.projectName());
        var p=found.orElseThrow(()->bad("项目标签不存在"));
        if(!"active".equals(p.status())) throw bad("只能选择使用中的项目标签");
        return p.name();
    }
    private static ItemRequest validate(ItemRequest req) {
        if(req==null) throw bad("请填写事项");
        String title=text(req.title(),160,"标题").trim(); if(title.isEmpty()) throw bad("标题不能为空");
        String planned=blank(req.plannedOn()); if(planned!=null) date(planned);
        if(req.dueAt()!=null) timestamp(req.dueAt());
        return new ItemRequest(title,text(req.note(),12000,"备注"),blank(req.projectId()),planned,req.dueAt(),req.version());
    }
    private FocusRequest validateFocus(FocusRequest req) {
        if(req==null||req.startedAt()==null||req.endedAt()==null||blank(req.todoId())==null) throw bad("请填写事项及完整的开始、结束时间");
        timestamp(req.startedAt()); timestamp(req.endedAt());
        if(req.endedAt()<=req.startedAt()) throw bad("结束时间必须晚于开始时间");
        if(req.endedAt()>clock.millis()) throw bad("不能记录尚未发生的投入");
        return new FocusRequest(req.todoId(),req.startedAt(),req.endedAt(),text(req.note(),2000,"投入备注"),req.version());
    }
    private static void timestamp(long value) { if(value<0||value>253402214400000L) throw bad("时间超出支持范围"); }
    private static void version(Long expected, long actual) {
        if(expected==null) throw bad("缺少记录版本，请刷新后重试");
        if(expected!=actual) throw conflict("记录已在其他页面更新，请刷新后重试");
    }
    private static LocalDate date(String value) {
        try { LocalDate day=LocalDate.parse(value); if(day.getYear()<1970||day.getYear()>9998) throw bad("日期超出支持范围"); return day; }
        catch(DateTimeException|NullPointerException e) { throw bad("日期格式应为 YYYY-MM-DD"); }
    }
    private static ZoneId zone(String name) {
        try { return ZoneId.of(name); } catch(DateTimeException|NullPointerException e) { throw bad("无效时区"); }
    }
    private static String text(String value,int max,String label) { String s=value==null?"":value; if(s.length()>max) throw bad(label+"最多 "+max+" 字"); return s; }
    private static String blank(String s) { return s==null||s.isBlank()?null:s.trim(); }
    private static ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
    private static ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
}
