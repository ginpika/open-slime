package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * forget_memory：遗忘一条**自己有权**的记忆（memory-v6 §12.5 的 Agent 侧入口）。
 *
 * <p>与人工 purge 同语义：撤销它的共享 + 置 {@code revoked} + 写**墓碑**（同内容不会再被自动记住），
 * 不是物理删除——历史仍可点查，误删可解释。
 *
 * <p>边界：只能忘自己的 L2 私有记忆 / 当前项目 / 当前会话的记忆；**用户画像只能由用户在记忆页撤销**。
 * 与 recall 的配合：先 recall 拿到 id 再忘，最稳。
 */
@Component
public class ForgetMemoryTool implements AiTool {

    public static final String NAME = "forget_memory";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "遗忘一条记忆（撤销并写墓碑：同内容不会再被自动记住）。"
                + "适合「这条不该记 / 记错了而且没有正确版本」。"
                + "只能忘你自己的私有记忆、当前项目、当前会话的记忆；用户画像只能由用户在记忆页撤销。"
                + "如果只是内容过时但有新版本，用 update_memory 改写，不要直接忘。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.MEMORY;
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(JsonNode args) {
        boolean hasId = args != null && args.path("item_id").isNumber();
        boolean hasMatch = args != null && !args.path("match").asText("").trim().isEmpty();
        if (!hasId && !hasMatch) {
            return "请给出 item_id（推荐）或 match：要遗忘哪一条记忆";
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("item_id")
                .put("type", "number")
                .put("description", "要遗忘的记忆 id（用 recall_memory 得到；给了它最稳）");
        props.putObject("match")
                .put("type", "string")
                .put("description", "没给 item_id 时用：要遗忘的那条记忆的一句话描述");
        props.putObject("reason")
                .put("type", "string")
                .put("description", "一句话说明为什么遗忘（写进墓碑，供以后审计）");
        return params;
    }
}
