package cc.ginpika.minis.ai.knowledge.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

/** Rendering is deterministic; generated prose never becomes executable HTML. */
public final class SnapshotHtml {
    private SnapshotHtml() {}
    public record Evidence(String html, List<WebDtos.Paragraph> paragraphs, List<String> warnings) {}
    public static final String STRUCTURE_CSS = ".snapshot-infobox{float:right;width:44%;max-width:320px;margin:0 0 20px 24px;background:#f8fafc;border:1px solid #cbd5e1;border-radius:8px;box-sizing:border-box}table.snapshot-infobox{display:table;table-layout:fixed}.snapshot-infobox img{display:block;max-width:100%;margin:0 auto}.snapshot-infobox dl{padding:0 12px}.snapshot-infobox dt{font-weight:600}.snapshot-infobox dd{margin:0 0 10px;overflow-wrap:anywhere}article{display:flow-root}figure{margin:20px 0}figcaption{color:#64748b;font-size:14px}details{margin:12px 0}summary{font-weight:600}@media(max-width:640px){.snapshot-infobox{float:none;width:100%;max-width:100%;margin:0 0 20px}}";
    public static final String TAB_GALLERY_CSS = ".snapshot-tabs{display:flex;flex-wrap:wrap;gap:4px;margin:16px 0;position:relative;clear:both}.snapshot-tab{display:contents}.snapshot-tab-control{position:absolute;width:1px;height:1px;opacity:0;overflow:hidden}.snapshot-tab-label{order:0;cursor:pointer;border:1px solid #cbd5e1;border-radius:6px;padding:5px 10px;font-size:14px;line-height:1.6;background:#fff;color:#475569}.snapshot-tab-control:checked+.snapshot-tab-label{background:#2563eb;border-color:#2563eb;color:#fff}.snapshot-tab-control:focus-visible+.snapshot-tab-label{outline:2px solid #2563eb;outline-offset:2px}.snapshot-tabs .snapshot-tab-panel{display:none;order:1;flex:0 0 100%;min-width:0;box-sizing:border-box;background:transparent;border:0;border-radius:0;padding:12px 0}.snapshot-tab-control:checked~.snapshot-tab-panel,.snapshot-tabs .snapshot-tab-panel:has(:target){display:block}.snapshot-tab-heading{display:none}.snapshot-gallery{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(160px,100%),1fr));gap:16px}.snapshot-gallery figure{margin:0;padding:8px;border:1px solid #e2e8f0;border-radius:8px;text-align:center;min-width:0}.snapshot-gallery img{display:block;width:auto;max-width:100%;height:auto;max-height:360px;object-fit:contain;margin:0 auto}.snapshot-gallery figcaption{overflow-wrap:anywhere;line-height:1.5;margin-top:8px}.snapshot-infobox .snapshot-tabs{margin:12px}.snapshot-infobox .snapshot-tab-label{padding:4px 7px;font-size:12px}@media print{.snapshot-tabs .snapshot-tab-panel{display:block}.snapshot-tab-label,.snapshot-tab-control{display:none}.snapshot-tab-heading{display:block}}";
    static final int MAX_IMAGES = 256;
    static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    static final Set<String> AUDIO_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-wav", "audio/webm", "audio/flac");
    static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/ogg");

    static boolean dataMedia(String src, Set<String> types) {
        int separator = src.indexOf(";base64,");
        return src.startsWith("data:") && separator > 5 && types.contains(src.substring(5, separator))
                && src.substring(separator + 8).matches("[A-Za-z0-9+/=]+");
    }

    public static Evidence clean(String raw, String baseUrl) {
        return clean(raw, baseUrl, WebDtos.MediaOptions.defaults());
    }

