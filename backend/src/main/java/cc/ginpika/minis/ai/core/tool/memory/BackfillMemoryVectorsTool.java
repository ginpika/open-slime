package cc.ginpika.minis.ai.core.tool.memory;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolDomain;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * backfill_memory_vectors：为缺向量的记忆事件补齐 embedding——记忆向量轴的维护动作，
 * 供记忆维护类的任务计划声明调用（embedding 端点刚配好、或发现 recall_memory 语义排序未生效时）。
 * 不挂 HEARTBEAT 面：主会话是调度者，自己不下场改数据（与其它写类工具同一戒律）。
 */
@Component
public class BackfillMemoryVectorsTool implements AiTool {

    public static final String NAME = "backfill_memory_vectors";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "为缺少向量的记忆事件补齐 embedding（每次最多补一批，默认 16 条），"
                + "使 recall_memory 的语义排序对旧事件也生效。embedding 端点刚配置好、"
                + "或换过 embedding 模型后调用；平时无需调用（新事件会自动补）。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.MEMORY;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        params.putObject("properties").putObject("limit")
                .put("type", "number")
                .put("description", "本次最多补齐的条数，1–64，默认 16");
        return params;
    }
}
