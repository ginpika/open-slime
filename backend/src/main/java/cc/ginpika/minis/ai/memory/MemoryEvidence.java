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
        ArrayNode refs = JSON.createArrayNode();
        refs.addObject().put("ref", kind + ":" + id).put("text", text == null ? "" : text)
                .put("observedAt", System.currentTimeMillis());
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
