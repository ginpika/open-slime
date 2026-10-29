package cc.ginpika.minis.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.LinkedHashMap;

/** 来源由调用方提供；模型只能选择可核对的原话，不能自造来源 ID。 */
public final class MemoryEvidence {
    private static final ObjectMapper JSON = new ObjectMapper();
    private MemoryEvidence() { }

    public static String ref(String kind, long id, String text) {
        return ref(kind, id, text, System.currentTimeMillis());
    }

    public static String ref(String kind, long id, String text, long observedAt) {
        ArrayNode refs = JSON.createArrayNode();
        refs.addObject().put("ref", kind + ":" + id).put("text", text == null ? "" : text)
                .put("observedAt", observedAt);
        return refs.toString();
    }

    public static String merge(String a, String b) {
        LinkedHashMap<String, JsonNode> distinct = new LinkedHashMap<>();
        for (String value : new String[]{a, b}) {
            try {
                JsonNode arr = JSON.readTree(value == null ? "[]" : value);
                if (!arr.isArray()) { continue; }
                for (JsonNode node : arr) {
                    String key = node.isObject() ? node.path("ref").asText("") : node.asText();
                    if (!key.isBlank()) { distinct.put(key, node); }
                }
            } catch (Exception ignored) { }
        }
        ArrayNode out = JSON.createArrayNode(); distinct.values().forEach(out::add);
        return out.toString();
    }

    public static int count(String refs) {
        try { return JSON.readTree(merge(null, refs)).size(); }
        catch (Exception ignored) { return 0; }
    }

    /** 兼容早期的事件 ID 数组和现在的带类型证据引用。 */
    public static java.util.Set<String> references(String refs) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            JsonNode arr = JSON.readTree(refs == null ? "[]" : refs);
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    String ref = n.isObject() ? n.path("ref").asText("")
                            : n.isNumber() ? "memory_event:" + n.asLong() : n.asText("");
                    if (!ref.isBlank()) { out.add(ref); }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static long latestObservation(String refs) {
        long at = 0;
        try { for (JsonNode n : JSON.readTree(merge(null, refs))) { at = Math.max(at, n.path("observedAt").asLong()); } }
        catch (Exception ignored) { }
        return at;
    }

    public static String userQuote(String refs, String quote) {
        ArrayNode out = JSON.createArrayNode();
        if (quote == null || quote.isBlank()) { return out.toString(); }
        try {
            for (JsonNode n : JSON.readTree(merge(null, refs))) {
                if (n.path("ref").asText("").startsWith("chat_message:")
                        && n.path("text").asText("").contains(quote)) { out.add(n); }
            }
        } catch (Exception ignored) { }
        return out.toString();
    }
}
