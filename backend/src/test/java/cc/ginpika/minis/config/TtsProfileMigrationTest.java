package cc.ginpika.minis.config;

import cc.ginpika.minis.config.store.TtsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class TtsProfileMigrationTest {
    @Test void migratesAndDeduplicatesLegacyEndpointsWithoutChangingSelections() throws Exception {
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tts_profile(id TEXT PRIMARY KEY,config TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE tts_endpoint(id TEXT PRIMARY KEY,config TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE tts_selection(agent_id TEXT PRIMARY KEY,profile_id TEXT)");
            var store=new TtsStore(jdbc);
            var om=new ObjectMapper();
            for(String id:java.util.List.of("a","b")) {
                var p=om.createObjectNode().put("id",id).put("name",id).put("baseUrl","https://api.fish.audio/")
                    .put("apiKey","old-key").put("model","s2.1-pro").put("referenceId","voice-"+id);
                p.set("parameters",om.createObjectNode().put("temperature",0.3));
                store.save(id,om.writeValueAsString(p));
            }
            store.select("default","a"); store.select("agent:agent1","b");
            var migration=new TtsProfileMigration(store,om,new TransactionTemplate(new DataSourceTransactionManager(source)));
            migration.migrate(); migration.migrate();
            assertEquals(1,store.endpoints().size());
            assertEquals(2,store.profiles().size());
            var endpoint=om.readTree(store.endpoints().getFirst());
            assertEquals("old-key",endpoint.path("apiKey").asText());
            assertFalse(endpoint.has("model"));
            for(String json:store.profiles()) {
                var p=om.readTree(json);
                assertEquals(endpoint.path("id").asText(),p.path("endpointId").asText());
                assertFalse(p.has("apiKey"));
                assertEquals("s2.1-pro",p.path("model").asText());
                assertEquals(0.3,p.path("parameters").path("temperature").asDouble());
                assertEquals("voice-"+p.path("id").asText(),p.path("referenceId").asText());
            }
            assertEquals("a",store.selection("default"));
            assertEquals("b",store.selection("agent:agent1"));
        } finally { source.destroy(); }
    }
    @Test void copiesEndpointModelsToEveryProfileAndKeepsExistingProfileModels() throws Exception {
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
        try {
            var jdbc=new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE tts_profile(id TEXT PRIMARY KEY,config TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE tts_endpoint(id TEXT PRIMARY KEY,config TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE tts_selection(agent_id TEXT PRIMARY KEY,profile_id TEXT)");
            var store=new TtsStore(jdbc);
            var om=new ObjectMapper();
            store.saveEndpoint("api", "{\"id\":\"api\",\"name\":\"Fish\",\"baseUrl\":\"https://api.fish.audio\",\"apiKey\":\"key\",\"model\":\"s2-pro\"}");
            for(String id:java.util.List.of("a","b","c")) {
                var p=om.createObjectNode().put("id",id).put("name",id).put("endpointId","api").put("referenceId","voice-"+id);
                if(id.equals("c")) p.put("model","s2.1-pro");
                p.set("parameters",om.createObjectNode().put("temperature",0.4));
                store.save(id,om.writeValueAsString(p));
            }
            store.select("default","a"); store.select("agent:agent1","b");
            var migration=new TtsProfileMigration(store,om,new TransactionTemplate(new DataSourceTransactionManager(source)));
            migration.migrate(); migration.migrate();
            assertEquals(1,store.endpoints().size());
            assertFalse(om.readTree(store.endpoints().getFirst()).has("model"));
            for(String raw:store.profiles()) {
                var p=om.readTree(raw);
                assertEquals(p.path("id").asText().equals("c") ? "s2.1-pro" : "s2-pro",p.path("model").asText());
                assertEquals(0.4,p.path("parameters").path("temperature").asDouble());
            }
            assertEquals("a",store.selection("default"));
            assertEquals("b",store.selection("agent:agent1"));
        } finally { source.destroy(); }
    }
}
