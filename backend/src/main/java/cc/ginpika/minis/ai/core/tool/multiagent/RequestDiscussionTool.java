package cc.ginpika.minis.ai.core.tool.multiagent;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * request_discussion：执行遇阻——请再次激活讨论，让大家一起决定怎么办。
 *
 * <p>它是「执行态 → 讨论态」的开关：执行线程里发现前置条件不成立、方案有坑、需要换人 / 拆条目时，
 * 调用它把问题抛回桌面。全桌据此动态增删改共享任务区（改条目、加前置、换负责人、甚至推翻结论），
 * 讨论完再由 {@link StartWorkTool} 重新开工——这就是那条全链路 loop 的回边。
 */
@Component
public class RequestDiscussionTool implements AiTool {

    public static final String NAME = "request_discussion";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "执行者遇阻时回报主控：reason 写清问题、已尝试方法、证据和需要的决定，with 可建议相关成员。"
                + "由主控集中处理，不自行扩张待办或创建议题。主控在规划时调用此工具，则邀请 with 指定的成员"
                + "进入独立议题提供意见，结论回传后由主控决定下一步。with=用户 会暂停任务等待用户决定。"
                + "只有真正缺少条件或需要设计决策时才请求讨论，任务内部能判断的工作自行完成。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.PEER);
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(com.fasterxml.jackson.databind.JsonNode args) {
        String reason = args == null ? "" : args.path("reason").asText("").strip();
        return reason.isEmpty() ? "reason 不能为空：写清卡在哪、已经试过什么、需要什么决定。" : null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("reason").put("type", "string")
                .put("description", "卡在哪、已经试过什么、需要大家决定什么");
        props.putObject("with").put("type", "string")
                .put("description", "决策：找谁。可多个成员名（用「、」分隔，只你们定向沟通一轮）；"
                        + "写「用户」= 请用户拍板（全桌暂停等用户发言）；留空或填「全员」= 全桌一起议");
        params.putArray("required").add("reason");
        return params;
    }
}
