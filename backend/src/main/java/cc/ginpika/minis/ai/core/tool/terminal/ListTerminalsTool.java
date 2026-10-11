package cc.ginpika.minis.ai.core.tool.terminal;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * list_terminals：列出当前可用的工作空间（{@link RunCommandTool} 的 target 取值来源）。
 *
 * <p>工作空间名是模型指认环境的唯一凭据，而环境是人在工作空间页配的、随时会变，所以「有哪些」
 * 必须能当场问出来——否则模型要么凭空猜名字，要么把一次失败当成「这台机器不能执行命令」。
 *
 * <p>只读无参数，故并行与否无关紧要；子代理同样不给（见 {@link RunCommandTool#agentsMayCall()}）。
 */
@Component
public class ListTerminalsTool implements AiTool {

    public static final String NAME = "list_terminals";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "列出当前可用的工作空间（docker 容器 / SSH 服务器），返回每个工作空间的名字、类型与所在位置。"
                + "在调用 " + RunCommandTool.NAME + " 之前先用它确认名字；"
                + "若返回为空，说明用户还没在工作空间页配置或启用，此时不要假设环境存在。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    /** 与 {@link RunCommandTool} 同属终端能力域：路由选「终端」时两件一起进 */
    @Override
    public ToolDomain domain() {
        return ToolDomain.TERMINAL;
    }

    /** 只读的「能碰什么」清单，与执行权同进同出：不给子代理 */
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
