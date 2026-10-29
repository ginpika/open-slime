package cc.ginpika.minis.agent.roundtable;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Explicit pause state and observational counters; counts never stop authorized work. */
@Repository
public class RoundtableProgress {
    private final JdbcTemplate jdbc;

    public RoundtableProgress(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record State(String status, int steps, String reason) {}

    public State state(String session) {
        return jdbc.query("SELECT status,steps,reason FROM roundtable_progress WHERE session_id=?",
                (rs, n) -> new State(rs.getString(1), rs.getInt(2), rs.getString(3)), session)
                .stream().findFirst().orElse(new State("active", 0, null));
    }

    public boolean allowed(String session) { return "active".equals(state(session).status()); }

    /** A fresh user instruction explicitly resumes continuation. */
    public void resume(String session) {
        jdbc.update("INSERT INTO roundtable_progress(session_id,status,steps) VALUES(?,'active',0) "
                + "ON CONFLICT(session_id) DO UPDATE SET status='active',steps=0,reason=NULL", session);
    }

    public void pause(String session, String reason) {
        jdbc.update("INSERT INTO roundtable_progress(session_id,status,steps,reason) VALUES(?,'paused',0,?) "
                + "ON CONFLICT(session_id) DO UPDATE SET status='paused',reason=excluded.reason", session, reason);
    }

    public boolean step(String session) {
        jdbc.update("INSERT OR IGNORE INTO roundtable_progress(session_id,status,steps) VALUES(?,'active',0)", session);
        return jdbc.update("UPDATE roundtable_progress SET steps=steps+1 WHERE session_id=? AND status='active'",session)==1;
    }

    public boolean attempt(String todo) {
        jdbc.update("INSERT OR IGNORE INTO roundtable_attempt(todo_id,attempts) VALUES(?,0)", todo);
        return jdbc.update("UPDATE roundtable_attempt SET attempts=attempts+1 WHERE todo_id=?",todo)==1;
    }

    public void retry(String todo) { jdbc.update("DELETE FROM roundtable_attempt WHERE todo_id=?", todo); }
}
