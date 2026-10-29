package cc.ginpika.minis.todo;

import static cc.ginpika.minis.todo.TodoDtos.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository("personalTodoStore")
public class TodoStore {
    private final JdbcTemplate jdbc;
    public TodoStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** First statement in every mutation: acquire SQLite's writer lock before reading state. */
    void lock(String owner) {
        jdbc.update("INSERT INTO personal_todo_owner(owner_id) VALUES(?) ON CONFLICT(owner_id) "
                + "DO UPDATE SET revision=personal_todo_owner.revision+1", owner);
    }

    private static final String ITEM_SELECT = """
            SELECT t.*, COALESCE(p.name,t.project_name) resolved_project_name,
              CASE WHEN t.project_id IS NULL THEN NULL ELSE COALESCE(p.status,'deleted') END project_status,
              (SELECT MIN(f.started_at) FROM personal_focus f WHERE f.todo_id=t.id) first_started,
              COALESCE((SELECT SUM(MAX(0,COALESCE(f.ended_at,?)-f.started_at)) FROM personal_focus f WHERE f.todo_id=t.id),0) spent
            FROM personal_todo t LEFT JOIN project p ON p.id=t.project_id WHERE t.owner_id=?
            """;

    Optional<Item> find(String owner, String id, long now) {
        return jdbc.query(ITEM_SELECT + " AND t.id=?", (r,n) -> item(r), now, owner, id).stream().findFirst();
    }

    List<Item> current(String owner, long from, long to, String project, long now) {
        List<Object> args = new ArrayList<>(List.of(now,owner,from,to));
        String filter = filter("t", project, args);
        return jdbc.query(ITEM_SELECT + " AND (t.state NOT IN ('done','dropped') OR (t.closed_at>=? AND t.closed_at<?))"
                + filter + " ORDER BY t.created_at DESC,t.id", (r,n) -> item(r), args.toArray());
    }

    List<Item> closed(String owner, long from, long to, String project, long now) {
        List<Object> args = new ArrayList<>(List.of(now,owner,from,to));
        return jdbc.query(ITEM_SELECT + " AND t.state IN ('done','dropped') AND t.closed_at>=? AND t.closed_at<?"
                + filter("t",project,args) + " ORDER BY t.closed_at DESC,t.id", (r,n) -> item(r), args.toArray());
    }

    List<Item> dayItems(String owner, long from, long to, String project, long now) {
        List<Object> args = new ArrayList<>(List.of(now,owner,from,to,to,now,from));
        return jdbc.query(ITEM_SELECT + """
                AND ((t.state IN ('done','dropped') AND t.closed_at>=? AND t.closed_at<?)
                  OR EXISTS(SELECT 1 FROM personal_focus f WHERE f.todo_id=t.id AND f.owner_id=t.owner_id
                    AND f.started_at<? AND COALESCE(f.ended_at,?)>?))
                """ + filter("t",project,args) + " ORDER BY t.created_at,t.id",(r,n)->item(r),args.toArray());
    }

    static String filter(String alias, String project, List<Object> args) {
        if (project == null || project.isBlank()) return "";
        if ("unassigned".equals(project)) return " AND " + alias + ".project_id IS NULL";
        args.add(project);
        return " AND " + alias + ".project_id=?";
    }

    void insert(String owner, String id, ItemRequest req, String projectName, String state, long now) {
        jdbc.update("INSERT INTO personal_todo(id,owner_id,title,note,project_id,project_name,state,planned_on,due_at,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", id,owner,req.title(),req.note(),req.projectId(),projectName,state,req.plannedOn(),req.dueAt(),now,now);
    }

    int replace(String owner, String id, ItemRequest req, String projectName, String state, long now) {
        return jdbc.update("UPDATE personal_todo SET title=?,note=?,project_id=?,project_name=?,state=?,planned_on=?,due_at=?,"
                + "updated_at=?,version=version+1 WHERE owner_id=? AND id=? AND version=?",
                req.title(),req.note(),req.projectId(),projectName,state,req.plannedOn(),req.dueAt(),now,owner,id,req.version());
    }

