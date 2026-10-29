package cc.ginpika.minis.agent.roundtable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Project membership and issue ownership; discussion content stays in the existing session store. */
@Repository
public class CollaborationStore {
    public record Issue(String id, String parentSessionId, String sessionId, String title,
                        String status, String conclusion, long messageId, long createdAt, Long resolvedAt) {}
    private final JdbcTemplate jdbc;
    public CollaborationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Issue> issues() {
        return jdbc.query("SELECT * FROM collaboration_issue ORDER BY created_at, id", CollaborationStore::mapIssue);
    }

    public void insert(Issue issue) {
        jdbc.update("INSERT INTO collaboration_issue(id,parent_session_id,session_id,title,status,message_id,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", issue.id(), issue.parentSessionId(), issue.sessionId(),
                issue.title(), issue.status(), issue.messageId(), issue.createdAt());
    }

    public boolean resolve(String id, String conclusion) {
        return jdbc.update("UPDATE collaboration_issue SET status='resolved',conclusion=?,resolved_at=? "
                + "WHERE id=? AND status='open'", conclusion, System.currentTimeMillis(), id) == 1;
    }

    public void linkWork(String issueId, String todoId) {
        jdbc.update("INSERT INTO collaboration_issue_work(issue_id,todo_id) VALUES(?,?)", issueId, todoId);
    }

    public java.util.Optional<Issue> issueBySession(String sessionId) {
        return jdbc.query("SELECT * FROM collaboration_issue WHERE session_id=?", CollaborationStore::mapIssue, sessionId)
                .stream().findFirst();
    }

    private static Issue mapIssue(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Issue(rs.getString("id"), rs.getString("parent_session_id"), rs.getString("session_id"),
                rs.getString("title"), rs.getString("status"), rs.getString("conclusion"),
                rs.getLong("message_id"), rs.getLong("created_at"), RoundtableRows.nullableLong(rs, "resolved_at"));
    }

    public java.util.Optional<String> workOf(String issueId) {
        return jdbc.queryForList("SELECT todo_id FROM collaboration_issue_work WHERE issue_id=?", String.class, issueId)
                .stream().findFirst();
    }

    public Map<String, List<String>> projectMembers() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        jdbc.query("SELECT project_id,agent_id FROM collaboration_project_member ORDER BY project_id,position", rs -> {
            result.computeIfAbsent(rs.getString("project_id"), key -> new java.util.ArrayList<>())
                    .add(rs.getString("agent_id"));
        });
        return result;
    }

    public void members(String projectId, List<String> ids) {
        jdbc.update("DELETE FROM collaboration_project_member WHERE project_id=?", projectId);
        for (int i = 0; i < ids.size(); i++) jdbc.update(
                "INSERT INTO collaboration_project_member(project_id,agent_id,position) VALUES(?,?,?)",
                projectId, ids.get(i), i);
    }
}
