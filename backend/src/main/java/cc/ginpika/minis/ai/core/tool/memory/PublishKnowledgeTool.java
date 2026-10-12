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
 * publish_knowledge：把一条结论/经验**主动发布**出去（memory-v6 §9.2 · L4 交接与知识）。
 *
 * <p>为什么需要它：v5 的 L4 只有管理页 API，Agent 没有任何「发布」能力——跨 Agent 协作因此仍是孤岛。
 * 本工具让 Agent 在对话/心跳里把值得共享的东西发布给指定接收方（user / project / agent / session），
 * 落点 = 新建一条 {@code L4} 条目 + 一条共享授权（发布即来源凭据，满足 I6），
 * 由此对方在读侧 R5 与收件箱里按需发现（不推送）。
 *
 * <p>执行见 {@code KnowledgeToolbox} 的分发；写入走轮内缓冲（{@code MemoryStage}）——
 * 本轮发布不影响本轮注入，下一轮才生效（memory-v6 §4.4）。
 */
@Component
public class PublishKnowledgeTool implements AiTool {

    public static final String NAME = "publish_knowledge";

    /** 合法接收方类型（与 MemoryPublisher 的判定保持一致） */
    private static final Set<String> TO_KINDS = Set.of("agent", "project", "session", "user");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "把一条值得共享的结论 / 经验 / 交接信息**发布**给指定接收方（L4 交接与知识）。"
                + "适用：完成任务后给同伴留交接摘要、把验证过的经验共享给项目或某位 Agent。"
                + "接收方在下一次召回（或收件箱）里按需发现，不会打断对方。"
                + "这是显式动作：需要你判断「对方确实需要」，不要发布寒暄与私人细节。";
    }

    @Override
    public Set<ToolSurface> surfaces() {
        return Set.of(ToolSurface.CHAT, ToolSurface.HEARTBEAT);
    }

    @Override
    public ToolDomain domain() {
        return ToolDomain.MEMORY;
    }

    /** 写记忆（L4 发布）：必须声明 writes——否则会被当成只读工具混进子代理的默认工具集 */
    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public String validate(JsonNode args) {
        if (args == null || args.path("content").asText("").trim().isEmpty()) {
            return "缺少 content：请给出要发布的结论 / 交接内容";
        }
        String kind = args.path("to_kind").asText("").trim().toLowerCase();
        if (!TO_KINDS.contains(kind)) {
            return "to_kind 非法（agent|project|session|user）：" + kind;
        }
        return null;
    }

    @Override
    public ObjectNode parameters(ObjectMapper om) {
        ObjectNode params = om.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("content")
                .put("type", "string")
                .put("description", "要发布的内容：一句到几句完整的结论 / 经验 / 交接说明");
        props.putObject("title")
                .put("type", "string")
                .put("description", "不超过 12 字的标题（可选）");
        ObjectNode kind = props.putObject("to_kind");
        kind.put("type", "string");
        kind.put("description", "接收方类型：agent=某位同伴；project=本项目；session=本会话；user=用户本人");
        kind.putArray("enum").add("agent").add("project").add("session").add("user");
        props.putObject("to_ref")
                .put("type", "string")
                .put("description", "接收方标识：agent 填 Agent id；project 填项目 id；session 填会话 id；"
                        + "user 可不填。缺省 = 当前项目 / 当前会话 / 自己");
        props.putObject("handoff")
                .put("type", "boolean")
                .put("description", "是否带交接状态机（true = 对方需 accept/done 跟进；缺省 false 只发布）");
        props.putObject("note")
                .put("type", "string")
                .put("description", "给接收方的一句话说明（可选）");
        params.putArray("required").add("content").add("to_kind");
        return params;
    }
}
