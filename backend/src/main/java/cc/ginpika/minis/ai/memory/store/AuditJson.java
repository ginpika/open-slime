package cc.ginpika.minis.ai.memory.store;

/**
 * 审计摘要的最小 JSON 序列化（memory-v6 §6.1）：只用于 {@code memory_revision.before/after}，
 * 避免为一个「紧凑摘要」把 Jackson 引进底层 store。
 *
 * <p>约定：{@code kv("owner", "AGENT", "layer", "L2")} → {@code {"owner":"AGENT","layer":"L2"}}；
 * 空值对跳过；null 输入返回 null（表示「无此侧信息」）。
 */
public final class AuditJson {

    private AuditJson() {
    }

    public static String kv(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            Object value = pairs[i + 1];
            if (key == null || value == null) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(escape(String.valueOf(key))).append("\":\"")
                    .append(escape(String.valueOf(value))).append('"');
        }
        return sb.append('}').toString();
    }

    /** 数值型键值（不外加引号） */
    public static String num(String key, long value) {
        return "{\"" + escape(key) + "\":" + value + "}";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }
}
