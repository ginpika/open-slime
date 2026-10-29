package cc.ginpika.minis.ai.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cc.ginpika.minis.ai.core.tool.*;
import cc.ginpika.minis.ai.core.tool.doc.*;
import cc.ginpika.minis.ai.knowledge.store.*;
import cc.ginpika.minis.ai.knowledge.web.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DocumentLibraryTest {
    private final ObjectMapper om = new ObjectMapper();
    private SingleConnectionDataSource ds;
    private JdbcTemplate jdbc;
    private DocumentStore documents;
    private ChunkStore chunks;
    private DocumentToolbox toolbox;
    private WebSnapshotStore snapshots;

    @BeforeEach void setUp() {
        ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        chunks = new ChunkStore(jdbc);
        var evidence = new SnapshotEvidenceStore(jdbc);
        var chunker = mock(Chunker.class);
        when(chunker.split(anyString())).thenAnswer(call -> List.of(call.getArgument(0, String.class)));
        documents = new DocumentStore(jdbc, chunks, new GraphStore(jdbc, om), chunker, "unused", evidence);
        snapshots = new WebSnapshotStore(jdbc, chunks, documents, om, evidence);
        toolbox = new DocumentToolbox(documents, om);
    }

    @AfterEach void close() { ds.destroy(); }

    @Test void catalogueTraversesSameTitleAndTimestampWithoutOmissionOrDuplicates() throws Exception {
        var expected = new ArrayList<Long>();
        for (int i = 0; i < 25; i++) expected.add(create("同名文档", "body"));
        jdbc.update("UPDATE documents SET created_at=100");
        var found = new ArrayList<Long>();
        String cursor = null;
        do {
            var args = om.createObjectNode().put("limit", 10);
            if (cursor != null) args.put("cursor", cursor);
            var result = call("list_documents", args);
            for (var item : result.path("items")) found.add(item.path("doc_id").asLong());
            assertEquals(result.path("has_more").asBoolean(), !result.path("next_cursor").isNull());
            cursor = result.path("next_cursor").asText(null);
        } while (cursor != null);
        java.util.Collections.reverse(expected);
        assertEquals(expected, found);
    }

    @Test void cursorDoesNotShiftWhenNewItemsAreInsertedAndEmptyLibraryIsExplicit() throws Exception {
        var empty = call("list_documents", om.createObjectNode());
        assertTrue(empty.path("items").isEmpty()); assertFalse(empty.path("has_more").asBoolean());
        for (int i = 0; i < 3; i++) create("Document " + i, "body");
        jdbc.update("UPDATE documents SET created_at=100");
        var first = call("list_documents", om.createObjectNode().put("limit", 2));
        create("New arrival", "body");
        var next = call("list_documents", om.createObjectNode().put("limit", 2).put("cursor", first.path("next_cursor").asText()));
        assertEquals(1, next.path("items").size());
        assertEquals("Document 0", next.path("items").get(0).path("title").asText());
        assertFalse(next.path("has_more").asBoolean());
    }

    @Test void catalogueFiltersAndIdentitiesWorkForSnapshotsAndOrdinaryDocuments() throws Exception {
        create("Internal note", "body");
        var snapshot = save("Saved snapshot");
        var result = call("list_documents", om.createObjectNode().put("source", "snapshot"));
        assertEquals(1, result.path("items").size());
        var item = result.path("items").get(0);
        assertEquals(snapshot.id(), item.path("snapshot_id").asText());
        assertEquals(snapshot.docId().longValue(), item.path("doc_id").asLong());
        assertEquals(snapshot.url(), item.path("url").asText());
        assertEquals("#/snapshots/" + snapshot.id(), item.path("local_url").asText());
        snapshots.toggle(snapshot.id(), false);
        assertTrue(call("list_documents", om.createObjectNode().put("source", "snapshot").put("status", "enabled")).path("items").isEmpty());
        assertEquals(1, call("list_documents", om.createObjectNode().put("status", "disabled")).path("items").size());
        jdbc.update("UPDATE documents SET created_at=50 WHERE source='snapshot'");
        assertEquals(1, call("list_documents", om.createObjectNode().put("created_from", 50).put("created_to", 50)).path("items").size());
    }

    @Test void titleSearchEscapesWildcardsAndCanContinueBeyondTenResults() throws Exception {
        create("literal %_ 中文", "body");
        create("literal ab 中文", "body");
        assertEquals(1, call("search_documents_by_title", om.createObjectNode().put("keywords", "%_ 中文")).path("items").size());
        for (int i = 0; i < 12; i++) create("Repeated title", "body");
        var first = call("search_documents_by_title", om.createObjectNode().put("keywords", "Repeated"));
        assertEquals(10, first.path("items").size()); assertTrue(first.path("has_more").asBoolean());
        var next = call("search_documents_by_title", om.createObjectNode().put("keywords", "Repeated").put("cursor", first.path("next_cursor").asText()));
        assertEquals(2, next.path("items").size());
        assertEquals("invalid_request", call("search_documents_by_title", om.createObjectNode()).path("error").asText());
    }

    @Test void catalogueRejectsInvalidFiltersAndCursorAndCapsOutput() throws Exception {
        assertError(om.createObjectNode().put("cursor", "broken"));
        assertError(om.createObjectNode().put("source", "anything"));
        assertError(om.createObjectNode().put("status", "secret"));
        assertError(om.createObjectNode().put("created_from", 2).put("created_to", 1));
        for (int i = 0; i < 35; i++) create("Document", "body");
        assertEquals(30, call("list_documents", om.createObjectNode().put("limit", 10000)).path("items").size());
    }

    @Test void documentReadingKeepsLegacyPagesAndOffersBoundedVersionedContinuation() throws Exception {
        String body = "0123456789".repeat(700);
        long id = create("Long document", body);
        String legacy = toolbox.run("read_document", om.createObjectNode().put("doc_id", id));
        assertTrue(legacy.contains("下一页 page=2"));
        var first = call("read_document", om.createObjectNode().put("doc_id", id).put("offset", 0).put("limit", 3000));
        var nextArgs = om.createObjectNode().put("doc_id", id).put("offset", first.path("next_offset").asInt())
                .put("limit", 4000).put("expected_hash", first.path("content_hash").asText());
        var next = call("read_document", nextArgs);
        assertEquals(body, first.path("text").asText() + next.path("text").asText());
        assertTrue(next.path("next_offset").isNull());
        jdbc.update("UPDATE chunks SET content='changed' WHERE doc_id=?", id);
        assertEquals("content_changed", call("read_document", nextArgs).path("error").asText());
        assertEquals("invalid_request", call("read_document", om.createObjectNode().put("doc_id", id).put("offset", 0).put("page", 1)).path("error").asText());
    }

    @Test void textPagesPreserveSupplementaryUnicodeAndReportBounds() {
        var first = ToolTextPage.slice(om, "a🌐b", 0, 2);
        var second = ToolTextPage.slice(om, "a🌐b", first.path("next_offset").asInt(), 2);
        assertEquals("a🌐", first.path("text").asText() + second.path("text").asText());
        assertThrows(IllegalArgumentException.class, () -> ToolTextPage.slice(om, "a🌐b", 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ToolTextPage.slice(om, "abc", -1, 2));
        assertThrows(IllegalArgumentException.class, () -> ToolTextPage.slice(om, "abc", 4, 2));
        assertFalse(ToolTextPage.slice(om, "abc", 3, 2).path("has_more").asBoolean());
    }

    @Test void ragResultsExposeHandlesForDirectDocumentAndSnapshotReading() {
        var snapshot = save("Source title");
        var text = ResultTexts.ragChunksText(chunks.rowsByDoc(snapshot.docId()));
        assertTrue(text.contains("doc_id: " + snapshot.docId()));
        assertTrue(text.contains("snapshot_id: " + snapshot.id()));
        assertTrue(text.contains("chunk_id: "));
        assertTrue(text.contains("#/snapshots/" + snapshot.id()));
    }

    @Test void oldSnapshotEvidenceIsIndexedOnceAndSurvivesRenameAndRechunk() {
        var snapshot = save("Legacy snapshot");
        jdbc.update("DELETE FROM web_snapshot_paragraph WHERE snapshot_id=?", snapshot.id());
        String first = documents.fullText(snapshot.docId());
        assertTrue(first.contains("[p1] Original source evidence"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM web_snapshot_paragraph WHERE snapshot_id=?", Integer.class, snapshot.id()) > 0);
        // Changing retrieval chunks must not change the evidence reader's source of truth.
        documents.rechunkDocument(snapshot.docId());
        jdbc.update("UPDATE chunks SET content='retrieval replacement' WHERE doc_id=?", snapshot.docId());
        snapshots.rename(snapshot.id(), "Renamed snapshot");
        assertTrue(documents.fullText(snapshot.docId()).contains("Original source evidence"));
        assertFalse(documents.fullText(snapshot.docId()).contains("retrieval replacement"));
        // Prove subsequent reads use the text index without parsing media HTML again.
        jdbc.update("UPDATE web_snapshot SET source_html='not parsed on subsequent reads' WHERE id=?", snapshot.id());
        assertTrue(documents.fullText(snapshot.docId()).contains("Original source evidence"));
        snapshots.delete(snapshot.id());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM web_snapshot_paragraph WHERE snapshot_id=?", Integer.class, snapshot.id()));
    }

    @Test void listingReturnsMetadataWithoutPerItemDetailLoadsOrLegacyBodyMaterialization() {
        var snapshot = save("Metadata only");
        jdbc.update("DELETE FROM web_snapshot_paragraph");
        var observed = spy(snapshots);
        var listed = observed.list(1, 20);
        assertEquals(1, listed.size());
        assertEquals("", listed.getFirst().sourceHtml());
        assertEquals(snapshot.contentHash(), listed.getFirst().contentHash());
        verify(observed, never()).get(anyString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM web_snapshot_paragraph", Integer.class));
        assertEquals("", snapshots.metadata(snapshot.id()).sourceHtml());
    }

    @Test void toolsAreAvailableInChatHeartbeatAndTheDefaultReadOnlyWorkerSet() {
        var manager = new ToolManager(List.of(new ListDocumentsTool(), new ReadWebSnapshotTool(), new ReadDocumentTool(), new SearchDocumentsTool()), om);
        for (var schemas : List.of(manager.toolsOf(ToolSurface.CHAT), manager.toolsOf(ToolSurface.HEARTBEAT), manager.readOnlyToolsOf(ToolSurface.CHAT))) {
            assertEquals(4, schemas.size());
            assertTrue(schemas.toString().contains("list_documents")); assertTrue(schemas.toString().contains("read_web_snapshot"));
        }
    }

    private long create(String title, String content) { return documents.create(title, content).id(); }
    private JsonNode call(String name, JsonNode args) throws Exception { return om.readTree(toolbox.run(name, args)); }
    private void assertError(JsonNode args) throws Exception { assertEquals("invalid_request", call("list_documents", args).path("error").asText()); }
    private WebDtos.Snapshot save(String title) {
        var clean = SnapshotHtml.clean("<p>Original source evidence with its conditions.</p>", "https://fixture.test/article");
        var capture = new WebDtos.Capture("capture", "browser", "https://fixture.test/article", title, "Author", "", 1,
                SnapshotHtml.hash(clean.html()), clean.paragraphs(), clean.warnings(), clean.html());
        return snapshots.save(capture, new WebDtos.SaveRequest("capture", title, "Summary", "Rationale", List.of(), ""));
    }
}
