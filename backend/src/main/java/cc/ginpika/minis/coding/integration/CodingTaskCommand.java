package cc.ginpika.minis.coding.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** One parser for schema validation and execution. Normalization never grants authority. */
public final class CodingTaskCommand {
    private CodingTaskCommand() {}
    public record Parsed(ObjectNode args, boolean aliased) {}
    public static Parsed parse(JsonNode raw) {
        if (raw == null || !raw.isObject()) throw new IllegalArgumentException("编程工具参数必须是对象");
        ObjectNode args = ((ObjectNode) raw).deepCopy();
        Set<String> fields = Set.of("action", "repositoryId", "runId", "prompt", "input", "mode");
        args.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) throw new IllegalArgumentException("未知编程参数：" + key);
            if (!args.get(key).isTextual()) throw new IllegalArgumentException(key + " 必须是字符串");
        });
        String action = args.path("action").asText();
        if (!Set.of("list", "start", "read", "diff", "operations", "verify", "message", "resume", "cancel").contains(action))
            throw new IllegalArgumentException("action 必须是 list/start/read/diff/operations/verify/message/resume/cancel");
        if (!Set.of("list", "start").contains(action) && args.path("runId").asText().isBlank())
            throw new IllegalArgumentException("需要 runId");
        if (args.has("mode") && !Set.of("PLAN", "EDIT", "EXECUTE").contains(args.path("mode").asText()))
            throw new IllegalArgumentException("mode 仅支持 PLAN/EDIT/EXECUTE；执行授权仍由当前派工决定");
        boolean alias = false;
        if(args.has("repositoryId") && !"start".equals(action))throw new IllegalArgumentException(action+" 不接收 repositoryId");
        if(args.has("runId") && Set.of("start","list").contains(action))throw new IllegalArgumentException(action+" 不接收 runId");
        if("message".equals(action) && args.has("mode"))throw new IllegalArgumentException("message 不切换模式；请用 resume 指定 mode");
        if (Set.of("message", "resume").contains(action)) {
            String input = args.path("input").asText().strip(), prompt = args.path("prompt").asText().strip();
            if (!input.isEmpty() && !prompt.isEmpty() && !input.equals(prompt))
                throw new IllegalArgumentException("input 与 prompt 冲突，补充要求请使用 input");
            alias = input.isEmpty() && !prompt.isEmpty();
            args.put("input", alias ? prompt : input); args.remove("prompt");
            if ("message".equals(action) && args.path("input").asText().isBlank())
                throw new IllegalArgumentException("补充指令 input 不能为空");
            if (args.path("input").asText().length() > 20_000) throw new IllegalArgumentException("补充指令过长");
        } else if ("start".equals(action)) {
            if (args.path("repositoryId").asText().isBlank() || args.path("prompt").asText().isBlank())
                throw new IllegalArgumentException("开始编程任务需要 repositoryId 和 prompt");
            if (args.has("input")) throw new IllegalArgumentException("start 使用 prompt，补充或续跑使用 input");
        } else if (args.has("prompt") || args.has("input") || args.has("mode")) {
            throw new IllegalArgumentException(action + " 不接收指令或模式参数");
        }
        return new Parsed(args, alias);
    }
}
