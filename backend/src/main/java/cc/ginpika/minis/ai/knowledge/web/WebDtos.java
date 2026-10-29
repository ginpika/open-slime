package cc.ginpika.minis.ai.knowledge.web;

import java.util.List;

/** Browser observations and immutable, source-backed web documents. */
public final class WebDtos {
    private WebDtos() {}
    public record Command(String browserId, String action, String url, String ref,
                          String text, Integer offset, Integer x, Integer y, Double deltaX, Double deltaY) {
        public Command(String browserId, String action, String url, String ref,
                       String text, Integer offset, Integer x, Integer y) {
            this(browserId, action, url, ref, text, offset, x, y, null, null);
        }
    }
    public record Element(String ref, String tag, String label, String href) {}
    /** Lightweight session metadata; listing never refreshes element references or takes screenshots. */
    public record BrowserTab(String browserId, String url, String title, boolean current) {}
    public record BrowserView(String browserId, String chatSessionId, String url, String title,
                              String text, int totalChars, int offset, List<Element> elements,
                              boolean blocked, String screenshot) {}
    public record Paragraph(String id, String text) {}
    public record MediaOptions(Boolean images, Boolean audio, Boolean video) {
        public boolean includeImages() { return images == null || images; }
        public boolean includeAudio() { return Boolean.TRUE.equals(audio); }
        public boolean includeVideo() { return Boolean.TRUE.equals(video); }
        public static MediaOptions defaults() { return new MediaOptions(true, false, false); }
    }
    public record Capture(String captureId, String browserId, String url, String title,
                          String author, String publishedAt, long capturedAt, String contentHash,
                          List<Paragraph> paragraphs, List<String> warnings, String sourceHtml) {}
    public record Claim(String text, String kind, List<String> evidence) {}
    public record SaveRequest(String captureId, String title, String summary, String rationale,
                              List<Claim> claims, String model) {}
    public record DistillRequest(String captureId, String instruction) {}
    public record Snapshot(String id, Long docId, String url, String title, String author,
                           String publishedAt, long capturedAt, long savedAt, String contentHash,
                           String summary, String rationale, List<Claim> claims, String model,
                           List<String> warnings, String indexStatus, boolean enabled,
                           String sourceHtml, String html) {}
    public record Toggle(boolean enabled) {}
    public record Rename(String title) {}
    public record Renamed(String id, String title) {}
}
