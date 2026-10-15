package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class WebTabsTest {
    @Test void everyGalleryVersionAndImageIsArchivedAndTabsWorkInScriptlessPreviewAndExport() throws Exception {
        assumeTrue("true".equals(System.getenv("MINIS_BROWSER_TEST")));
        String executable = System.getenv("MINIS_WEB_EXECUTABLE_PATH");
        if (executable == null) executable = List.of("C:/Program Files/Google/Chrome/Application/chrome.exe", "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")
                .stream().filter(path -> Files.isRegularFile(Path.of(path))).findFirst().orElse("");
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=");
        byte[] gif = Base64.getDecoder().decode("R0lGODlhAQABAIABAAAAAP///yH5BAEAAAEALAAAAAABAAEAQAICTAEAOw==");
        var downloads = new ConcurrentHashMap<String, AtomicInteger>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String fixture = fixture();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean image = path.startsWith("/assets/");
            if (image) downloads.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] data = image ? path.endsWith(".gif") ? gif : png : fixture.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", image ? path.endsWith(".gif") ? "image/gif" : "image/png" : "text/html;charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        var fixtures = new WebUrlPolicy() { @Override public String validate(String raw) {
            if (!raw.startsWith(base + "/")) throw new IllegalArgumentException("Fixture only"); return raw;
        } };
        var browser = new WebBrowserService(fixtures, new ObjectMapper(), executable, true);
        try {
            var view = browser.command("owner", null, new WebDtos.Command(null, "open", base + "/wiki", null, null, 0, null, null), false);
            var capture = browser.capture("owner", view.browserId());
            var doc = Jsoup.parseBodyFragment(capture.sourceHtml());
            assertEquals(3, doc.select(".snapshot-tabs").size());
            assertEquals(46, doc.select(".snapshot-gallery img").size());
            assertEquals(4, doc.select(".snapshot-infobox img").size());
            assertEquals(0, doc.select("img:not([src^=data:])").size());
            assertTrue(capture.warnings().isEmpty(), capture.warnings().toString());
            assertEquals(4, doc.select(".snapshot-infobox .snapshot-tab-panel").size());
            assertEquals(8, doc.select(".snapshot-tabs").get(1).select(".snapshot-tab-panel").size());
            for (String version : List.of("Base", "AW", "AW2v1", "AW2v2")) {
                var panel = doc.select(".snapshot-infobox .snapshot-tab-panel").stream()
                        .filter(el -> el.selectFirst("h3").text().equals(version)).findFirst().orElseThrow();
                assertTrue(panel.text().contains("Artist for " + version));
                assertEquals(1, panel.select("img[alt='Portrait " + version + "']").size());
                assertTrue(capture.paragraphs().stream().anyMatch(p -> p.text().contains("Artist for " + version)));
            }
            for (String dangerous : List.of("javascript:", "onclick", "<script", "onerror", "HEADER_NOISE")) assertFalse(capture.sourceHtml().contains(dangerous));
            assertTrue(doc.select("input").stream().allMatch(el -> el.attr("type").equals("radio")));
            // Both instances are lazy: the live browser loads a placeholder, the archive fetches the actual portrait once.
            assertEquals(1, downloads.get("/assets/Base.png").get());
            assertEquals(1, downloads.get("/assets/gallery-45.png").get());

            var snapshot = new WebDtos.Snapshot("snapshot", null, capture.url(), capture.title(), "", "", 1, 1,
                    capture.contentHash(), "", "test", List.of(), "", List.of(), "keyword-only", true, capture.sourceHtml(), "");
            String exported = SnapshotHtml.render(snapshot);
            String srcdoc = new Element("iframe").attr("sandbox", "").attr("srcdoc", exported).outerHtml();
            try (var pw = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
                var launch = new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true);
                if (!executable.isBlank()) launch.setExecutablePath(Path.of(executable));
                try (var chromium = pw.chromium().launch(launch); var context = chromium.newContext()) {
                    Page page = context.newPage();
                    page.setContent(srcdoc);
                    Frame frame = page.frames().get(1);
                    verifySwitches(frame);
                    assertEquals(0, frame.locator("script").count());
                    page.setContent(exported);
                    verifySwitches(page.mainFrame());
                    // Printing reveals every version, rather than silently omitting inactive content.
                    page.emulateMedia(new Page.EmulateMediaOptions().setMedia(com.microsoft.playwright.options.Media.PRINT));
                    assertEquals(14, page.locator(".snapshot-tab-panel:visible").count());
                }
            }
            var plain = browser.capture("owner", view.browserId(), new WebDtos.MediaOptions(false, false, false));
            assertFalse(plain.sourceHtml().contains("<img"));
            assertEquals(3, Jsoup.parseBodyFragment(plain.sourceHtml()).select(".snapshot-tabs").size());
            assertTrue(plain.paragraphs().stream().anyMatch(p -> p.text().contains("Artist for AW2v2")));
        } finally { browser.close(); server.stop(0); }
    }

    private static void verifySwitches(Frame frame) {
        var box = frame.locator(".snapshot-infobox");
        for (String version : List.of("Base", "AW", "AW2v1", "AW2v2")) {
            box.locator("label").filter(new Locator.FilterOptions().setHasText(version))
                    .all().stream().filter(label -> label.textContent().equals(version)).findFirst().orElseThrow().click();
            assertEquals(1, box.locator(".snapshot-tab-panel:visible").count());
            assertTrue(box.locator(".snapshot-tab-panel:visible").innerText().contains("Artist for " + version));
            assertEquals(1, box.locator("img[alt='Portrait " + version + "']:visible").count());
        }
        var gallery = frame.locator(".snapshot-tabs").nth(1);
        for (String label : List.of("Art", "Screenshots", "A Ver.", "Base")) {
            gallery.locator("label").filter(new Locator.FilterOptions().setHasText(label)).click();
            assertEquals(1, gallery.locator(".snapshot-tab-panel:visible").count());
            assertEquals(label, gallery.locator(".snapshot-tab-panel:visible h3").textContent());
            assertTrue(gallery.locator(".snapshot-tab-panel:visible img").count() > 0);
        }
        // Keyboard arrows operate the native radio group too.
        gallery.locator("input:checked").press("ArrowRight");
        assertEquals("CC", gallery.locator(".snapshot-tab-panel:visible h3").textContent());
        assertEquals(1, box.locator(".snapshot-tab-panel:visible").count());
        assertTrue(box.locator(".snapshot-tab-panel:visible").innerText().contains("Artist for AW2v2"));
    }

    private static String fixture() {
        var html = new StringBuilder("<html><head><title>Versioned character</title></head><body><header class='site-header'>HEADER_NOISE</header><main><div class='mw-parser-output'><div class='unit-infobox'><div class='ui-title'>Character official name</div><div class='ui-text'>日本語の名前</div><div class='ui-text'>");
        for (String v : List.of("Base", "AW", "AW2v1", "AW2v2")) html.append("<span class='_toggler_show-Infobox").append(v).append(" _toggler_show-Otherlink'><a href='javascript:toggler(1)'>").append(v).append("</a></span>");
        html.append("</div><div class='ui-databox'><div class='ui-label'>Common facts</div><div class='ui-data'>Shared identity</div></div>");
        for (String v : List.of("Base", "AW", "AW2v1", "AW2v2")) html.append("<div class='Infobox").append(v).append(" ui-image' style='display:none'><a href='/assets/").append(v).append(".png'><img src='/assets/placeholder.png' data-src='/assets/").append(v).append(".png' alt='Portrait ").append(v).append("'></a></div><div class='Infobox").append(v).append(" ui-databox' style='display:none'><div class='ui-label'>Artist:</div><div class='ui-data'>Artist for ").append(v).append("</div></div>");
        html.append("</div><p>Reliable source knowledge describes the character, chronology and original provenance. Every version and its illustrations must remain available offline, including content that the source initially hides.</p><h2>Gallery</h2><table><tr><th>Versions</th><td>");
        List<String> versions = List.of("Base", "CC", "AW", "AW2v1", "AW2v2", "A Ver.", "Screenshots", "Art");
        for (String v : versions) html.append("<span class='_toggler_show-").append(v.replace(" ", "")).append("Gallery _toggler_show-UnrelatedLink'><a href='javascript:toggler(2)'>").append(v).append("</a></span>");
        html.append("</td></tr></table>");
        int index = 0;
        for (String v : versions) {
            html.append("<div class='").append(v.replace(" ", "")).append("Gallery' style='display:none'><div class='wikia-gallery'>");
            for (int i = 0; i < (v.equals("Art") ? 4 : 6); i++) {
                String src = index == 0 ? "/assets/Base.png" : "/assets/gallery-" + index + (index % 4 == 0 ? ".gif" : ".png");
                html.append("<div class='wikia-gallery-item'><noscript><img src='/assets/duplicate.png'></noscript><img src='/assets/placeholder.png' data-src='").append(src).append("' alt='Gallery ").append(index++).append("'><div class='lightbox-caption'>").append(v).append(" illustration evidence</div></div>");
            }
            html.append("</div></div>");
        }
        html.append("<div class='tabber'><div class='tabbertab' title='Lore'><p>Original lore</p></div><div class='tabbertab' title='Sources'><p>Primary sources and dates</p></div></div></div></main></body></html>");
        return html.toString();
    }
}