    public static Evidence clean(String raw, String baseUrl, WebDtos.MediaOptions media) {
        Document input = Jsoup.parseBodyFragment(raw, baseUrl);
        // Remove chrome before stripping its tags: otherwise its menu text leaks into the evidence.
        for (Element box : input.select(".infobox,.infobox_v2,.infobox_v3,.infobox-table,.unit-infobox,.portable-infobox,.character-infobox,.character-info,.profile-box,table.floatright")) box.addClass("snapshot-infobox");
        var rightFloat = java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*float\\s*:\\s*right\\b");
        for (Element table : input.select("table[style]")) if (rightFloat.matcher(table.attr("style")).find()) table.addClass("snapshot-infobox");
        input.select("nav,[role=navigation],[role=menu],[role=menubar],[role=banner],.site-header,.site-footer,.site-sidebar,.sidebar-menu,.nav-menu,.vector-menu,.vector-sidebar,#mw-navigation,#mw-head,#mw-panel,#toc,.toc,.navbox,.mw-editsection,.catlinks")
                .stream().filter(el -> el.closest(".snapshot-infobox") == null).toList().forEach(Element::remove);
        for (Element el : input.select("[class]")) {
            var classes = el.classNames().stream().filter(name -> switch (name) {
                case "snapshot-infobox" -> Set.of("table", "aside", "div", "section").contains(el.tagName());
                case "snapshot-tabs", "snapshot-gallery" -> el.tagName().equals("div");
                case "snapshot-tab-panel" -> el.tagName().equals("section");
                case "snapshot-tab-heading" -> el.tagName().equals("h3");
                default -> false;
            }).toList();
            el.removeAttr("class");
            if (!classes.isEmpty()) el.attr("class", String.join(" ", classes));
        }
        int images = input.select("img").size();
        boolean dynamic = !input.select("svg,canvas,iframe,object,embed,math").isEmpty();
        input.select("script,style,noscript,iframe,form,input,button,svg,object,embed").remove();
        if (!media.includeImages()) input.select("img").remove();
        // External resources are not silently retained as dependencies of an offline snapshot.
        for (Element img : input.select("img")) {
            String src = img.attr("src");
            if (!dataMedia(src, IMAGE_TYPES)) {
                img.replaceWith(new Element("p").text("[图片未离线保存] " + img.attr("alt")));
            }
        }
        for (Element el : input.select("audio,video")) {
            boolean audio = el.tagName().equals("audio");
            if (!(audio ? media.includeAudio() : media.includeVideo())) { el.remove(); continue; }
            Set<String> types = audio ? AUDIO_TYPES : VIDEO_TYPES;
            el.select("source").stream().filter(source -> !dataMedia(source.attr("src"), types)).toList().forEach(Element::remove);
            if (!dataMedia(el.attr("src"), types)) el.removeAttr("src");
            if (!el.hasAttr("src") && el.select("source").isEmpty()) {
                el.replaceWith(new Element("p").text("[" + (audio ? "音频" : "视频") + "未离线保存] 资源不可获取、格式不支持或超出大小限制"));
            } else {
                el.attr("controls", "").attr("preload", "none");
                el.removeAttr("autoplay");
                if (!media.includeImages() || !dataMedia(el.attr("poster"), IMAGE_TYPES)) el.removeAttr("poster");
                el.select("track").remove();
            }
        }
        Safelist allowed = Safelist.relaxed().removeTags("img").addTags("img", "section", "hr", "audio", "video", "source", "aside", "div", "figure", "figcaption", "details", "summary", "dl", "dt", "dd")
                .addAttributes(":all", "class").addAttributes("section", "data-snapshot-label")
                .addAttributes("details", "open").addAttributes(":all", "lang", "dir")
                .addAttributes("img", "src", "alt").addProtocols("img", "src", "data")
                .addAttributes("audio", "src", "controls", "preload").addProtocols("audio", "src", "data")
                .addAttributes("video", "src", "controls", "preload", "poster").addProtocols("video", "src", "data").addProtocols("video", "poster", "data")
                .addAttributes("source", "src", "type").addProtocols("source", "src", "data")
                .addAttributes("a", "rel").addEnforcedAttribute("a", "rel", "noreferrer noopener")
                .preserveRelativeLinks(false);
        Document doc = new Cleaner(allowed).clean(input);
        doc.select("details").attr("open", ""); // Knowledge folds become visible in a static snapshot.
        doc.outputSettings().prettyPrint(false);
        List<WebDtos.Paragraph> paragraphs = new ArrayList<>();
        for (Element el : doc.select("h1,h2,h3,h4,h5,h6,p,li,pre,table,blockquote,dl,figcaption,div")) {
            if (el.tagName().equals("div") && !el.children().select("div,section,aside,details,figure,h1,h2,h3,h4,h5,h6").isEmpty()) continue;
            if (!el.children().select("p,li,pre,table,blockquote,dl").isEmpty()
                    && !Set.of("table", "pre", "dl").contains(el.tagName())) continue;
            if (el.parents().stream().anyMatch(p -> Set.of("table", "pre", "dl").contains(p.tagName())
                    || (el.tagName().equals("div") && Set.of("p", "li", "figcaption").contains(p.tagName())))) continue;
            String text = el.wholeText().strip();
            if (text.isBlank()) continue;
            String id = "p" + (paragraphs.size() + 1);
            el.attr("id", id);
            paragraphs.add(new WebDtos.Paragraph(id, text));
        }
        if (paragraphs.isEmpty() && !doc.body().text().isBlank()) {
            String text = doc.body().text();
            Element paragraph = new Element("p").attr("id", "p1");
            for (var node : new ArrayList<>(doc.body().childNodes())) paragraph.appendChild(node);
            doc.body().appendChild(paragraph);
            paragraphs.add(new WebDtos.Paragraph("p1", text));
        }
        List<String> warnings = new ArrayList<>();
        if (dynamic) warnings.add("页面包含图形、公式或嵌入内容；当前快照主要保留正文，请核查是否丢失关键信息");
        for (String kind : List.of("图片", "音频", "视频")) {
            long missing = doc.select("p").stream().filter(p -> p.text().startsWith("[" + kind + "未离线保存]")).count();
            if (missing > 0) warnings.add(missing + " 项" + kind + "无法离线保存；请检查是否影响理解");
        }
        if (media.includeImages() && images > MAX_IMAGES) warnings.add("图片较多，优先保存 Gallery 和信息框，最多尝试 " + MAX_IMAGES + " 张图片");
        buildTabs(doc);
        return new Evidence(doc.body().html(), List.copyOf(paragraphs), List.copyOf(warnings));
    }

