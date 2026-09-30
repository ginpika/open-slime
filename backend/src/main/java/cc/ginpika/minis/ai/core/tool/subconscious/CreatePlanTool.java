package cc.ginpika.minis.ai.core.tool.subconscious;

import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * create_plan：立一个「任务计划」——一份常驻编制，之后可以反复派它干活。
 *
 * <p>计划不由人在页面上新建，而是大模型自己攒编制：先立（本工具），再派（{@link DelegatePlanTool}）。
 * 立错了、不用了，用 {@link UpdatePlanTool} 改写或 {@link DeletePlanTool} 撤掉。
 *
 * <p>一次性的活别立编制——那是 {@link DelegateSubagentTool}（子代理）的事。
 */
@Component
public class CreatePlanTool implements AiTool {

    public static final String NAME = "create_plan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "立一个「任务计划」：给它起名、写下它该怎么做事的提示词，并决定它能用哪些工具、要不要对人说话。"
                + "计划立好后可用 " + DelegatePlanTool.NAME + " 反复派活，每次拿到的都是独立上下文。"
                + "当一件事需要反复做、或需要一套固定的做事方式时，才值得立一个计划；"
                + "一次性的活直接派 " + DelegateSubagentTool.NAME + " 即可。";
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
        ObjectNode props = params.putObject("properties");
        props.putObject("name").put("type", "string")
                .put("description", "计划的名字：之后派活、改写、删除都用它来指认，同一主题请沿用同一个名字");
        props.putObject("content").put("type", "string")
                .put("description", "提示词正文——这份编制「是谁、怎么做事」。可用 {{插槽}} 占位，"
                        + "执行时由注入器从知识库现取素材填进去（如 {{now}}、{{stats}}、{{user_profile}}=用户锚点画像）");
        props.putObject("role").put("type", "string")
                .put("description", "正文组装成哪种消息：system（默认，作为人格设定）或 user（作为指令）");
        AiTool.putStringArraySchema(props, "tools",
                "允许它调用的知识库工具名（如 search_vector_chunks、read_document、upsert_node）；"
                        + "留空=只产出结论，不碰知识库。管理类工具（派活 / 增删改计划）对它无效");
        AiTool.putEnumArraySchema(props, "channels",
                "交付渠道：silent=只留痕（默认，结果落库供回溯）；"
                        + "thread=额外允许它用 open_thread / send_message 往首页 chat 的会话里说给人听",
                "silent", "thread");
        props.putObject("interval_ms").put("type", "integer")
                .put("description", "节拍参考（毫秒）：心跳主会话据此判断隔多久该派它一次；省略=每拍都可以考虑");
        params.putArray("required").add("name").add("content");
        return params;
    }
}
