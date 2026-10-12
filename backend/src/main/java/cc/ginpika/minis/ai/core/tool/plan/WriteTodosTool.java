package cc.ginpika.minis.ai.core.tool.plan;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolRisk;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * write_todos：写下／更新这一轮活的「待办清单」（plan-of-record，STEP 4）。
 *
 * <p>这是<b>任务分解</b>：把一件多步的活拆成几条可跟踪的小步，边做边把状态改成进行中 / 完成 / 失败。
 * 与 {@code create_heartbeat} 的区别（二者语义不同，别混用）：那个立的是<b>常驻编制</b>——有名字、有节拍、
 * 由心跳反复派出去；本工具写的是<b>这一轮活的清单</b>——随会话存档，做完即尘封。
 *
 * <p>写入是<b>全量覆盖</b>式的：每次调用把该会话的清单整体替换，调用方给的是「当前完整视图」。
 * 于是模型改一条状态时把它连同其余几条一起写回，清单永远只有一份、不会分叉。
 *
 * <p>写完的清单会随下一轮上下文回灌 system（见 {@code TodoProvider}），模型因此不必自己在正文里
 * 复述进度；人也能从工具调用痕迹里看到这份清单怎么变的。
 */
@Component
public class WriteTodosTool implements AiTool {

    public static final String NAME = "write_todos";

    /** 清单条数上限：拆分是为了可跟踪，不是越多越好——超过这个数该合并同类项 */
    public static final int MAX_ITEMS = 20;

    /** 单条内容长度上限：一句「可判断做没做完」的祈使句用不了这么长，超了就是在写小作文 */
    public static final int MAX_CONTENT_LEN = 200;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "写下或更新这一轮活的「待办清单」：把一件多步的活拆成几条可跟踪的小步，"
                + "边做边把每条的状态改成进行中 / 完成 / 失败。每次调用请给出<b>当前完整清单</b>"
                + "（覆盖式写入，不是追加）——改了一条状态就把其余几条一并带上。"
                + "清单会随下一轮上下文回灌给你，于是你不必在正文里复述进度，照它往下做即可；"
                + "哪一步卡住了、失败了，就在那条的 note 里写清原因。"
                + "适合需要多步、结果要人能跟的活（调研、改造、排障）；"
                + "一句话能答完的别写清单，也别拿它当便签记无关的事。"
                + "每条 content 控制在 " + MAX_CONTENT_LEN + " 字内（一句祈使句），细节写 note。"
                + "要派活给人/子代理时用 " + DelegateSubagentTool.NAME + "。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    /** 控制类：只在主对话面上由大模型自己维护，子代理不写别人的清单 */
    @Override
    public boolean agentsMayCall() {
        return false;
    }

    /** 写会话内的清单状态：算写入（只读授权下应被挡），但不进知识库，故 {@link #writes()} 仍为 false */
    @Override
    public ToolRisk risk() {
        return ToolRisk.WRITE;
    }

    /** 覆盖式写入时至少给一条：空清单无从表达「做完了」，清空请留一条 done 的收尾项 */
    @Override
    public String validate(com.fasterxml.jackson.databind.JsonNode args) {
        if (args == null || !args.path("todos").isArray() || args.path("todos").isEmpty()) {
            return "todos 不能为空：请给出当前完整清单（至少一条）。若这轮已收尾，留一条"
                    + "标 done 的收尾项说明结论即可。";
        }
        if (args.path("todos").size() > MAX_ITEMS) {
            return "todos 最多 " + MAX_ITEMS + " 条：请合并同类项，拆分是为了可跟踪而不是事无巨细。";
        }
        for (com.fasterxml.jackson.databind.JsonNode n : args.path("todos")) {
            String content = n.path("content").asText("");
            if (content.length() > MAX_CONTENT_LEN) {
                return "单条内容最多 " + MAX_CONTENT_LEN + " 字（当前 " + content.length()
                        + " 字）：请压缩成一句可判断做没做完的祈使句（细节写进 note，或拆成另一步）。";
            }
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode todos = props.putObject("todos");
        todos.put("type", "array");
        todos.put("description", "当前完整清单（覆盖式：本次调用后的清单就是它）");
        ObjectNode item = todos.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("content").put("type", "string")
                .put("description", "这一步要做什么（一句祈使句，具体到可判断做没做完）");
        ObjectNode status = ip.putObject("status");
        status.put("type", "string");
        status.put("description", "这一步的状态");
        ArrayNode states = status.putArray("enum");
        states.add("pending").add("in_progress").add("done").add("failed");
        ip.putObject("note").put("type", "string")
                .put("description", "结果 / 失败原因（可选；收尾时写上更可读）");
        item.putArray("required").add("content").add("status");
        params.putArray("required").add("todos");
        return params;
    }
}
