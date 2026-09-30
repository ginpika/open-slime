package cc.ginpika.minis.ai.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出的容错 JSON 解析：剥 markdown 围栏 + 坏数据回退空对象。
 * 工具调用参数解析、模型 content 中的 JSON 文本提取共用这一处。
 */
public final class LlmJson {

    /** 模型输出可能包裹的 markdown 围栏（```json ... ```） */
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private static final ObjectMapper OM = new ObjectMapper();

    private LlmJson() {
    }

    /** 剥离模型输出可能包裹的 markdown 围栏（```json ... ```）；无围栏则原样返回 */
    public static String stripFence(String text) {
        if (text == null) {
            return "";
        }
        Matcher fence = FENCE.matcher(text);
        return fence.find() ? fence.group(1) : text;
    }

    /** 容错解析 JSON 文本；空/坏数据回退空对象（工具调用参数解析等场景） */
    public static JsonNode readTree(String json) {
        try {
            return OM.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return OM.createObjectNode();
        }
    }
}
