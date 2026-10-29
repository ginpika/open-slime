package cc.ginpika.minis.project;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Project-scoped execution authority backed by the current dispatch, not by model-provided IDs or permissions. */
@Repository
public class ProjectWorkExecution {
    public record Assignment(String session,String repository,String todo,String executor,String reviewer,String phase,int version) {
        public boolean executing(String peer){return "executing".equals(phase) && executor.equals(peer);}
        public boolean checking(String peer){return "checking".equals(phase) && reviewer.equals(peer) && !executor.equals(peer);}
    }
    public record Link(String run,String session,String todo,String executor,int version,String reviewer,String reviewedHash,String reviewEvidence,Integer reviewVersion) {}
    private final JdbcTemplate jdbc;
    public ProjectWorkExecution(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Assignment> assignment(String owner,String session) {
        var candidates=assignments(owner,session);
        String todo=ProjectWorkScope.get();
        if(todo!=null)return candidates.stream().filter(a->todo.equals(a.todo())).findFirst();
        String peer=cc.ginpika.minis.ai.core.AgentScope.get();
        var matching=candidates.stream().filter(a->a.executing(peer)||a.checking(peer)).toList();
        return matching.size()==1?Optional.of(matching.getFirst()):candidates.size()==1?Optional.of(candidates.getFirst()):Optional.empty();
    }
    private List<Assignment> assignments(String owner,String session) {
        return jdbc.query("SELECT c.*,w.repository_id FROM collaboration_assignment c JOIN collaboration_plan plan ON plan.session_id=c.session_id AND plan.version=c.version "
                        +"JOIN hub_session s ON s.id=c.session_id "
                        +"JOIN project_workspace w ON w.project_id=s.project_id JOIN coding_repository r ON r.id=w.repository_id "
                        +"JOIN roundtable_todo t ON t.id=c.todo_id AND t.session_id=c.session_id "
                        +"LEFT JOIN roundtable_progress p ON p.session_id=c.session_id WHERE c.session_id=? AND r.owner_id=? "
                        +"AND plan.phase IN ('executing','checking') AND COALESCE(p.status,'active')='active' AND (s.member_ids IS NULL OR ("
                        +"EXISTS(SELECT 1 FROM json_each(s.member_ids) WHERE value=c.executor_id) AND "
                        +"EXISTS(SELECT 1 FROM json_each(s.member_ids) WHERE value=c.reviewer_id))) "
                        +"AND ((c.phase='executing' AND t.status='doing') OR (c.phase='checking' AND t.status='checking'))",
                (r,n)->new Assignment(session,r.getString("repository_id"),r.getString("todo_id"),r.getString("executor_id"),
                        r.getString("reviewer_id"),r.getString("phase"),r.getInt("version")),session,owner);
    }
    public Assignment executor(String owner,String session,String peer) {
        return assignment(owner,session).filter(a->a.executing(peer)).orElseThrow(()->new IllegalArgumentException("仅当前有效派工的执行者可在已绑定项目中修改并执行命令；主控应先派工，议题不能实施。"));
    }
    public Assignment reviewer(String owner,String session,String peer,String run) {
        var link=link(run).filter(l->l.session().equals(session) && (ProjectWorkScope.get()==null || ProjectWorkScope.get().equals(l.todo())))
                .orElseThrow(()->new IllegalArgumentException("编码副本不属于当前待办。"));
        // A controller can explicitly redispatch review of a preserved artifact without rerunning its author.
        // Mutable execution still requires the exact assignment version in assertLive/continueRun.
        var assignment=assignments(owner,session).stream().filter(a->a.todo().equals(link.todo()) && a.checking(peer))
                .findFirst().orElseThrow(()->new IllegalArgumentException("仅本次派工的另一位检查者可复核并应用成果。"));
        requireRepository(run,assignment.repository());
        if(peer.equals(link.executor()))throw new IllegalArgumentException("不能独立检查自己创作的编码副本。");
        return assignment;
    }
    public Optional<Link> link(String run) {
        return jdbc.query("SELECT * FROM collaboration_coding_run WHERE run_id=?",(r,n)->new Link(run,r.getString("session_id"),r.getString("todo_id"),
                r.getString("executor_id"),r.getInt("assignment_version"),r.getString("reviewer_id"),r.getString("reviewed_hash"),r.getString("review_evidence"),
                (Integer)r.getObject("review_version")),run).stream().findFirst();
    }
    @org.springframework.transaction.annotation.Transactional
    public void attach(Assignment assignment,String run) {
        jdbc.update("INSERT INTO collaboration_coding_run(run_id,session_id,todo_id,executor_id,assignment_version) VALUES(?,?,?,?,?)",
                run,assignment.session(),assignment.todo(),assignment.executor(),assignment.version());
        jdbc.update("INSERT INTO coding_validation_command(run_id,authority) VALUES(?,'collaboration')",run);
    }
    public List<String> runs(String session,String todo) {
        // Old copies stay in history, but must not block a new copy or count as evidence after a directory move.
        return jdbc.query("SELECT l.run_id FROM collaboration_coding_run l JOIN coding_run r ON r.id=l.run_id "
                        +"JOIN hub_session s ON s.id=l.session_id JOIN project_workspace w ON w.project_id=s.project_id AND w.repository_id=r.repository_id "
                        +"WHERE l.session_id=? AND l.todo_id=? ORDER BY r.created_at",
                (r,n)->r.getString(1),session,todo);
    }
    private void requireRepository(String run,String repository) {
        Boolean matches=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM coding_run WHERE id=? AND repository_id=?)",Boolean.class,run,repository);
        if(!Boolean.TRUE.equals(matches))throw new IllegalArgumentException("编码副本属于此前的工作目录，请在当前绑定目录创建新副本；旧副本仅保留为历史记录。");
    }
    public void assertLive(String run) {
        link(run).ifPresent(link->{
            Integer count=jdbc.queryForObject("SELECT count(*) FROM collaboration_assignment c JOIN collaboration_plan plan ON plan.session_id=c.session_id AND plan.version=c.version "
                            +"JOIN roundtable_todo t ON t.id=c.todo_id AND t.session_id=c.session_id "
                            +"LEFT JOIN roundtable_progress p ON p.session_id=c.session_id JOIN project_workspace w ON w.project_id=(SELECT project_id FROM hub_session WHERE id=c.session_id) "
                            +"JOIN hub_session s ON s.id=c.session_id JOIN coding_run r ON r.id=? WHERE c.session_id=? AND c.todo_id=? AND c.executor_id=? AND c.version=? "
                            +"AND (s.member_ids IS NULL OR (EXISTS(SELECT 1 FROM json_each(s.member_ids) WHERE value=c.executor_id) "
                            +"AND EXISTS(SELECT 1 FROM json_each(s.member_ids) WHERE value=c.reviewer_id))) "
                            +"AND c.phase='executing' AND plan.phase IN ('executing','checking') AND t.status='doing' AND COALESCE(p.status,'active')='active' AND r.repository_id=w.repository_id",
                    Integer.class,run,link.session(),link.todo(),link.executor(),link.version());
            if(count==null || count!=1)throw new CancellationException("协作派工、项目目录或继续状态已改变，编码任务已停止。");
        });
    }
    public void continueRun(String owner,String session,String peer,String run) {
        var assignment=executor(owner,session,peer);
        var old=link(run).orElseThrow(()->new IllegalArgumentException("历史编程任务没有本待办的执行授权，请创建绑定当前派工的任务。"));
        if(!old.session().equals(session) || !old.todo().equals(assignment.todo()) || !old.executor().equals(peer))throw new IllegalArgumentException("不能续跑另一成员或另一待办的编码副本。");
        requireRepository(run,assignment.repository());
        jdbc.update("UPDATE collaboration_coding_run SET assignment_version=?,reviewer_id=NULL,reviewed_hash=NULL,review_evidence=NULL,review_version=NULL WHERE run_id=?",assignment.version(),run);
    }
    public void reviewed(String run,Assignment assignment,String peer,String hash,String evidence) {
        jdbc.update("UPDATE collaboration_coding_run SET reviewer_id=?,reviewed_hash=?,review_evidence=?,review_version=? WHERE run_id=?",
                peer,hash,evidence,assignment.version(),run);
    }
}
