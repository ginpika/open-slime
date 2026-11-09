package cc.ginpika.minis.ai.core.tool.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Schema for the personal-chat approval pilot. Only the personal-chat runner registers it. */
public final class RequestTodoCompletionTool {
    public static final String NAME = "request_todo_completion";
    public static final int MAX_NOTE_LENGTH = 500;

    private RequestTodoCompletionTool() {}

    public static ObjectNode schema(ObjectMapper json) {
        ObjectNode tool = json.createObjectNode().put("type", "function");
        ObjectNode fn = tool.putObject("function").put("name", NAME).put("description",
                "当排障阶段已完成、需要用户核对并确认将当前清单中的一项未开始待办标为完成时使用。"
                        + "按当前待办清单里的序号选择一项，可附上验收结果。必须单独调用。"
                        + "提案成功后本轮暂停，由用户在界面上确认或拒绝。"
                        + "等待确认期间不能用 write_todos 代为提交这项变更。");
        ObjectNode params = fn.putObject("parameters").put("type", "object").put("additionalProperties", false);
        ObjectNode fields = params.putObject("properties");
        fields.putObject("item").put("type", "integer").put("minimum", 1)
                .put("description", "当前待办清单显示的序号，从 1 开始");
        fields.putObject("note").put("type", "string").put("maxLength", MAX_NOTE_LENGTH)
                .put("description", "可选的验收结果，用户确认后写入待办备注");
        params.putArray("required").add("item");
        return tool;
    }
}
