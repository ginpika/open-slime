package cc.ginpika.minis.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class MemoryMigrationTest {
    @Test void v4PreservesUnknownHistoryAndDemotesUnconfirmedSharedFacts() throws Exception {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));
            var jdbc = new JdbcTemplate(ds);
            jdbc.update("UPDATE _schema SET version=3");
            jdbc.update("INSERT INTO memory_item(scope,body,source,valid_from,created_at,updated_at,owner,domain,layer,project_id,session_id,status,version) "
                    + "VALUES('fact','模型假设','auto',1,1,1,'PROJECT','PROJECT','L1','p','s','active',0)");
            jdbc.update("INSERT INTO memory_event(event_key,kind,actor,ref_type,ref_id,created_at) VALUES('legacy','chat','user','chat_message',1,1)");
            var migration = new SchemaMigrations(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),"jdbc:sqlite::memory:");
            migration.apply(); migration.apply();
            assertEquals(4,jdbc.queryForObject("SELECT version FROM _schema",Integer.class));
            assertEquals("L3",jdbc.queryForObject("SELECT layer FROM memory_item",String.class));
            assertNull(jdbc.queryForObject("SELECT agent_id FROM memory_event",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM memory_event",Integer.class));
        } finally { ds.destroy(); }
    }
}
