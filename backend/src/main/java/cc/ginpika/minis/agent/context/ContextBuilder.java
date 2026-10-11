package cc.ginpika.minis.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 上下文平面的组装器：把多路 {@link ContextProvider} 汇成一次 /chat/completions 的请求体。
 *
 * <p>骨架：{@code model} + 一条 {@code system} + 各源追加的消息 + {@code tools}。system 的正文由
 * 基底（身份提示词 + 对话增强）与各源的尾段（如「在跑的子代理」）以固定分隔线拼成；消息则由各源
 * 按 {@code @Order} 顺序依次插入。真实发送（{@code ChatService#runChat}）与「查看原始上下文」
 * （{@code ChatService#rawContext}）共用这一条路径，保证预览与实际发出去的请求体完全一致。
 *
 * <p>这是 STEP 2 的等效抽取落点：{@code ChatService#requestBody} 的实现原样搬来这里，行为零变化；
 * 知识检索 / 记忆 / 代码库三路上下文作为后续 provider 逐步接入，组装骨架不再改动。
 */
@Component
public class ContextBuilder {

    /** system 基底与各源尾段之间的分隔线（沿用原 requestBody 的固定形状，逐字不变） */
    private static final String SYSTEM_SEPARATOR = "\n\n---\n\n";

    private final ObjectMapper om;
    private final List<ContextProvider> providers;

    public ContextBuilder(ObjectMapper om, List<ContextProvider> providers) {
        this.om = om;
        /* 顺序即拼装顺序：Spring 已按 @Order 排好，这里保留注入顺序——
           与 ToolManager 不同，上下文源的顺序有语义，不能按名字重排 */
        this.providers = providers;
    }

    /** 组装一次请求体：model + system（基底 + 各源尾段）+ 各源消息 + tools */
    public ObjectNode build(ContextRequest req) {
        return build(req, List.of());
    }

    /**
     * 组装一次请求体，并额外挂上一组「这次才有的」provider。
     *
     * <p>用途：某些面的素材来自本次请求自身携带的数据（如潜意识的「本计划绑定了哪些注入器」），
     * 装不进无状态的全局 provider，就由调用方现造一个 provider 一起参与拼装。全局 provider 在前、
     * 追加的在后——但各面的全局 provider 已按 {@link ContextRequest#surface()} 早退，
     * 所以追加的 provider 等价于该面唯一的源，拼装顺序稳定。
     */
    public ObjectNode build(ContextRequest req, List<ContextProvider> extra) {
        ObjectNode body = om.createObjectNode();
        body.put("model", req.model());
        ArrayNode msgs = body.putArray("messages");

        List<ContextProvider> all = new ArrayList<>(providers);
        all.addAll(extra);

        StringBuilder system = new StringBuilder(req.systemBase() == null ? "" : req.systemBase());
        for (ContextProvider p : all) {
            String suffix = p.systemSuffix(req);
            if (suffix != null && !suffix.isBlank()) {
                system.append(SYSTEM_SEPARATOR).append(suffix);
            }
        }
        /* system 全空时不落这一条：潜意识里 role=user 的计划没有 system 基底，也不该凭空多出一条空 system */
        if (!system.toString().isBlank()) {
            msgs.addObject().put("role", "system").put("content", system.toString());
        }

        for (ContextProvider p : all) {
            for (JsonNode m : p.messages(req)) {
                msgs.add(m);
            }
        }
        if (req.toolSchemas() != null) {
            body.set("tools", req.toolSchemas());
        }
        return body;
    }
}
