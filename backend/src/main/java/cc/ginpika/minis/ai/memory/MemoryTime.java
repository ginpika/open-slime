package cc.ginpika.minis.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import java.util.regex.Pattern;

/** Source-anchored time semantics. Unknown event time is never replaced with ingestion time. */
public final class MemoryTime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RELATIVE = Pattern.compile("大前天|前天|昨天|今天|明天|后天|今晚|今早|昨晚|刚才|现在|上周|本周|下周|去年|今年|明年");
    public record Frame(String kind, Long start, Long end, String precision, String partOfDay,
                        String zone, Long anchorAt, String sourceRef, String expression, String attribution) {}
    private MemoryTime() {}

    public static ZoneId zone(String value) {
        try { return ZoneId.of(value); } catch (Exception e) { return ZoneId.systemDefault(); }
    }

    /** Called at intake, before batching or retry. Never manufacture timestamps for historical references. */
    public static String anchor(String refs, String zone) {
        try {
            JsonNode values = JSON.readTree(MemoryEvidence.merge(null, refs));
            for (JsonNode n : values) if (n.isObject() && !n.hasNonNull("timeZone")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode)n).put("timeZone", zone(zone).getId());
            }
            return values.toString();
        } catch (Exception e) { throw new IllegalArgumentException("记忆时间来源无效", e); }
    }

    public static String material(String refs) {
        try {
            var out = JSON.createArrayNode();
            for (JsonNode n : JSON.readTree(MemoryEvidence.merge(null, refs))) {
                if (!n.isObject() || !n.hasNonNull("observedAt") || n.path("ref").asText().startsWith("graph_")) continue;
                var item = out.addObject();
                item.put("source_ref", n.path("ref").asText());
                item.set("observedAt", n.path("observedAt"));
                item.put("timeZone", n.path("timeZone").asText("unknown"));
                long at = n.path("observedAt").asLong();
                if (at > 0 && n.hasNonNull("timeZone")) item.put("localDateTime", Instant.ofEpochMilli(at).atZone(zone(n.path("timeZone").asText())).toString());
            }
            return out.isEmpty() ? "来源时间未知；不得以处理时间解释相对时间。" : out.toString();
        } catch (Exception e) { return "来源时间未知；不得以处理时间解释相对时间。"; }
    }

    public static Frame extract(JsonNode temporal, String refs, String body, String scope) {
        String kind = temporal.path("kind").asText("unknown");
        if (!List.of("event", "state", "timeless", "unknown").contains(kind)) kind = "unknown";
        if ("episode".equals(scope)) kind = "event";
        String ref = temporal.path("source_ref").asText("");
        JsonNode source = null;
        try {
            for (JsonNode n : JSON.readTree(MemoryEvidence.merge(null, refs))) {
                if (n.path("ref").asText().equals(ref) && !ref.startsWith("graph_job:") && !ref.startsWith("graph_episode:")) { source = n; break; }
            }
        } catch (Exception ignored) { }
        String expression = temporal.path("expression").asText("");
        // A model may only resolve a phrase actually present in its selected source.
        boolean supported = source != null && !expression.isBlank() && source.path("text").asText().contains(expression);
        Long anchor = source == null || source.path("observedAt").asLong() <= 0 ? null : source.path("observedAt").asLong();
        String zone = source == null ? null : source.path("timeZone").asText(null);
        try { if(zone!=null) ZoneId.of(zone); } catch(DateTimeException e) { zone=null; }
        Long start = null, end = null;
        String precision = "unknown", part = "";
        if (supported) {
            for (String p : List.of("凌晨", "早上", "上午", "中午", "下午", "晚上", "傍晚", "夜里")) if (expression.contains(p)) { part = p; break; }
            if (expression.contains("今早")) part = "早上";
            if (expression.contains("今晚") || expression.contains("昨晚")) part = "晚上";
            LocalDate date = null;
            var absolute = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(expression);
            try {
                if (absolute.find()) date = LocalDate.parse(absolute.group());
                else if (anchor != null && zone != null) {
                    LocalDate base = Instant.ofEpochMilli(anchor).atZone(zone(zone)).toLocalDate();
                    if (expression.contains("大前天")) date = base.minusDays(3);
                    else if (expression.contains("前天")) date = base.minusDays(2);
                    else if (expression.contains("昨天") || expression.contains("昨晚")) date = base.minusDays(1);
                    else if (expression.contains("今天") || expression.contains("今早") || expression.contains("今晚")) date = base;
                    else if (expression.contains("明天")) date = base.plusDays(1);
                    else if (expression.contains("后天")) date = base.plusDays(2);
                }
                if (date != null && zone != null) {
                    start = date.atStartOfDay(zone(zone)).toInstant().toEpochMilli();
                    end = date.plusDays(1).atStartOfDay(zone(zone)).toInstant().toEpochMilli();
                    precision = part.isBlank() ? "day" : "part_of_day";
                }
                // A persistent state that starts today does not automatically end tonight.
                if ("state".equals(kind)) end = null;
                // Accept precise times only when the actual ISO text is present in the source phrase.
                String explicitStart=temporal.path("start").asText("");
                String explicitEnd=temporal.path("end").asText("");
                if(zone!=null && explicitStart.contains("T") && expression.contains(explicitStart)) {
                    start=instant(explicitStart,zone(zone)); precision="explicit";
                }
                if(zone!=null && !explicitEnd.isBlank() && expression.contains(explicitEnd)) end=instant(explicitEnd,zone(zone));
            } catch (DateTimeException ignored) { start = null; end = null; }
        }
        if (start != null && end != null && end <= start) end = null;
        if (RELATIVE.matcher(body).find() && start == null && "timeless".equals(kind)) kind = "unknown";
        String sourceRef=source!=null ? ref : MemoryEvidence.references(refs).stream().filter(r->!r.startsWith("graph_job:")).findFirst().orElse(null);
        return new Frame(kind, start, end, precision, part, zone, anchor, sourceRef,
                supported ? expression : "", ref.startsWith("chat_message:") && source != null ? "user_report" : "unverified");
    }

    public static String encode(Frame frame) {
        try { return JSON.writeValueAsString(frame); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static Frame decode(String value) {
        try { return value == null ? null : JSON.readValue(value, Frame.class); } catch (Exception e) { return null; }
    }
    public static String normalize(String body, Frame frame) {
        if (frame.start() == null) return "event".equals(frame.kind()) && frame.sourceRef() != null
                ? "[发生时间未知；来源 " + frame.sourceRef() + "] " + body : body;
        String date = Instant.ofEpochMilli(frame.start()).atZone(zone(frame.zone())).toLocalDate().toString();
        String resolved = "explicit".equals(frame.precision()) ? Instant.ofEpochMilli(frame.start()).atZone(zone(frame.zone())).toString()
                : date + (frame.partOfDay().isBlank() ? "" : " " + frame.partOfDay());
        String replacement=frame.expression().replaceAll("大前天|前天|昨天|今天|明天|后天",date)
                .replace("今早",date+"早上").replace("今晚",date+"晚上").replace("昨晚",date+"晚上");
        String normalized = frame.expression().isBlank() || !RELATIVE.matcher(frame.expression()).find()
                ? body : body.replace(frame.expression(), replacement);
        return "event".equals(frame.kind()) ? "[发生于 " + resolved + "] " + normalized : normalized;
    }
    public static String label(String json) {
        Frame t = decode(json);
        if (t == null) return "时间未标定，相对日期不得按当前日期解释";
        String kind = switch (t.kind()) { case "event" -> "经历"; case "state" -> "状态"; case "timeless" -> "长期信息"; default -> "时间类型待核实"; };
        if("timeless".equals(t.kind())) return kind;
        if (t.start() == null) return kind + " · 发生/生效时间未知"
                + (t.anchorAt()!=null && t.zone()!=null ? " · 原陈述于 "+Instant.ofEpochMilli(t.anchorAt()).atZone(zone(t.zone())) : "")
                + "，相对日期不得按当前日期解释";
        String date = Instant.ofEpochMilli(t.start()).atZone(zone(t.zone())).toLocalDate().toString();
        if ("explicit".equals(t.precision())) date = Instant.ofEpochMilli(t.start()).atZone(zone(t.zone())).toString();
        String range = t.end() != null && "explicit".equals(t.precision()) ? " 至 " + Instant.ofEpochMilli(t.end()).atZone(zone(t.zone())) : "";
        return kind + " · " + date + range + " " + t.partOfDay() + " · " + t.zone() + ("user_report".equals(t.attribution()) ? " · 用户自述" : " · 未核实");
    }
    public static Long instant(String text, ZoneId zone) {
        if (text == null || text.isBlank() || "null".equals(text)) return null;
        try { return Instant.parse(text).toEpochMilli(); } catch (DateTimeException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant().toEpochMilli(); } catch (DateTimeException ignored) { }
        try { return LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli(); } catch (DateTimeException ignored) { }
        return LocalDate.parse(text).atStartOfDay(zone).toInstant().toEpochMilli();
    }
    /** Interval overlap; unknown times do not match a dated request. End is exclusive. */
    public static String filter(Long from, Long to, String kind, List<Object> args) {
        String sql = "";
        if(from!=null || to!=null) sql+=" AND json_extract(temporal_json,'$.start') IS NOT NULL";
        if (from != null) { sql += " AND (CASE WHEN json_extract(temporal_json,'$.kind')='state' THEN COALESCE(invalid_at,json_extract(temporal_json,'$.end'),9223372036854775807) ELSE COALESCE(json_extract(temporal_json,'$.end'),json_extract(temporal_json,'$.start')+1) END)>?"; args.add(from); }
        if (to != null) { sql += " AND json_extract(temporal_json,'$.start')<?"; args.add(to); }
        if (kind != null && !kind.isBlank()) { sql += " AND json_extract(temporal_json,'$.kind')=?"; args.add(kind); }
        return sql;
    }
}
