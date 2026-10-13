package cc.ginpika.minis.config;

import cc.ginpika.minis.config.store.TtsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.transaction.support.TransactionTemplate;

/** Splits legacy profiles atomically, preserving profile IDs and agent/default selections. */
@Component
@DependsOnDatabaseInitialization
public class TtsProfileMigration {
    private final TtsStore store;
    private final ObjectMapper om;
    private final TransactionTemplate tx;
    public TtsProfileMigration(TtsStore store,ObjectMapper om,TransactionTemplate tx) { this.store=store; this.om=om; this.tx=tx; }
    @PostConstruct
    public void migrate() {
        tx.executeWithoutResult(status -> {
            try {
                for(String raw:store.profiles()) {
                    ObjectNode p=(ObjectNode)om.readTree(raw);
                    if(p.hasNonNull("endpointId")) {
                        if(!p.hasNonNull("model")) {
                            String model=null;
                            for(String endpoint:store.endpoints()) {
                                var e=om.readTree(endpoint);
                                if(p.path("endpointId").asText().equals(e.path("id").asText())) { model=e.path("model").asText("s2.1-pro"); break; }
                            }
                            if(model==null) throw new IllegalStateException("TTS 档案引用的端点不存在");
                            p.put("model",model);
                            store.save(p.path("id").asText(),om.writeValueAsString(p));
                        }
                        continue;
                    }
                    String base=p.path("baseUrl").asText().strip().replaceAll("/+$", "");
                    String key=p.path("apiKey").asText().strip();
                    String endpointId=null;
                    for(String endpoint:store.endpoints()) {
                        var e=om.readTree(endpoint);
                        if(base.equals(e.path("baseUrl").asText()) && key.equals(e.path("apiKey").asText())) { endpointId=e.path("id").asText(); break; }
                    }
                    if(endpointId==null) {
                        endpointId=UUID.randomUUID().toString();
                        store.saveEndpoint(endpointId,om.writeValueAsString(new TtsService.Endpoint(endpointId,p.path("name").asText()+" API",base,key)));
                    }
                    p.put("endpointId",endpointId);
                    p.remove(java.util.List.of("baseUrl","apiKey"));
                    store.save(p.path("id").asText(),om.writeValueAsString(p));
                }
                // Only remove endpoint models after every referencing profile has copied them.
                for(String raw:store.endpoints()) {
                    ObjectNode e=(ObjectNode)om.readTree(raw);
                    if(e.has("model")) { e.remove("model"); store.saveEndpoint(e.path("id").asText(),om.writeValueAsString(e)); }
                }
            } catch(Exception e) { throw new IllegalStateException("TTS 档案迁移失败",e); }
        });
    }
}
