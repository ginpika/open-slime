package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class SnapshotImagesTest {
    @Test void zipContainsExactBytesAcrossHiddenVersionsDeduplicatesAndKeepsProvenance() throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=");
        byte[] gif=animatedGif();
        String portrait="data:image/png;base64,"+Base64.getEncoder().encodeToString(png);
        String animation="data:image/gif;base64,"+Base64.getEncoder().encodeToString(gif);
        String raw="""
            <div class='snapshot-infobox'><div class='snapshot-tabs'>
            <section class='snapshot-tab-panel' data-snapshot-label='Base'><h3 class='snapshot-tab-heading'>Base</h3><img src='%s' alt='../../CON?.PNG (16 KB)'></section>
            <section class='snapshot-tab-panel' data-snapshot-label='AW'><h3 class='snapshot-tab-heading'>AW</h3><img src='%s' alt='覚醒 立ち絵.png'></section>
            </div></div><div class='snapshot-tabs'>
            <section class='snapshot-tab-panel' data-snapshot-label='Base'><h3 class='snapshot-tab-heading'>Base</h3><div class='snapshot-gallery'><img src='%s' alt='Gallery portrait'></div></section>
            <section class='snapshot-tab-panel' data-snapshot-label='AW'><h3 class='snapshot-tab-heading'>AW</h3><div class='snapshot-gallery'><img src='%s' alt='アルティア 動画.gif'><img src='%s' alt='Same animation'></div></section>
            </div><img src='data:image/png;base64,A'><img src='data:image/svg+xml;base64,PHN2Zz4='><img src='https://unreachable.invalid/image.png'>
            """.formatted(portrait,portrait,portrait,animation,animation);
        // The extraction utility also safely handles incomplete legacy images without fetching them.
        var snapshot=snapshot(raw);
        var archive=SnapshotImages.extract(snapshot);
        var catalog=archive.catalog();
        assertEquals(5,catalog.totalOccurrences()); assertEquals(2,catalog.imageCount());
        assertEquals(3,catalog.duplicateCount()); assertEquals(3,catalog.skippedCount());
        assertEquals(png.length+gif.length,catalog.totalBytes());
        var first=catalog.images().getFirst();
        assertEquals(3,first.occurrences().size());
        assertEquals(Set.of("Base","AW"),new HashSet<>(first.occurrences().stream().map(SnapshotImages.Occurrence::version).toList()));
        assertEquals(Set.of("信息框","Gallery"),new HashSet<>(first.occurrences().stream().map(SnapshotImages.Occurrence::section).toList()));
        assertTrue(first.filename().endsWith(".png"));
        for(var image:catalog.images()) {
            assertFalse(image.filename().matches(".*[\\\\/:*?\"<>|].*"));
            String src=Jsoup.parseBodyFragment(raw).select("img").get(image.sourceIndex()).attr("src");
            byte[] bytes=Base64.getDecoder().decode(src.substring(src.indexOf(";base64,")+8));
            assertEquals(image.id(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        var output=new ByteArrayOutputStream(); var mapper=new ObjectMapper(); archive.writeZip(output,mapper);
        var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(output.toByteArray()),StandardCharsets.UTF_8)) {
            ZipEntry entry; while((entry=zip.getNextEntry())!=null) files.put(entry.getName(),zip.readAllBytes());
        }
        assertEquals(3,files.size());
        assertArrayEquals(png,files.get("images/"+first.filename()));
        byte[] exportedGif=files.get("images/"+catalog.images().get(1).filename());
        assertArrayEquals(gif,exportedGif);
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(exportedGif))) {
            var reader=ImageIO.getImageReadersByFormatName("gif").next();
            try {reader.setInput(stream);assertEquals(2,reader.getNumImages(true));} finally {reader.dispose();}
        }
        var manifest=mapper.readTree(files.get("manifest.json"));
        assertEquals("saved-snapshot",manifest.path("snapshotId").asText());
        assertEquals(snapshot.url(),manifest.path("url").asText());
        assertEquals(2,manifest.path("catalog").path("images").size());
        assertEquals("AW",manifest.path("catalog").path("images").get(1).path("occurrences").get(0).path("version").asText());
        assertEquals(raw,snapshot.sourceHtml());
    }

    @Test void emptySnapshotsHaveNoImages() {
        var catalog=SnapshotImages.extract(snapshot("<p>Only source text.</p>")).catalog();
        assertEquals(0,catalog.imageCount()); assertEquals(0,catalog.totalBytes()); assertTrue(catalog.images().isEmpty());
    }

    static WebDtos.Snapshot snapshot(String html) {
        return new WebDtos.Snapshot("saved-snapshot",null,"https://example.org/wiki/Character","角色快照","","",1,1,"hash","","test",List.of(),"",List.of(),"keyword-only",true,html,"");
    }
    private static byte[] animatedGif() throws Exception {
        var output=new ByteArrayOutputStream(); var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(var stream=ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int color:List.of(0x2563eb,0x60a5fa)) {
                var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);
                for(int x=0;x<2;x++)for(int y=0;y<2;y++)image.setRGB(x,y,color);
                writer.writeToSequence(new IIOImage(image,null,null),null);
            }
            writer.endWriteSequence();
        } finally {writer.dispose();}
        return output.toByteArray();
    }
}
