package cc.ginpika.minis.ai.core.tool.heartbeat;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * list_heartbeats：列出「你自己」的常驻职责名册（心跳 Agent 维护与派出职责前先看它）。
 *
 * <p>潜意识改成「每个 Agent 各有一套」之后，名册不再自动注入正文——心跳 Agent 需要时自己调本工具。
 * 返回的正是当前调用 Agent 的 {@code heartbeat_plan} 名册（含名字、id、渠道、节拍、上次派出时间与
 * 正文开头），执行落在 {@code HeartbeatService} 的分发里（它知道当前 Agent 是谁）。
 *
 * <p>与编制工具（create_heartbeat / update_heartbeat / delete_heartbeat）一样 {@code agentsMayCall=false}：
 * 只有心跳 Agent 自己看得见，被派出去的职责 / 子代理拿不到。
 */
@Component
public class ListPlansTool implements AiTool {

    public static final String NAME = "list_heartbeats";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出你自己的「心跳职责」名册：每条含名字、id、交付渠道、节拍参考、上次被心跳派出的时间，"
                + "以及正文开头（方便你认出这条职责管什么）。心跳派活前先看名册，据此决定这一拍派谁。无需参数。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public boolean agentsMayCall() {
        return false;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return params;
    }
}