    /** Controls are generated only after sanitization; no source form, handler or script is retained. */
    private static void buildTabs(Document doc) {
        int groupIndex = 0;
        for (Element group : doc.select("div.snapshot-tabs")) {
            var panels = group.children().stream().filter(el -> el.hasClass("snapshot-tab-panel")).toList();
            if (panels.size() < 2 || panels.size() > 40) {
                group.removeClass("snapshot-tabs");
                panels.forEach(panel -> { panel.removeClass("snapshot-tab-panel"); panel.select(".snapshot-tab-heading").removeClass("snapshot-tab-heading"); });
                continue;
            }
            String name = "snapshot-tabs-" + (++groupIndex);
            int panelIndex = 0;
            for (Element panel : panels) {
                String label = panel.attr("data-snapshot-label").strip();
                if (label.isBlank()) label = "版本 " + (panelIndex + 1);
                if (label.length() > 120) label = label.substring(0, 120);
                String id = name + "-" + panelIndex;
                Element tab = new Element("div").addClass("snapshot-tab");
                panel.before(tab); panel.remove();
                Element control = tab.appendElement("input").addClass("snapshot-tab-control")
                        .attr("type", "radio").attr("name", name).attr("id", id).attr("aria-label", label);
                if (panelIndex++ == 0) control.attr("checked", "");
                tab.appendElement("label").addClass("snapshot-tab-label").attr("for", id).text(label);
                tab.appendChild(panel);
            }
        }
        doc.select("[data-snapshot-label]").removeAttr("data-snapshot-label");
    }

    public static String hash(String html) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(html.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String text(String value) { return new Element("span").text(value == null ? "" : value).html(); }

    public static String render(WebDtos.Snapshot snapshot) {
        StringBuilder distilled = new StringBuilder();
        if (!snapshot.summary().isBlank()) distilled.append("<section><h2>蒸馏摘要（模型生成）</h2><p>")
                .append(text(snapshot.summary())).append("</p>");
        if (!snapshot.claims().isEmpty()) {
            if (distilled.isEmpty()) distilled.append("<section><h2>蒸馏论点（模型生成）</h2>");
            for (WebDtos.Claim claim : snapshot.claims()) {
                distilled.append("<p><small>").append(text(claim.kind())).append("</small> ")
                        .append(text(claim.text())).append(" ");
                for (String ref : claim.evidence()) distilled.append("<a href=\"#").append(ref)
                        .append("\">[").append(ref).append("]</a> ");
                distilled.append("</p>");
            }
        }
        if (!distilled.isEmpty()) distilled.append("</section>");
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\">"
                + "<title>" + text(snapshot.title()) + "</title><style>"
                + "body{max-width:780px;margin:40px auto;padding:0 24px;color:#20242c;background:#fff;font:16px/1.8 system-ui,sans-serif}"
                + "h1{font-size:30px;line-height:1.3}h2{font-size:23px}a{color:#2563eb;overflow-wrap:anywhere}"
                + "img,video{max-width:100%;height:auto}audio{width:100%}pre{overflow:auto;background:#f3f4f6;padding:16px}table{border-collapse:collapse;display:block;overflow:auto}"
                + "td,th{border:1px solid #ddd;padding:8px}section{background:#f8fafc;padding:20px;border:1px solid #e2e8f0;border-radius:12px}"
                + "small,.meta{color:#64748b}p,li{overflow-wrap:anywhere}:target{background:#fef3c7}"
                + STRUCTURE_CSS
                + TAB_GALLERY_CSS
                + "</style></head><body><h1>" + text(snapshot.title()) + "</h1><p class=\"meta\">来源：<a href=\""
                + text(snapshot.url()) + "\" target=\"_blank\" rel=\"noreferrer noopener\">" + text(snapshot.url())
                + "</a><br>作者：" + text(snapshot.author()) + " · 原发布时间：" + text(snapshot.publishedAt())
                + "<br>抓取：" + java.time.Instant.ofEpochMilli(snapshot.capturedAt())
                + " · SHA-256：" + snapshot.contentHash() + "</p>"
                + (snapshot.warnings().isEmpty() ? "" : "<p class=\"meta\">" + text(String.join("；", snapshot.warnings())) + "</p>")
                + distilled + "<h2>正文证据</h2><article>" + snapshot.sourceHtml() + "</article></body></html>";
    }
}
