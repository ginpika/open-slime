package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Session lifecycle tests use a browser transport double; real DOM behavior is covered by WebBrowserTest. */
class WebBrowserContinuityTest {
    private WebBrowserService service;
    private Browser browser;
    private final List<Page> pages = new ArrayList<>();
    private final List<BrowserContext> contexts = new ArrayList<>();

    @BeforeEach void setUp() {
        WebUrlPolicy policy = new WebUrlPolicy() {
            @Override public String validate(String url) {
                if (url == null || !url.startsWith("https://fixture.test/"))
                    throw new IllegalArgumentException("Fixture URL only");
                return url;
            }
        };
        service = new WebBrowserService(policy, new ObjectMapper(), "", true);
        browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext(any(Browser.NewContextOptions.class))).thenAnswer(invocation -> {
            BrowserContext context = mock(BrowserContext.class);
            Page page = mock(Page.class);
            Locator body = mock(Locator.class);
            AtomicReference<String> url = new AtomicReference<>("about:blank");
            when(context.newPage()).thenReturn(page);
            when(page.url()).thenAnswer(call -> url.get());
            when(page.title()).thenReturn("Fixture title");
            when(page.locator("body")).thenReturn(body);
            when(body.innerText()).thenReturn("Fixture content");
            when(page.evaluate(anyString(), any())).thenReturn(List.of());
            when(page.screenshot(any())).thenReturn(new byte[]{1});
            when(page.navigate(anyString(), any())).thenAnswer(call -> {
                url.set(call.getArgument(0));
                return null;
            });
            pages.add(page);
            contexts.add(context);
            return context;
        });
        ReflectionTestUtils.setField(service, "browser", browser);
    }

    @AfterEach void tearDown() { service.close(); }

    @Test void repeatedOpenAndReadResumeTheSamePageWithoutReloading() {
        var first = command("owner", "chat", null, "open", "/article");
        for (int turn = 0; turn < 5; turn++) {
            assertEquals(first.browserId(), command("owner", "chat", null, "read", null).browserId());
            assertEquals(first.browserId(), command("owner", "chat", null, "open", "/article").browserId());
        }
        assertEquals(1, service.tabs("owner", "chat").size());
        assertEquals(1, service.views("human", "chat").size());
        verify(pages.getFirst(), times(1)).navigate(anyString(), any());
        var next = command("owner", "chat", null, "open", "/next");
        assertEquals(first.browserId(), next.browserId());
        assertEquals("https://fixture.test/next", next.url());
        // Explicit navigation can still reload the page when asked.
        command("owner", "chat", first.browserId(), "open", "/next");
        verify(pages.getFirst(), times(3)).navigate(anyString(), any());
        verify(browser, times(1)).newContext(any(Browser.NewContextOptions.class));
    }

    @Test void explicitNewTabAndSelectionControlTheDefaultAndListingIsPassive() {
        var first = command("owner", "chat", null, "open", "/article");
        var second = command("owner", "chat", null, "new_tab", "/article");
        assertNotEquals(first.browserId(), second.browserId());
        assertEquals(second.browserId(), command("owner", "chat", null, "read", null).browserId());
        command("owner", "chat", first.browserId(), "read", null);
        clearInvocations(pages.toArray());
        var tabs = service.tabs("owner", "chat");
        assertEquals(List.of(true, false), tabs.stream().map(WebDtos.BrowserTab::current).toList());
        for (Page page : pages) {
            verify(page, never()).evaluate(anyString(), any());
            verify(page, never()).screenshot(any());
            verify(page, never()).navigate(anyString(), any());
        }
        assertEquals(first.browserId(), command("owner", "chat", null, "read", null).browserId());
        command("owner", "chat", null, "close", null);
        verify(contexts.getFirst()).close();
        assertEquals(second.browserId(), command("owner", "chat", null, "read", null).browserId());
        command("owner", "chat", null, "close", null);
        assertTrue(service.tabs("owner", "chat").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> command("owner", "chat", null, "read", null));
        assertNotEquals(first.browserId(), command("owner", "chat", null, "open", "/article").browserId());
    }

    @Test void otherOwnersAndChatsCannotBeResumedOrListed() {
        var first = command("owner", "chat", null, "open", "/article");
        assertTrue(service.tabs("other", "chat").isEmpty());
        assertTrue(service.tabs("owner", "other-chat").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> command("other", "chat", first.browserId(), "read", null));
        assertThrows(IllegalArgumentException.class, () -> command("owner", "other-chat", first.browserId(), "read", null));
        assertThrows(IllegalArgumentException.class, () -> command("owner", "other-chat", null, "read", null));
        var other = command("owner", "other-chat", null, "open", "/article");
        assertNotEquals(first.browserId(), other.browserId());
        assertEquals(first.browserId(), command("owner", "chat", null, "read", null).browserId());
    }

    @Test void expiredBrowserIsRemovedAndRecreated() {
        var first = command("owner", "chat", null, "open", "/article");
        when(pages.getFirst().isClosed()).thenReturn(true);
        assertTrue(service.tabs("owner", "chat").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> command("owner", "chat", first.browserId(), "read", null));
        assertNotEquals(first.browserId(), command("owner", "chat", null, "open", "/article").browserId());
    }

    private WebDtos.BrowserView command(String owner, String chat, String id, String action, String path) {
        return service.command(owner, chat, new WebDtos.Command(id, action,
                path == null ? null : "https://fixture.test" + path, null, null, 0, null, null), false);
    }
}