    void state(String owner, Item t, String state, long now) {
        boolean done = "done".equals(state), closed = done || "dropped".equals(state);
        jdbc.update("UPDATE personal_todo SET state=?,completed_at=?,closed_at=?,updated_at=?,version=version+1 WHERE owner_id=? AND id=?",
                state,done ? now : null,closed ? now : null,now,owner,t.id());
    }

    void event(String owner, String todo, String kind, String detail, long at) {
        jdbc.update("INSERT INTO personal_todo_event(owner_id,todo_id,kind,detail,occurred_at) VALUES(?,?,?,?,?)",owner,todo,kind,detail,at);
    }

    List<Event> events(String owner, String todo) {
        return jdbc.query("SELECT * FROM personal_todo_event WHERE owner_id=? AND todo_id=? ORDER BY occurred_at,id",
                (r,n)->new Event(r.getLong("id"),r.getString("kind"),r.getString("detail"),r.getLong("occurred_at")),owner,todo);
    }

    private static final String FOCUS_SELECT = """
            SELECT f.*,t.title,t.project_id,COALESCE(p.name,t.project_name) resolved_project_name
            FROM personal_focus f JOIN personal_todo t ON t.id=f.todo_id AND t.owner_id=f.owner_id
              LEFT JOIN project p ON p.id=t.project_id WHERE f.owner_id=?
            """;

    List<Focus> focus(String owner, long from, long to, String project, long now) {
        List<Object> args = new ArrayList<>(List.of(owner,to,now,from));
        return jdbc.query(FOCUS_SELECT + " AND f.started_at<? AND COALESCE(f.ended_at,?)>?"
                + filter("t",project,args) + " ORDER BY f.started_at,f.id", (r,n)->focus(r),args.toArray());
    }

    List<Focus> focusFor(String owner, String todo) {
        return jdbc.query(FOCUS_SELECT + " AND f.todo_id=? ORDER BY f.started_at,f.id",(r,n)->focus(r),owner,todo);
    }

    Optional<Focus> focusById(String owner, String id) {
        return jdbc.query(FOCUS_SELECT + " AND f.id=?",(r,n)->focus(r),owner,id).stream().findFirst();
    }

    Optional<Focus> active(String owner) {
        return jdbc.query(FOCUS_SELECT + " AND f.ended_at IS NULL",(r,n)->focus(r),owner).stream().findFirst();
    }

