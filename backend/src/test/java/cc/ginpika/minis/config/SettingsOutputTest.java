package cc.ginpika.minis.config;

import cc.ginpika.minis.config.dto.SettingsDtos;
import cc.ginpika.minis.config.store.SettingsStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsOutputTest {
    private SettingsService service(SettingsStore store){return new SettingsService(new LlmProps(),store,null,null,null,null,null,null,null,null);}
    @Test void legacyModelRowsKeepTheirDataAndNewOutputColumnIsIdempotent(){
        var source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);var jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE llm_model(id TEXT PRIMARY KEY,name TEXT,base_url TEXT,api_key TEXT,model TEXT,context_window INTEGER,created_at INTEGER,updated_at INTEGER)");
        jdbc.update("INSERT INTO llm_model VALUES('m1','model','fixture','fixture-key','model',262144,1,1)");
        var patches=new SchemaColumnPatches(jdbc);patches.apply();patches.apply();
        var store=new SettingsStore(jdbc);var previous=store.model("m1").orElseThrow();
        assertNull(previous.maxOutputTokens());assertEquals("fixture-key",previous.apiKey());assertEquals(262144,previous.contextWindow());
        store.updateModel(new SettingsStore.ModelRow("m1","model","fixture","fixture-key","model",262144,32768));
        assertEquals(32768,store.model("m1").orElseThrow().maxOutputTokens());
        store.updateModel(previous);assertNull(store.model("m1").orElseThrow().maxOutputTokens());
        store.insertModel(new SettingsStore.ModelRow("m2","second","fixture","key","model",65536,16384));
        assertEquals(2,store.models().size());
        source.destroy();
    }
    @Test void outputLimitResolutionUsesTheSelectedModelAndStaysIndependentOfContextWindow(){
        var store=mock(SettingsStore.class);var service=service(store);
        when(store.setting()).thenReturn(Optional.of(new SettingsStore.SettingRow("m1")));
        when(store.model("m1")).thenReturn(Optional.of(new SettingsStore.ModelRow("m1","model","fixture","key","model",262144,32768)));
        when(store.model("m2")).thenReturn(Optional.of(new SettingsStore.ModelRow("m2","model","fixture","key","model",262144)));
        assertEquals(32768,service.chatOutputTokens(null));assertNull(service.chatOutputTokens("m2"));assertNull(service.chatOutputTokens("env"));
    }
    @Test void settingsCrudSavesTheOutputLimitWithoutProbingOrAcceptingInvalidValues(){
        var store=mock(SettingsStore.class);var service=service(store);
        when(store.models()).thenReturn(List.of());
        service.createModel(new SettingsDtos.ModelRequest("model","fixture","key","model",262144,32768));
        var row=ArgumentCaptor.forClass(SettingsStore.ModelRow.class);verify(store).insertModel(row.capture());
        assertEquals(32768,row.getValue().maxOutputTokens());assertEquals(262144,row.getValue().contextWindow());
        assertThrows(ResponseStatusException.class,()->service.createModel(new SettingsDtos.ModelRequest("model","fixture","key","model",262144,-1)));
    }
}
