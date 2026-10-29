package cc.ginpika.minis.agent.roundtable;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Never replay a possibly effectful execution silently after a process restart. */
@Component
public class RoundtableRecovery {
    private final JdbcTemplate jdbc;
    private final RoundtableProgress progress;
    private final RoundtableMessageStore messages;
    public RoundtableRecovery(JdbcTemplate jdbc, RoundtableProgress progress, RoundtableMessageStore messages) {
        this.jdbc = jdbc; this.progress = progress; this.messages = messages;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover() {
        var sessions = jdbc.queryForList("SELECT DISTINCT session_id FROM roundtable_todo WHERE status IN ('doing','checking') "
                + "UNION SELECT p.session_id FROM roundtable_progress p WHERE p.status='active' AND NOT EXISTS "
                + "(SELECT 1 FROM collaboration_plan c WHERE c.session_id=p.session_id AND c.phase='completed') "
                + "UNION SELECT session_id FROM collaboration_plan WHERE phase IN ('executing','checking')", String.class);
        for (String session : sessions) {
            jdbc.update("UPDATE roundtable_todo SET status='blocked',block_reason=?,updated_at=? "
                    + "WHERE session_id=? AND status IN ('doing','checking')", "进程已重启，请检查已有产出后决定是否重试",
                    System.currentTimeMillis(), session);
            jdbc.update("UPDATE roundtable_work_run SET status='blocked',error=?,finished_at=? "
                    + "WHERE session_id=? AND status='running'", "进程重启，执行中断", System.currentTimeMillis(), session);
            progress.pause(session, "进程重启，已暂停自动推进");
            jdbc.update("DELETE FROM collaboration_assignment WHERE session_id=?",session);
            jdbc.update("UPDATE collaboration_plan SET version=version+1,phase='planning',todo_id=NULL,executor_id=NULL,reviewer_id=NULL "
                    + "WHERE session_id=? AND phase<>'completed'", session);
            messages.insertNotice(session, null, "进程重启，自动推进已暂停；请检查已有产出后发言继续，受阻待办可用「立刻检查」重试。", "runtime_paused");
        }
    }
}
