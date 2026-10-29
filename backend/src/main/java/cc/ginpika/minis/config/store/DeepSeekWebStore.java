package cc.ginpika.minis.config.store;

import cc.ginpika.minis.config.DeepSeekWebSettings.Config;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DeepSeekWebStore {
    private final JdbcTemplate jdbc;
    public DeepSeekWebStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Config> read() {
        return jdbc.query("SELECT * FROM deepseek_web_config WHERE id=1", (rs, n) -> new Config(
                rs.getString("base_url"), rs.getString("api_key"), rs.getBoolean("fast_enabled"),
                rs.getBoolean("rag_enabled"), rs.getBoolean("fetch_enabled"), rs.getInt("result_count"),
                rs.getInt("timeout_seconds"))).stream().findFirst();
    }

    public void save(Config c) {
        jdbc.update("""
                INSERT INTO deepseek_web_config(id,base_url,api_key,fast_enabled,rag_enabled,fetch_enabled,result_count,timeout_seconds)
                VALUES(1,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                base_url=excluded.base_url,api_key=excluded.api_key,fast_enabled=excluded.fast_enabled,
                rag_enabled=excluded.rag_enabled,fetch_enabled=excluded.fetch_enabled,
                result_count=excluded.result_count,timeout_seconds=excluded.timeout_seconds
                """, c.baseUrl(), c.apiKey(), c.fastEnabled(), c.ragEnabled(), c.fetchEnabled(), c.count(), c.timeoutSeconds());
    }
}
