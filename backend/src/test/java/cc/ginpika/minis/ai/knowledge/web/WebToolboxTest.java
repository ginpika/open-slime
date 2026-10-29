package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebToolboxTest {
    private final ObjectMapper om = new ObjectMapper();
    private final WebBrowserService browser = mock(WebBrowserService.class);
    private final WebSnapshotService snapshots = mock(WebSnapshotService.class);
    private final WebToolbox toolbox = new WebToolbox(browser, snapshots, om);

    @Test void pageListingUsesStableChatOwnershipAcrossTurns() throws Exception {
        when(browser.tabs("agent:peer:chat", "chat")).thenReturn(List.of(
                new WebDtos.BrowserTab("existing", "https://fixture.test/", "Title", true)));
        for (String turn : List.of("turn-1", "turn-2")) {
            String output = run(turn, "{\"action\":\"list\"}");
            assertEquals("existing", om.readTree(output).path("tabs").get(0).path("browserId").asText());
        }
        verify(browser, times(2)).tabs("agent:peer:chat", "chat");
        verify(browser, never()).command(any(), any(), any(), anyBoolean());
    }

    @Test void captureWithoutIdResumesTheCurrentChatPageAndReturnsSaveableEvidence() throws Exception {
        when(browser.capture(eq("agent:peer:chat"), eq("chat"), eq(""), any(WebDtos.MediaOptions.class)))
                .thenReturn(new WebDtos.Capture("capture-id", "existing", "https://fixture.test/", "Title", "", "", 1,
                        "hash", List.of(new WebDtos.Paragraph("p1", "Original evidence")), List.of(), "<p>Original evidence</p>"));
        var output = om.readTree(run("turn-2", "{\"action\":\"capture\"}"));
        assertEquals("capture-id", output.path("capture_id").asText());
        assertEquals("existing", output.path("browser_id").asText());
        assertEquals("[p1] Original evidence", output.path("evidence").asText());
    }

    @Test void savedSnapshotMetadataIsReadableByLinkWithoutBrowserOrOriginalSite() throws Exception {
        stubSnapshot();
        var result = saved("{\"snapshot_id\":\"https://minis.test/#/snapshots/saved-id\"}");
        assertEquals("saved-id", result.path("snapshot_id").asText());
        assertEquals(12, result.path("doc_id").asInt());
        assertEquals("Author", result.path("author").asText());
        assertEquals("Rationale", result.path("rationale").asText());
        assertEquals(7, result.path("claims_count").asInt());
        assertFalse(result.path("enabled").asBoolean()); // disabled retrieval is still explicitly readable
        assertTrue(result.path("claims").isMissingNode());
        assertFalse(result.toString().contains("sourceHtml"));
        verifyNoInteractions(browser);
        verify(snapshots, never()).paragraphs(anyString());
    }

    @Test void savedSnapshotClaimsAreBoundedAndTheirEvidenceCanBeReadDirectly() throws Exception {
        stubSnapshot();
        var first = saved("{\"snapshot_id\":\"saved-id\",\"section\":\"claims\",\"limit\":999}");
        assertEquals(5, first.path("claims").size()); assertEquals(5, first.path("next_offset").asInt());
        assertEquals("p1", first.path("claims").get(0).path("evidence").get(0).asText());
        var next = saved("{\"snapshot_id\":\"saved-id\",\"section\":\"claims\",\"offset\":5}");
        assertEquals(2, next.path("claims").size()); assertTrue(next.path("next_offset").isNull());
        when(snapshots.paragraphs("saved-id")).thenReturn(List.of(new WebDtos.Paragraph("p1", "Original text"), new WebDtos.Paragraph("p2", "Other text")));
        var body = saved("{\"snapshot_id\":\"saved-id\",\"section\":\"body\",\"paragraph_id\":\"p1\",\"limit\":7}");
        var rest = saved("{\"snapshot_id\":\"saved-id\",\"section\":\"body\",\"paragraph_id\":\"p1\",\"offset\":7}");
        assertEquals("[p1] Original text", body.path("text").asText() + rest.path("text").asText());
        assertFalse(rest.path("has_more").asBoolean());
    }

    @Test void savedSnapshotReaderReportsMissingIdsSectionsParagraphsAndOffsets() throws Exception {
        stubSnapshot();
        when(snapshots.paragraphs("saved-id")).thenReturn(List.of(new WebDtos.Paragraph("p1", "text")));
        for (String args : List.of("{}", "{\"snapshot_id\":\"../private\"}",
                "{\"snapshot_id\":\"saved-id\",\"section\":\"unknown\"}",
                "{\"snapshot_id\":\"saved-id\",\"section\":\"body\",\"paragraph_id\":\"p2\"}",
                "{\"snapshot_id\":\"saved-id\",\"section\":\"body\",\"offset\":-1}",
                "{\"snapshot_id\":\"saved-id\",\"section\":\"claims\",\"offset\":8}"))
            assertEquals("invalid_request", saved(args).path("error").asText(), args);
        when(snapshots.metadata("missing")).thenThrow(new IllegalArgumentException("快照不存在"));
        assertEquals("invalid_request", saved("{\"snapshot_id\":\"missing\"}").path("error").asText());
    }

    private com.fasterxml.jackson.databind.JsonNode saved(String args) throws Exception {
        return om.readTree(toolbox.run("read_web_snapshot", om.readTree(args)));
    }

    private void stubSnapshot() {
        var claims = java.util.stream.IntStream.range(0, 7).mapToObj(i -> new WebDtos.Claim("Generated claim " + i, "opinion", List.of("p1"))).toList();
        when(snapshots.metadata("saved-id")).thenReturn(new WebDtos.Snapshot("saved-id", 12L, "https://fixture.test/", "Title", "Author",
                "2026-10-16", 1, 2, "hash", "Summary", "Rationale", claims, "model", List.of("Warning"), "disabled", false, "", ""));
    }

    private String run(String turn, String args) throws Exception {
        var json = om.readTree(args);
        return MemoryScopeCtx.run(MemoryExec.chat("peer", null, "chat", turn, false),
                () -> toolbox.run("web_browser", json));
    }
}
