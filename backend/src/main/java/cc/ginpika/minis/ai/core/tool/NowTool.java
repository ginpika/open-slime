package cc.ginpika.minis.ai.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * now：返回当前本地日期时间（{@code yyyy-MM-dd HH:mm:ss}）。
 *
 * <p>「注入器」退役后，运行时素材一律由模型自己调工具现取。当前时间是最常用的一件：心跳与常驻职责
 * 每一拍都需要知道「现在是何时」，于是它成了一个无参数的只读工具，落在 {@link ToolSurface#CHAT}
 * 与 {@link ToolSurface#HEARTBEAT} 两个面上，由 {@code KnowledgeToolbox} 分发。
 */
@Component
public class NowTool implements AiTool {

    public static final String NAME = "now";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "获取当前本地日期时间（格式 yyyy-MM-dd HH:mm:ss）。当需要知道「此刻是何时」时调用，"
                + "例如判断某项按节拍该不该做、或给事件标注时间。无需参数。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        return params;
    }
}
