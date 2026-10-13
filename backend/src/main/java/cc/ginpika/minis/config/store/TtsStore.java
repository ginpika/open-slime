package cc.ginpika.minis.config.store;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TtsStore {
    private final JdbcTemplate jdbc;
    public TtsStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<String> profiles() { return jdbc.query("SELECT config FROM tts_profile ORDER BY rowid", (r, n) -> r.getString(1)); }
    public List<String> endpoints() { return jdbc.query("SELECT config FROM tts_endpoint ORDER BY rowid", (r,n) -> r.getString(1)); }
    public void saveEndpoint(String id, String json) { jdbc.update("INSERT INTO tts_endpoint VALUES(?,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config",id,json); }
    public void deleteEndpoint(String id) { jdbc.update("DELETE FROM tts_endpoint WHERE id=?",id); }
    public void save(String id, String json) { jdbc.update("INSERT INTO tts_profile VALUES(?,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config", id, json); }
    public boolean exists(String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM tts_profile WHERE id=?", Integer.class, id) > 0; }
    public void delete(String id) {
        jdbc.update("DELETE FROM tts_profile WHERE id=?", id);
        jdbc.update("DELETE FROM tts_selection WHERE profile_id=?", id);
    }
    public String selection(String agent) { return jdbc.query("SELECT profile_id FROM tts_selection WHERE agent_id=?", (r,n) -> r.getString(1), agent).stream().findFirst().orElse(null); }
    public void select(String agent, String id) {
        if (id == null || id.isBlank()) { jdbc.update("DELETE FROM tts_selection WHERE agent_id=?", agent); return; }
        jdbc.update("INSERT INTO tts_selection VALUES(?,?) ON CONFLICT(agent_id) DO UPDATE SET profile_id=excluded.profile_id", agent, id);
    }
}
