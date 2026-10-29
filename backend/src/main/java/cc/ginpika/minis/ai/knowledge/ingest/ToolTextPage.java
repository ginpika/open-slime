package cc.ginpika.minis.ai.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Bounded tool output with an explicit continuation; offsets use Java/JS UTF-16 units. */
public final class ToolTextPage {
    private ToolTextPage() {}

    public static ObjectNode slice(ObjectMapper om, String text, int offset, int requestedLimit) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("offset 超出范围，应为 0～" + text.length());
        int limit = Math.max(1, Math.min(20_000, requestedLimit));
        if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                && Character.isHighSurrogate(text.charAt(offset - 1)))
            throw new IllegalArgumentException("offset 位于 Unicode 字符中间，请使用上一页 next_offset");
        int end = Math.min(text.length(), offset + limit);
        if (end < text.length() && end > offset && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end = end - 1 == offset ? end + 1 : end - 1;
        var result = om.createObjectNode();
        result.put("text", text.substring(offset, end));
        result.put("offset", offset); result.put("total_chars", text.length());
        result.put("has_more", end < text.length());
        if (end < text.length()) result.put("next_offset", end); else result.putNull("next_offset");
        return result;
    }
}
