package cc.ginpika.minis.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

class StatsMigrationTest {
    @Test void nullableUsageColumnsAreAddedIdempotentlyWithoutChangingHistoricalTokens() {
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE token_usage(id INTEGER PRIMARY KEY,source TEXT,model TEXT,prompt_tokens INTEGER,completion_tokens INTEGER,total_tokens INTEGER,estimated INTEGER,created_at INTEGER)");
            jdbc.update("INSERT INTO token_usage VALUES(1,'chat','m',10,5,15,1,1234)");
            var patches=new SchemaColumnPatches(jdbc);patches.apply();patches.apply();
            var row=jdbc.queryForMap("SELECT * FROM token_usage");
            assertEquals(15,((Number)row.get("total_tokens")).intValue()); assertEquals(1,((Number)row.get("estimated")).intValue());
            assertNull(row.get("provider"));assertNull(row.get("quality"));assertNull(row.get("status"));
            assertNull(row.get("cached_tokens"));assertNull(row.get("reasoning_tokens"));
        } finally {source.destroy();}
    }
}

