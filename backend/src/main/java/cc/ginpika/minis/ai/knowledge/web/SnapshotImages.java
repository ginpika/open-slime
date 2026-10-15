package cc.ginpika.minis.ai.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/** Extracts saved raster bytes without contacting the source site or changing the snapshot. */
public final class SnapshotImages {
    private SnapshotImages() {}
    public record Occurrence(int sourceIndex, String label, String section, String version) {}
    public record Image(String id, String filename, String mimeType, long bytes, int sourceIndex, List<Occurrence> occurrences) {}
    public record Catalog(int totalOccurrences, int imageCount, int duplicateCount, int skippedCount, long totalBytes, List<Image> images) {}
    public record Manifest(int formatVersion, String snapshotId, String title, String url, long capturedAt, Catalog catalog) {}
    private static class Asset {
        final String id, filename, mimeType;
        final byte[] data;
        final int sourceIndex;
        final List<Occurrence> occurrences = new ArrayList<>();
        Asset(String id, String filename, String mimeType, byte[] data, int sourceIndex) {
            this.id=id; this.filename=filename; this.mimeType=mimeType; this.data=data; this.sourceIndex=sourceIndex;
        }
        Image metadata() { return new Image(id,filename,mimeType,data.length,sourceIndex,List.copyOf(occurrences)); }
    }
    public static final class Archive {
        private final Manifest manifest;
        private final List<Asset> assets;
        private Archive(Manifest manifest, List<Asset> assets) { this.manifest=manifest; this.assets=assets; }
        public Catalog catalog() { return manifest.catalog(); }
        public void writeZip(OutputStream output, ObjectMapper mapper) throws IOException {
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.setLevel(Deflater.BEST_SPEED);
                write(zip, "manifest.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
                for (Asset asset : assets) write(zip, "images/"+asset.filename, asset.data);
            }
        }
        private void write(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
            var entry = new ZipEntry(name); entry.setTime(manifest.capturedAt());
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
        }
    }

    public static Archive extract(WebDtos.Snapshot snapshot) {
        var doc = Jsoup.parseBodyFragment(snapshot.sourceHtml());
        var assets = new LinkedHashMap<String,Asset>();
        int index=0, total=0, skipped=0;
        long bytes=0;
        for (Element image : doc.select("img")) {
            int sourceIndex=index++;
            String src=image.attr("src");
            if (!SnapshotHtml.dataMedia(src,SnapshotHtml.IMAGE_TYPES)) { skipped++; continue; }
            byte[] data;
            try { data=Base64.getDecoder().decode(src.substring(src.indexOf(";base64,")+8)); }
            catch (IllegalArgumentException ex) { skipped++; continue; }
            if (data.length==0) { skipped++; continue; }
            String type=src.substring(5,src.indexOf(";base64,"));
            String hash;
            try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
            catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            String label=image.attr("alt").strip();
            Element panel=image.closest(".snapshot-tab-panel");
            Element heading=panel==null?null:panel.children().stream().filter(el->el.hasClass("snapshot-tab-heading")).findFirst().orElse(null);
            String version=heading==null?"":heading.text();
            String section=image.closest(".snapshot-gallery")!=null?"Gallery":image.closest(".snapshot-infobox")!=null?"信息框":"正文";
            Asset asset=assets.get(hash);
            if (asset==null) {
                String filename=String.format(Locale.ROOT,"%03d-%s.%s",assets.size()+1,filename(label),extension(type));
                asset=new Asset(hash,filename,type,data,sourceIndex); assets.put(hash,asset); bytes+=data.length;
            }
            asset.occurrences.add(new Occurrence(sourceIndex,label,section,version)); total++;
        }
        var catalog=new Catalog(total,assets.size(),total-assets.size(),skipped,bytes,assets.values().stream().map(Asset::metadata).toList());
        return new Archive(new Manifest(1,snapshot.id(),snapshot.title(),snapshot.url(),snapshot.capturedAt(),catalog),List.copyOf(assets.values()));
    }

    private static String extension(String type) {
        return switch(type) { case "image/jpeg" -> "jpg"; case "image/webp" -> "webp"; case "image/gif" -> "gif"; default -> "png"; };
    }
    private static String filename(String label) {
        String name=label.replaceFirst("(?i)\\.(?:png|jpe?g|gif|webp)(?:\\s*\\([^)]*\\))?$","")
                .replaceAll("[\\p{Cc}\\p{Cf}<>:\"/\\\\|?*]","_").replaceAll("\\s+"," ").strip()
                .replaceAll("^[. ]+|[. ]+$","");
        if (name.isBlank()) return "image";
        int end=name.offsetByCodePoints(0,Math.min(72,name.codePointCount(0,name.length())));
        return name.substring(0,end);
    }
}
