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
 * update_memory：改写一条**自己有权**的记忆（memory-v6 §9.1）。
 *
 * <p>为什么需要它：此前 Agent 只能追加（发布 / 工作集），记错了只能等人工在记忆页修。
 * 有了它，「我上次记的做法有误，纠正一下」变成 Agent 自己能闭环的动作。
 *
 * <p>边界（执行侧 {@code MemoryEditService} 把关，这里只声明契约）：
 * 只能改**自己的 L2 私有记忆 / 当前项目 / 当前会话**的记忆；**用户画像与已发布知识不可改**；
 * 命中多条不猜、让人把 item_id 说清楚；更新走乐观锁，冲突如实回报（不覆盖别人的写入）。
 * 写入走轮内缓冲：本轮改写不影响本轮注入，下一轮才生效。
 */
@Component
public class UpdateMemoryTool implements AiTool {

    public static final String NAME = "update_memory";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "改写一条记忆的正文（或标题）：适合「这条记错了 / 过时了，纠正一下」。"
                + "先用 recall_memory 找到目标（拿到 id 最稳），或用 match 给一句话描述让系统定位。"
                + "只能改你自己的私有记忆、当前项目、当前会话的记忆；用户画像与已发布的知识不能改。"
                + "如果只是新增信息，不要用本工具（正常对话会被自动提炼）；临时的工作状态用 note_working。";
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
        if (args == null || args.path("new_content").asText("").trim().isEmpty()) {
            return "缺少 new_content：请给出改写后的正文";
        }
        boolean hasId = args.path("item_id").isNumber();
        boolean hasMatch = !args.path("match").asText("").trim().isEmpty();
        if (!hasId && !hasMatch) {
            return "请给出 item_id（推荐）或 match：要改哪一条记忆";
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
                .put("description", "要改写的记忆 id（用 recall_memory 得到；给了它最稳）");
        props.putObject("match")
                .put("type", "string")
                .put("description", "没给 item_id 时用：要改的那条记忆的一句话描述（命中多条会回候选让你重挑）");
        props.putObject("new_content")
                .put("type", "string")
                .put("description", "改写后的正文（完整一句陈述，不是补丁）");
        props.putObject("title")
                .put("type", "string")
                .put("description", "改写后的标题（不超过 12 字；不填保持原标题）");
        params.putArray("required").add("new_content");
        return params;
    }
}
