package cc.ginpika.minis.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cc.ginpika.minis.ai.knowledge.web.DeepSeekWebClient;
import cc.ginpika.minis.config.DeepSeekWebSettings;
import cc.ginpika.minis.config.store.DeepSeekWebStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeepSeekWebControllerTest {
    @Test void testsUseDraftOrPreservedSecretWithoutSavingOrRequiringSceneSwitches() {
        var store = mock(DeepSeekWebStore.class); var settings = new DeepSeekWebSettings(store);
        var client = mock(DeepSeekWebClient.class); var controller = new DeepSeekWebController(settings, client);
        var config = new DeepSeekWebSettings.Config("https://api.deepseek.com", "fixture", false, false, false, 5, 2);
        when(store.read()).thenReturn(Optional.of(config));
        when(client.search(config, "hello", 1, false)).thenReturn(List.of());
        when(client.fetch(config, "https://example.com")).thenReturn(new DeepSeekWebClient.Page("title", "https://example.com", "body"));
        var draft = new DeepSeekWebSettings.Draft(config.baseUrl(), null, false, false, false, 5, 2);
        var request = new DeepSeekWebController.TestRequest(draft, "hello", "https://example.com");
        assertTrue(controller.test("search", request).ok()); assertTrue(controller.test("fetch", request).ok());
        verify(store, never()).save(any());
        assertFalse(controller.test("other", request).ok());
    }
}