    boolean overlaps(String owner, String exceptId, long start, long end) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM personal_focus WHERE owner_id=? AND id<>? "
                + "AND started_at<? AND (ended_at IS NULL OR ended_at>?)",Integer.class,owner,exceptId,end,start);
        return n != null && n > 0;
    }

    void insertFocus(String owner, String id, String todo, long start, Long end, String source, String note) {
        jdbc.update("INSERT INTO personal_focus(id,owner_id,todo_id,started_at,ended_at,source,note) VALUES(?,?,?,?,?,?,?)",
                id,owner,todo,start,end,source,note);
    }

    void stop(String owner, String id, long now) {
        jdbc.update("UPDATE personal_focus SET ended_at=?,version=version+1 WHERE owner_id=? AND id=? AND ended_at IS NULL",now,owner,id);
    }

    void editFocus(String owner, String id, FocusRequest req) {
        jdbc.update("UPDATE personal_focus SET started_at=?,ended_at=?,note=?,version=version+1 WHERE owner_id=? AND id=?",
                req.startedAt(),req.endedAt(),req.note(),owner,id);
    }

    void deleteFocus(String owner, String id) { jdbc.update("DELETE FROM personal_focus WHERE owner_id=? AND id=?",owner,id); }

    List<ProjectTag> projects(String owner) {
        return jdbc.query("""
                SELECT p.id,p.name,p.status FROM project p WHERE p.user_id IS NULL OR p.user_id=?
                UNION ALL SELECT t.project_id,MAX(t.project_name),'deleted' FROM personal_todo t
                  WHERE t.owner_id=? AND t.project_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM project p WHERE p.id=t.project_id)
                GROUP BY t.project_id
                ORDER BY name,id
                """,(r,n)->new ProjectTag(r.getString(1),r.getString(2),r.getString(3)),owner,owner);
    }

    DayNote note(String owner, String day) {
        return jdbc.query("SELECT body,version FROM personal_day_note WHERE owner_id=? AND day=?",
                (r,n)->new DayNote(day,r.getString(1),r.getLong(2)),owner,day).stream().findFirst().orElse(new DayNote(day,"",-1));
    }

    List<ProjectSummary> projectSummaries(String owner, long now) {
        var totals = jdbc.query("""
                SELECT t.project_id,
                  SUM(CASE WHEN t.state NOT IN ('done','dropped') THEN 1 ELSE 0 END) open_count,
                  SUM(CASE WHEN t.state='doing' THEN 1 ELSE 0 END) doing_count,
                  SUM(CASE WHEN t.state='done' THEN 1 ELSE 0 END) completed_count,
                  SUM(CASE WHEN t.state='dropped' THEN 1 ELSE 0 END) dropped_count,
                  SUM(COALESCE(f.spent,0)) spent
                FROM personal_todo t LEFT JOIN (
                  SELECT todo_id,SUM(MAX(0,COALESCE(ended_at,?)-started_at)) spent
                  FROM personal_focus WHERE owner_id=? GROUP BY todo_id
                ) f ON f.todo_id=t.id WHERE t.owner_id=? GROUP BY t.project_id
                """, (r,n)->new ProjectSummary(r.getString(1),"","",r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6)),now,owner,owner);
        List<ProjectTag> tags = new ArrayList<>(projects(owner));
        tags.add(new ProjectTag("unassigned","未分类","active"));
        var byProject = new java.util.HashMap<String,ProjectSummary>();
        totals.forEach(t->byProject.put(t.id(),t));
        return tags.stream().map(tag->{
            ProjectSummary t=byProject.get("unassigned".equals(tag.id())?null:tag.id());
            return new ProjectSummary(tag.id(),tag.name(),tag.status(),t==null?0:t.openCount(),t==null?0:t.doingCount(),
                    t==null?0:t.completedCount(),t==null?0:t.droppedCount(),t==null?0:t.spentMillis());
        }).toList();
    }

    void note(String owner, String day, String body, long now) {
        jdbc.update("INSERT INTO personal_day_note(owner_id,day,body,updated_at) VALUES(?,?,?,?) "
                + "ON CONFLICT(owner_id,day) DO UPDATE SET body=excluded.body,updated_at=excluded.updated_at,version=personal_day_note.version+1",
                owner,day,body,now);
    }

    List<DayNote> notes(String owner, String start, String end) {
        return jdbc.query("SELECT day,body,version FROM personal_day_note WHERE owner_id=? AND day>=? AND day<? AND body<>'' ORDER BY day DESC",
                (r,n)->new DayNote(r.getString(1),r.getString(2),r.getLong(3)),owner,start,end);
    }

    Optional<Footprint> firstCompletion(String owner) {
        return jdbc.query("SELECT e.todo_id,t.title,e.occurred_at FROM personal_todo_event e JOIN personal_todo t ON t.id=e.todo_id "
                + "WHERE e.owner_id=? AND e.kind='completed' ORDER BY e.occurred_at,e.id LIMIT 1",
                (r,n)->new Footprint(r.getString(1),"第一次，把想法做成","完成了「"+r.getString(2)+"」",r.getLong(3)),owner).stream().findFirst();
    }

    private static Long nullable(ResultSet r, String key) throws SQLException {
        Object value = r.getObject(key); return value == null ? null : ((Number)value).longValue();
    }
    private static Item item(ResultSet r) throws SQLException {
        return new Item(r.getString("id"),r.getString("title"),r.getString("note"),r.getString("project_id"),r.getString("resolved_project_name"),
                r.getString("project_status"),r.getString("state"),r.getString("planned_on"),nullable(r,"due_at"),r.getLong("created_at"),
                r.getLong("updated_at"),nullable(r,"first_started"),nullable(r,"completed_at"),nullable(r,"closed_at"),r.getLong("version"),r.getLong("spent"));
    }
    private static Focus focus(ResultSet r) throws SQLException {
        return new Focus(r.getString("id"),r.getString("todo_id"),r.getString("title"),r.getString("project_id"),r.getString("resolved_project_name"),
                r.getLong("started_at"),nullable(r,"ended_at"),r.getString("source"),r.getString("note"),r.getLong("version"));
    }
}
