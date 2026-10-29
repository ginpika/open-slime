package cc.ginpika.minis.ai.comfyui.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 生成结果即使被模型省略或写成普通链接，也要作为图片出现在最终会话正本中。 */
public final class GeneratedImageReply {
    private GeneratedImageReply() {}

    public static String appendMissing(String content, List<JsonNode> messages, ObjectMapper json) {
        Set<String> calls = new HashSet<>();
        Set<String> images = new LinkedHashSet<>();
        for (JsonNode message : messages) {
            for (JsonNode call : message.path("tool_calls")) {
                if (GenerateImageTool.NAME.equals(call.path("function").path("name").asText())) calls.add(call.path("id").asText());
            }
            if (!"tool".equals(message.path("role").asText()) || !calls.contains(message.path("tool_call_id").asText())) continue;
            try {
                JsonNode result = json.readTree(message.path("content").asText());
                if (result != null) for (JsonNode image : result.path("images")) {
                    String url = image.path("url").asText();
                    if (url.matches("/api/comfyui/images/[0-9a-f-]{36}")) images.add(url);
                }
            } catch (java.io.IOException ignored) { /* 工具错误文本不是图片结果。 */ }
        }
        String result = content == null ? "" : content;
        for (String url : images) {
            String pattern = "!\\[[^\\]]*\\]\\(\\s*<?" + java.util.regex.Pattern.quote(url) + ">?(?:\\s+\"[^\"]*\")?\\s*\\)";
            if (!java.util.regex.Pattern.compile(pattern).matcher(result).find()) result += "\n\n![生成图片](" + url + ")";
        }
        return result;
    }
}
