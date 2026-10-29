package cc.ginpika.minis.config;

import static org.junit.jupiter.api.Assertions.*;
import cc.ginpika.minis.config.store.DeepSeekWebStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

class DeepSeekWebSettingsTest {
    private SingleConnectionDataSource source;
    private DeepSeekWebSettings settings;
    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.execute(source); schema.execute(source);
        settings = new DeepSeekWebSettings(new DeepSeekWebStore(new JdbcTemplate(source)));
    }
    @AfterEach void close() { source.destroy(); }
    private DeepSeekWebSettings.Draft draft(String key, boolean fast, boolean rag) {
        return new DeepSeekWebSettings.Draft("https://api.deepseek.com/v1/", key, fast, rag, true, 5, 15);
    }
    @Test void defaultsAndRoundTripKeepSecretOutOfView() throws Exception {
        assertFalse(settings.view().fastEnabled()); assertFalse(settings.view().ragEnabled());
        assertFalse(settings.view().hasApiKey());
        var view = settings.save(draft("fixture-secret", true, false));
        assertEquals("https://api.deepseek.com/v1", view.baseUrl()); assertTrue(view.hasApiKey());
        String json = new ObjectMapper().writeValueAsString(view);
        assertFalse(json.contains("fixture-secret")); assertFalse(json.contains("\"apiKey\""));
        settings.save(draft(null, false, true));
        assertEquals("fixture-secret", settings.resolve().apiKey()); assertTrue(settings.resolve().ragEnabled());
        assertFalse(settings.resolve().fastEnabled());
        settings.save(draft("", false, false)); assertFalse(settings.resolve().configured());
    }
    @Test void invalidSaveDoesNotOverwriteWorkingConfiguration() {
        settings.save(draft("fixture-secret", false, false));
        assertThrows(ResponseStatusException.class, () -> settings.save(draft("", true, false)));
        assertThrows(ResponseStatusException.class, () -> settings.save(draft("bad\nkey", false, false)));
        for (String base : new String[]{"file:///tmp", "https://user:pass@example.com", "https://api.example.com?key=secret"}) {
            assertThrows(ResponseStatusException.class, () -> settings.save(new DeepSeekWebSettings.Draft(base, null, false, false, true, 5, 15)));
        }
        assertThrows(ResponseStatusException.class, () -> settings.save(new DeepSeekWebSettings.Draft("https://api.deepseek.com", null, false, false, true, 51, 15)));
        assertEquals("fixture-secret", settings.resolve().apiKey());
    }
}
