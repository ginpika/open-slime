package cc.ginpika.minis.project;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit association with an existing, validated workspace project; never infer one by name or recency. */
@Repository
public class ProjectWorkspaceStore {
    public record Binding(String projectId, String repositoryId) {}
    public record Scope(boolean constrained, String projectId, String repositoryId) {}
    private final JdbcTemplate jdbc;
    public ProjectWorkspaceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Binding> list() {
        return jdbc.query("SELECT project_id,repository_id FROM project_workspace", (r,n) -> new Binding(r.getString(1),r.getString(2)));
    }
    public Optional<Binding> find(String project) { return list().stream().filter(b -> b.projectId().equals(project)).findFirst(); }
    public void bind(String project, String repository) {
        jdbc.update("INSERT INTO project_workspace(project_id,repository_id) VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET repository_id=excluded.repository_id", project, repository);
    }
    public Scope scope(String session) {
        return jdbc.query("SELECT s.mode,s.project_id,w.repository_id,(EXISTS(SELECT 1 FROM collaboration_plan c WHERE c.session_id=s.id) "
                        + "OR EXISTS(SELECT 1 FROM collaboration_issue i WHERE i.session_id=s.id)) AS managed "
                        + "FROM hub_session s LEFT JOIN project_workspace w ON w.project_id=s.project_id WHERE s.id=?",
                (r,n) -> new Scope("multi-agent".equals(r.getString(1)) && (r.getString(2)!=null || r.getBoolean(4)), r.getString(2), r.getString(3)), session)
                .stream().findFirst().orElse(new Scope(false,null,null));
    }
}
