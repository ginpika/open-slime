package cc.ginpika.minis.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class GraphMigrationTest {
    @TempDir Path temp;
    @Test void applicationStartupBacksUpAndUpgradesLegacyDatabaseWithoutChangingClaimIds() throws Exception {
        Path file=temp.resolve("legacy.db");String url="jdbc:sqlite:"+file;
        var ds=new SingleConnectionDataSource(url,true);
        String schema=new ClassPathResource("schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        ScriptUtils.executeSqlScript(ds.getConnection(),new ByteArrayResource(schema.replaceAll("\\bgraph_claim\\b","memory_item").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var jdbc=new JdbcTemplate(ds);
        jdbc.update("UPDATE _schema SET version=4");
        jdbc.update("INSERT INTO memory_item(id,scope,title,body,valid_from,created_at,updated_at,owner,domain,layer,agent_id,status,version,identity) VALUES(42,'method','old','old reliable memory',1,1,1,'AGENT','AGENT','L2','a','active',0,'REAL')");
        jdbc.update("INSERT INTO entities(id,type,name,created_at) VALUES('old-a','CONCEPT','old A',1),('old-b','CONCEPT','old B',1)");
        jdbc.update("INSERT INTO relations(id,subject_id,predicate,object_id) VALUES('old-r','old-a','related','old-b')");
        ds.destroy();
        var runner=new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,JdbcTemplateAutoConfiguration.class,DataSourceTransactionManagerAutoConfiguration.class,TransactionAutoConfiguration.class,SqlInitializationAutoConfiguration.class))
                .withUserConfiguration(GraphDatabaseInitialization.class,SchemaColumnPatches.class,SchemaIndexes.class,SchemaMigrations.class)
                .withPropertyValues("spring.datasource.url="+url,"spring.datasource.driver-class-name=org.sqlite.JDBC","spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:schema.sql");
        runner.run(context->{
            assertNull(context.getStartupFailure());
            var db=new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            assertEquals("old reliable memory",db.queryForObject("SELECT body FROM graph_claim WHERE id=42",String.class));
            assertEquals(6,db.queryForObject("SELECT version FROM _schema",Integer.class));
            assertEquals("conflicted",db.queryForObject("SELECT status FROM graph_claim WHERE id=(SELECT claim_id FROM graph_relation WHERE id='old-r')",String.class));
            assertTrue(db.queryForList("PRAGMA foreign_key_check").isEmpty());
            assertEquals(1,db.queryForObject("SELECT count(*) FROM graph_claim_fts WHERE graph_claim_fts MATCH 'reliable'",Integer.class));
        });
        assertTrue(Files.list(temp.resolve("backups")).anyMatch(p->p.getFileName().toString().startsWith("before-graph-")));
        runner.run(context->assertNull(context.getStartupFailure()));
    }

    @Test void failedGraphMigrationRollsBackCanonicalChangesAndVersion() throws Exception {
        var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            ScriptUtils.executeSqlScript(ds.getConnection(),new ClassPathResource("schema.sql"));var jdbc=new JdbcTemplate(ds);
            jdbc.update("UPDATE _schema SET version=4");
            jdbc.update("INSERT INTO entities(id,type,name,created_at) VALUES('x','CONCEPT','broken legacy edge',0)");
            jdbc.execute("PRAGMA foreign_keys=OFF");
            jdbc.update("INSERT INTO relations(id,subject_id,predicate,object_id) VALUES('bad','x','bad','missing')");
            jdbc.execute("PRAGMA foreign_keys=ON");
            var migration=new SchemaMigrations(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),"jdbc:sqlite::memory:");
            assertThrows(RuntimeException.class,migration::apply);
            assertEquals(4,jdbc.queryForObject("SELECT version FROM _schema",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM graph_claim",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM relations",Integer.class));
        } finally {ds.destroy();}
    }
}
