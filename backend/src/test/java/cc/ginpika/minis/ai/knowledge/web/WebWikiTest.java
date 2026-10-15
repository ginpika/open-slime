package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import org.junit.jupiter.api.Test;

class WebWikiTest {
    @Test void wikiAndOrdinaryArticlesPreserveCoreBoxesOriginalAnimatedGifAndDiscardChrome() throws Exception {
        assumeTrue("true".equals(System.getenv("MINIS_BROWSER_TEST")));
        String executable = System.getenv("MINIS_WEB_EXECUTABLE_PATH");
        if (executable == null) executable = List.of("C:/Program Files/Google/Chrome/Application/chrome.exe","C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")
                .stream().filter(path -> Files.isRegularFile(Path.of(path))).findFirst().orElse("");
        byte[] gif = animatedGif();
        byte[] smallGif = animatedGif(false);
        assertTrue(gif.length > 1_000_000);
        try (var stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(gif))) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try { reader.setInput(stream); assertEquals(2,reader.getNumImages(true)); } finally { reader.dispose(); }
        }
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var originalDownloads = new AtomicInteger();
        server.createContext("/assets/", exchange -> {
            boolean original = exchange.getRequestURI().getPath().equals("/assets/a/ab/Portrait.gif")
                    || exchange.getRequestURI().getPath().startsWith("/assets/a/ab/Portrait.gif/revision/");
            if (original) originalDownloads.incrementAndGet();
            boolean small = exchange.getRequestURI().getPath().contains("/Small");
            byte[] data = small ? smallGif : original ? gif : png;
            exchange.getResponseHeaders().add("Content-Type",(original||small)?"image/gif":"image/png");
            exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.createContext("/", exchange -> {
            boolean wiki = exchange.getRequestURI().getPath().equals("/wiki");
            String body = """
                <!doctype html><html><head><title>Character knowledge</title></head><body>
                <header><details><summary>Site menu</summary><p>HEADER_MENU_NOISE</p></details></header>
                <aside class="site-sidebar"><details><summary>Site navigation</summary><p>SIDEBAR_MENU_NOISE</p></details></aside>
                <main>%s<h1>Character knowledge</h1><details class="sidebar-menu"><summary>Menu</summary><p>INLINE_MENU_NOISE</p></details>
                %s<table class="infobox" style="float:right"><tr><th colspan="2">Official identity</th></tr>
                <tr><th>日本語</th><td><a href="/name">星野</a></td></tr><tr><th>English</th><td><a href="/english">Hoshino</a></td></tr>
                <tr><td colspan="2"><img src="/assets/thumb/a/ab/Portrait.gif/320px-Portrait.gif" alt="Original animated portrait"></td></tr></table>
                <aside class="portable-infobox" role="complementary"><h2>角色信息</h2><div class="pi-data"><h3 class="pi-data-label">称号</h3><div class="pi-data-value">晓之星</div></div></aside>
                <div class="unit-infobox hidden"><div class="ui-title">Guardian of Light Altair</div><div class="ui-text">光の守護者アルティア<br><i>Hikari no Shugosha Arutia</i></div>
                <div class="ui-text"><a href="javascript:toggler(1)">TAB_CONTROL_NOISE</a></div>
                <div class="InfoboxAW ui-image" style="display:none"><a href="/assets/a/ab/Portrait.gif"><img src="data:image/gif;base64,R0lGODlhAQABAIABAAAAAP///yH5BAEAAAEALAAAAAABAAEAQAICTAEAOw%%3D%%3D" data-src="/assets/a/ab/Portrait.gif" alt="Altair AW Render"></a></div>
                <div class="ui-databox"><div class="ui-label">Artist:</div><div class="ui-data">wagi</div></div></div>
                <p>The character has official names in Japanese and English. These are essential source facts, alongside the portrait and original title in the fact boxes.</p>
                <p>This original article records the character's background, relationships, and conditions. It provides evidence for local knowledge, with enough prose to exercise ordinary reader extraction.</p>
                <details class="mw-collapsible"><summary>Supplementary character facts</summary><p>CORE_KNOWLEDGE_FOLD contains the official chronology and evidence.</p></details>
                <figure><a href="/wiki/File:Portrait.gif"><img data-src="/assets/a/ab/Portrait.gif/revision/latest?cb=20261014" src="/assets/placeholder.png"></a><figcaption>Original animation</figcaption></figure>
                <div class="navbox"><p>FOOTER_NAV_NOISE</p></div>%s</main></body></html>
                """.formatted(wiki?"<div id='mw-content-text'><div class='mw-parser-output'>":"<article>",
                        "<img src='/assets/decor.png' alt='Small body figure'>".repeat(42)
                                + java.util.stream.IntStream.range(0,14).mapToObj(i->"<img src='/assets/Small"+i+".gif/revision/latest' alt='Small animation "+i+"'>").collect(java.util.stream.Collectors.joining())
                                + "<noscript>NO_SCRIPT_NOISE</noscript>",wiki?"</div></div>":"</article>");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/html;charset=UTF-8");
            exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
        });
        server.start();
        var fixtures = new WebUrlPolicy() { @Override public String validate(String raw) { if(!raw.startsWith(base)) throw new IllegalArgumentException("Fixture only"); return raw; } };
        var browser = new WebBrowserService(fixtures,new ObjectMapper(),executable,true);
        try {
            for (String path : List.of("/wiki","/ordinary")) {
                var view = browser.command("owner",null,new WebDtos.Command(null,"open",base+path,null,null,0,null,null),false);
                var capture = browser.capture("owner",view.browserId());
                for (String noise : List.of("HEADER_MENU_NOISE","SIDEBAR_MENU_NOISE","INLINE_MENU_NOISE","FOOTER_NAV_NOISE","TAB_CONTROL_NOISE","NO_SCRIPT_NOISE","MINISMEDIA_")) assertFalse(capture.sourceHtml().contains(noise),path+":"+noise);
                assertTrue(capture.sourceHtml().contains("class=\"snapshot-infobox\""));
                assertTrue(capture.sourceHtml().contains("星野")); assertTrue(capture.sourceHtml().contains("Hoshino"));
                assertTrue(capture.paragraphs().stream().anyMatch(p -> p.text().contains("称号") && p.text().contains("晓之星")));
                assertTrue(capture.sourceHtml().contains("CORE_KNOWLEDGE_FOLD"));
                assertTrue(capture.paragraphs().stream().anyMatch(p -> p.text().contains("光の守護者アルティア") && p.text().contains("Hikari no Shugosha Arutia")));
                assertTrue(capture.paragraphs().stream().anyMatch(p -> p.text().contains("Artist:") && p.text().contains("wagi")));
                assertTrue(capture.sourceHtml().contains("Altair AW Render"));
                assertTrue(capture.sourceHtml().contains("data:image/gif;base64,"+Base64.getEncoder().encodeToString(gif)));
                var html=org.jsoup.Jsoup.parseBodyFragment(capture.sourceHtml());
                for(int i=0;i<14;i++) {
                    var images=html.select("img[alt='Small animation "+i+"']");
                    assertEquals(1,images.size());
                    assertEquals("data:image/gif;base64,"+Base64.getEncoder().encodeToString(smallGif),images.getFirst().attr("src"));
                }
                var plain = browser.capture("owner",view.browserId(),new WebDtos.MediaOptions(false,false,false));
                assertTrue(plain.sourceHtml().contains("星野")); assertFalse(plain.sourceHtml().contains("<img"));
                browser.command("owner",null,new WebDtos.Command(view.browserId(),"close",null,null,null,0,null,null),false);
            }
            assertTrue(originalDownloads.get()>=2);
        } finally { browser.close(); server.stop(0); }
    }

    private static byte[] animatedGif() throws Exception {
        return animatedGif(true);
    }
    private static byte[] animatedGif(boolean pad) throws Exception {
        var output = new ByteArrayOutputStream(); var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream); writer.prepareWriteSequence(null);
            for (int rgb : List.of(0x2563eb,0x60a5fa)) {
                var image = new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB);
                for(int x=0;x<4;x++)for(int y=0;y<4;y++)image.setRGB(x,y,rgb);
                var meta=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),writer.getDefaultWriteParam());
                var root=(IIOMetadataNode)meta.getAsTree("javax_imageio_gif_image_1.0");
                var control=(IIOMetadataNode)root.getElementsByTagName("GraphicControlExtension").item(0);control.setAttribute("delayTime","20");
                meta.setFromTree("javax_imageio_gif_image_1.0",root); writer.writeToSequence(new IIOImage(image,null,meta),null);
            }
            writer.endWriteSequence();
        } finally {writer.dispose();}
        byte[] original=output.toByteArray(); if(!pad)return original;
        var padded=new ByteArrayOutputStream(); padded.write(original,0,original.length-1);
        padded.write(0x21);padded.write(0xfe); // valid GIF comment subblocks, testing > 1 MB without a huge image
        for(int i=0;i<4200;i++){padded.write(255);padded.write(new byte[255]);}
        padded.write(0);padded.write(0x3b);return padded.toByteArray();
    }
}
