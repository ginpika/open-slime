package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import cc.ginpika.minis.ai.knowledge.ingest.Chunker;
import cc.ginpika.minis.ai.knowledge.store.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WebSnapshotTest {
    private WebDtos.Capture capture(String html) {
        var clean=SnapshotHtml.clean(html,"https://example.org/article");
        return new WebDtos.Capture("capture","browser","https://example.org/article","Article","Author","2026-10-13",1,
                SnapshotHtml.hash(clean.html()),clean.paragraphs(),clean.warnings(),clean.html());
    }

    @Test void sanitizerRetainsEvidenceAndRejectsActiveContent() {
        var clean=SnapshotHtml.clean("""
            <h2>Heading</h2><p onclick="steal()">Original evidence <a href="javascript:steal()">unsafe</a></p>
            <script>steal()</script><iframe src="https://evil.example"></iframe>
            <table><tr><td>42</td><td>units</td></tr></table><pre>const a = 42;</pre>
            <img src="https://example.org/image.png" alt="a graph">
            <img src="data:image/svg+xml;base64,PHN2Zz4=" onerror="steal()">
            ""","https://example.org/article");
        assertFalse(clean.html().contains("script"));
        assertFalse(clean.html().contains("onclick"));
        assertFalse(clean.html().contains("javascript:"));
        assertFalse(clean.html().contains("onerror"));
        assertFalse(clean.html().contains("svg+xml"));
        assertTrue(clean.html().contains("<table"));
        assertTrue(clean.html().contains("const a = 42;"));
        assertTrue(clean.paragraphs().stream().anyMatch(p->p.text().contains("Original evidence")));
        assertTrue(clean.paragraphs().stream().allMatch(p->clean.html().contains("id=\""+p.id()+"\"")));
        assertFalse(clean.warnings().isEmpty());
    }

    @Test void claimsRequireActualSourceParagraphs() {
        var capture=capture("<p>Evidence from the source.</p>");
        var good=new WebDtos.SaveRequest("capture","Title","Summary","Useful evidence",List.of(new WebDtos.Claim("Supported","fact",List.of("p1"))),"model");
        assertEquals(1,WebSnapshotService.validate(capture,good).claims().size());
        var bad=new WebDtos.SaveRequest("capture","Title","Summary","Useful evidence",List.of(new WebDtos.Claim("Invented","fact",List.of("p999"))),"model");
        assertThrows(IllegalArgumentException.class,()->WebSnapshotService.validate(capture,bad));
        assertThrows(IllegalArgumentException.class,()->WebSnapshotService.validate(capture,new WebDtos.SaveRequest("capture","Title","","",List.of(),"")));
    }

    @Test void selectedMediaIsOfflineControlledAndUnselectedMediaIsRemoved() {
        String raw = """
            <p>Original evidence with media.</p><img src="data:image/png;base64,YWJj">
            <audio src="data:audio/wav;base64,YWJj" autoplay onplay="steal()"></audio>
            <video autoplay poster="https://evil.example/poster.png"><source src="data:video/webm;base64,YWJj" type="video/webm"><track src="https://evil.example/subtitles"></video>
            <audio src="https://example.org/missing.mp3"></audio><video src="javascript:steal()"></video>
            """;
        var selected = SnapshotHtml.clean(raw, "https://example.org", new WebDtos.MediaOptions(true,true,true));
        assertTrue(selected.html().contains("data:audio/wav;base64,YWJj"));
        assertTrue(selected.html().contains("data:video/webm;base64,YWJj"));
        assertTrue(selected.html().contains("controls"));
        assertTrue(selected.html().contains("preload=\"none\""));
        for (String active : List.of("autoplay", "onplay", "<track", "poster=", "javascript:", "https://evil.example")) assertFalse(selected.html().contains(active), active);
        assertTrue(selected.warnings().stream().anyMatch(w -> w.contains("音频")));
        assertTrue(selected.warnings().stream().anyMatch(w -> w.contains("视频")));
        var plain = SnapshotHtml.clean(raw, "https://example.org", new WebDtos.MediaOptions(false,false,false));
        assertFalse(plain.html().contains("<img")); assertFalse(plain.html().contains("<audio")); assertFalse(plain.html().contains("<video"));
        assertFalse(plain.html().contains("未离线保存"));
        assertEquals(selected.paragraphs().getFirst().text(),plain.paragraphs().getFirst().text());
        assertNotEquals(SnapshotHtml.hash(selected.html()),SnapshotHtml.hash(plain.html()));
        var unstructured = SnapshotHtml.clean("Plain source text <audio src='data:audio/wav;base64,YWJj'></audio>",
                "https://example.org",new WebDtos.MediaOptions(false,true,false));
        assertTrue(unstructured.html().contains("data:audio/wav;base64,YWJj"));
        assertEquals("Plain source text",unstructured.paragraphs().getFirst().text());
    }

    @Test void factBoxesDefinitionsAndKnowledgeFoldsSurviveWhileMenusAreRemoved() {
        var clean = SnapshotHtml.clean("""
            <header class="site-header"><details><summary>Site menu</summary><p>HEADER_MENU_NOISE</p></details></header>
            <aside class="site-sidebar"><details><summary>Navigation</summary><p>SIDEBAR_MENU_NOISE</p></details></aside>
            <table class="infobox" style="float:right" onclick="steal()"><tr><th colspan="2">Official identity</th></tr>
              <tr><th>日本語</th><td lang="ja">星野</td></tr><tr><th>English</th><td>Hoshino</td></tr>
              <tr><td colspan="2"><img src="data:image/gif;base64,R0lGODlh" alt="Character portrait"></td></tr></table>
            <aside class="portable-infobox" role="complementary"><dl><dt>称号</dt><dd>晓之星</dd></dl></aside>
            <details><summary>Background facts</summary><p>Important facts from a closed knowledge fold.</p></details>
            <figure><img src="data:image/gif;base64,R0lGODlh"><figcaption>Animation showing an important mechanism.</figcaption></figure>
            <p>Normal article evidence with relevant details.</p>
            <div class="wikia-gallery-caption"><a href="/wiki/File:Portrait.gif">Portrait skill animation</a></div>
            <table style="float:left;text-align:right"><tr><td>Ordinary skill statistics</td></tr></table>
            """, "https://example.org/wiki/Character");
        for (String noise : List.of("HEADER_MENU_NOISE", "SIDEBAR_MENU_NOISE", "onclick", "style=", "portable-infobox")) assertFalse(clean.html().contains(noise),noise);
        assertTrue(clean.html().contains("class=\"snapshot-infobox\""));
        assertTrue(clean.html().contains("colspan=\"2\""));
        assertTrue(clean.html().contains("lang=\"ja\""));
        assertTrue(clean.html().contains("<details open"));
        assertTrue(clean.html().contains("data:image/gif;base64,R0lGODlh"));
        assertTrue(clean.paragraphs().stream().anyMatch(p -> p.text().contains("日本語") && p.text().contains("星野") && p.text().contains("Hoshino")));
        assertTrue(clean.paragraphs().stream().anyMatch(p -> p.text().contains("称号") && p.text().contains("晓之星")));
        assertTrue(clean.paragraphs().stream().anyMatch(p -> p.text().contains("Animation showing")));
        assertTrue(clean.paragraphs().stream().anyMatch(p -> p.text().contains("Portrait skill animation")));
        assertEquals(2,org.jsoup.Jsoup.parseBodyFragment(clean.html()).select(".snapshot-infobox").size());
    }

    @Test void urlPolicyRejectsPrivateAndReservedDestinations() throws Exception {
        WebUrlPolicy policy=new WebUrlPolicy();
        for(String url:List.of("file:///etc/passwd","javascript:alert(1)","http://127.0.0.1","http://[::1]","http://10.0.0.1","http://169.254.169.254","http://100.64.0.1","https://user:pass@example.org")) {
            assertThrows(IllegalArgumentException.class,()->policy.validate(url),url);
        }
        assertTrue(WebUrlPolicy.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(WebUrlPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        assertFalse(WebUrlPolicy.publicAddress(InetAddress.getByName("198.51.100.1")));
        org.springframework.test.util.ReflectionTestUtils.setField(policy,"allowFakeIp",true);
        assertThrows(IllegalArgumentException.class,()->policy.validate("http://198.18.0.1"));
    }

    @Test void tabControlsAreGeneratedAfterSanitizationAndAllVersionsRemainEvidence() {
        var clean = SnapshotHtml.clean("""
            <div class="snapshot-tabs unrelated" onclick="steal()">
              <section class="snapshot-tab-panel" data-snapshot-label="Base"><h3 class="snapshot-tab-heading">Base</h3><p>Original base facts.</p></section>
              <section class="snapshot-tab-panel" data-snapshot-label="AW"><h3 class="snapshot-tab-heading">AW</h3><dl><dt>Artist</dt><dd>Awakened artist facts.</dd></dl><input type="text" name="secret"><script>steal()</script></section>
            </div><input type="radio" name="snapshot-tabs-1" checked><label for="arbitrary">Source form</label>
            <div class="snapshot-tabs"><section class="snapshot-tab-panel" data-snapshot-label="Only"><h3 class="snapshot-tab-heading">Only</h3><p>Single panel remains visible.</p></section></div>
            """, "https://example.org/wiki");
        var doc = org.jsoup.Jsoup.parseBodyFragment(clean.html());
        assertEquals(2, doc.select("input").size());
        assertEquals(1, doc.select("input[checked]").size());
        assertTrue(doc.select("input").stream().allMatch(el -> el.attr("type").equals("radio") && el.attr("name").equals("snapshot-tabs-1")));
        for (var label : doc.select("label")) assertNotNull(doc.getElementById(label.attr("for")));
        for (String bad : List.of("onclick", "steal", "<script", "secret", "unrelated", "data-snapshot-label", "for=\"arbitrary\"")) assertFalse(clean.html().contains(bad), bad);
        assertEquals(1, doc.select(".snapshot-tabs").size());
        assertTrue(clean.paragraphs().stream().anyMatch(p -> p.text().contains("Awakened artist facts.")));
        assertTrue(doc.select("p").stream().filter(p -> p.text().contains("Single panel")).allMatch(p -> p.closest(".snapshot-tab-panel") == null));
    }

    @Test void savingDeduplicatesVersionsPublishesSourceAndDeletesDerivedData() {
        try(var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true)) {
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
            var jdbc=new JdbcTemplate(ds);var om=new ObjectMapper();var chunks=new ChunkStore(jdbc);var graph=new GraphStore(jdbc,om);
            var evidence=new SnapshotEvidenceStore(jdbc);
            var docs=new DocumentStore(jdbc,chunks,graph,new Chunker(mock(cc.ginpika.minis.ai.llm.OpenAiChatClient.class),mock(cc.ginpika.minis.ai.core.prompt.PromptManager.class),om),"unused",evidence);
            var store=new WebSnapshotStore(jdbc,chunks,docs,om,evidence);
            var first=capture("<p>Original source facts, numbers and conditions.</p>");
            var request=new WebDtos.SaveRequest(first.captureId(),first.title(),"Generated summary","High quality original source",List.of(new WebDtos.Claim("Original source facts","fact",List.of("p1"))),"test-model");
            var saved=store.save(first,request);
            assertEquals(saved.id(),store.save(first,request).id());
            assertEquals(1,store.list(1,20).size());
            assertTrue(docs.fullText(saved.docId()).contains("Original source facts"));
            docs.rechunkDocument(saved.docId());
            assertEquals("snapshot:"+saved.id(),chunks.rowsByDoc(saved.docId()).getFirst().sourceRef());
            long chunkId=chunks.rowsByDoc(saved.docId()).getFirst().id();
            store.saveEmbeddingIfEnabled(saved.id(),chunkId,"test",new float[]{1,0});
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding",Integer.class));
            assertTrue(chunks.rowsByDoc(saved.docId()).getFirst().content().contains("[p1]"));
            assertFalse(chunks.rowsByDoc(saved.docId()).getFirst().content().contains("Generated summary"));
            var beforeRename=chunks.rowsByDoc(saved.docId()).getFirst();
            var renamed=store.rename(saved.id(),"  RenamedSnapshotAlpha · 日文资料  ");
            assertEquals("RenamedSnapshotAlpha · 日文资料",renamed.title());
            assertEquals(renamed.title(),store.get(saved.id()).title());
            assertEquals(renamed.title(),docs.title(saved.docId()));
            assertEquals(renamed.title(),chunks.rowsByDoc(saved.docId()).getFirst().title());
            assertTrue(chunks.metaMatch(List.of("RenamedSnapshotAlpha"),20).contains(chunkId));
            assertTrue(chunks.cnMatch(List.of("日文资料"),20).contains(chunkId));
            assertEquals(beforeRename.content(),chunks.rowsByDoc(saved.docId()).getFirst().content());
            assertEquals(beforeRename.status(),chunks.rowsByDoc(saved.docId()).getFirst().status());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding",Integer.class));
            assertEquals(saved.sourceHtml(),store.get(saved.id()).sourceHtml());
            assertEquals(saved.contentHash(),store.get(saved.id()).contentHash());
            assertEquals(saved.savedAt(),store.get(saved.id()).savedAt());
            assertEquals(renamed.title(),store.save(first,request).title());
            assertTrue(SnapshotHtml.render(store.get(saved.id())).contains("<title>"+renamed.title()+"</title>"));
            for(String invalid:List.of("", "   ", "x".repeat(501), "bad\nname")) assertThrows(IllegalArgumentException.class,()->store.rename(saved.id(),invalid));
            assertThrows(IllegalArgumentException.class,()->store.rename(saved.id(),null));
            assertThrows(IllegalArgumentException.class,()->store.rename("missing-snapshot","Valid name"));
            var changed=capture("<p>A newer version with different source facts.</p>");
            var newer=store.save(changed,new WebDtos.SaveRequest(changed.captureId(),changed.title(),"","Changed version",List.of(),""));
            assertNotEquals(saved.id(),newer.id());
            assertEquals(2,store.list(1,20).size());
            store.toggle(saved.id(),false);
            store.saveEmbeddingIfEnabled(saved.id(),chunkId,"test",new float[]{1,0});
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding",Integer.class));
            assertTrue(chunks.rowsByDoc(saved.docId()).stream().allMatch(c->c.status().equals("disabled")));
            store.rename(saved.id(),"Disabled snapshot title");
            assertTrue(chunks.rowsByDoc(saved.docId()).stream().allMatch(c->c.status().equals("disabled")));
            String html=SnapshotHtml.render(store.get(saved.id()));
            assertTrue(html.contains("href=\"#p1\""));
            assertTrue(html.contains("default-src 'none'"));
            assertTrue(html.contains("Original source facts"));
            store.delete(saved.id());
            store.saveEmbeddingIfEnabled(saved.id(),chunkId,"test",new float[]{1,0});
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chunk_embedding",Integer.class));
            assertTrue(chunks.rowsByDoc(saved.docId()).isEmpty());
            assertNull(docs.title(saved.docId()));
            assertEquals(1,store.list(1,20).size());
        }
    }
}
