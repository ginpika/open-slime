package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.ai.memory.MemoryLiveContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 上下文平面的组装器：把多路 {@link ContextProvider} 汇成一次 /chat/completions 的请求体。
 *
 * <p>骨架：{@code model} + 一条 {@code system} + 各源追加的消息 + {@code tools}。system 的正文由
 * 基底（身份提示词 + 对话增强）与各源的尾段（如「在跑的子代理」）以固定分隔线拼成；消息则由各源
 * 按 {@code @Order} 顺序依次插入。单聊试点可以额外生成包含来源与优先级的装配计划，
 * 供每次出站前选择和计费。
 *
 * <p>这是 STEP 2 的等效抽取落点：{@code ChatContextService#requestBody} 的实现原样搬来这里，行为零变化；
 * 知识检索 / 记忆 / 代码库三路上下文作为后续 provider 逐步接入，组装骨架不再改动。
 */
@Component
public class ContextBuilder {

    /** system 基底与各源尾段之间的分隔线（沿用原 requestBody 的固定形状，逐字不变） */
    static final String SYSTEM_SEPARATOR = "\n\n---\n\n";

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
        return withScope(req, () -> buildScoped(req, extra));
    }

    public ContextPlan plan(ContextRequest req, int window, int outputReserve, long currentUserId,
                            Set<Long> visibleHistory) {
        return plan(req, window, outputReserve, currentUserId, visibleHistory, null);
    }

    public ContextPlan plan(ContextRequest req, int window, int outputReserve, long currentUserId,
                            Set<Long> visibleHistory, ContextPlan.Stage stage) {
        return withScope(req, () -> planScoped(req, window, outputReserve, currentUserId, visibleHistory, stage));
    }

    private static <T> T withScope(ContextRequest req, Supplier<T> assembly) {
        var current = MemoryScopeCtx.get();
        if (current == null || !Objects.equals(current.sessionId(), req.sessionId()) || current.human()) {
            var scope = new MemoryExec(null, req.agentId(), req.projectId(), req.sessionId(), req.taskId(),
                    MemorySurface.valueOf(req.surface().name()), false, null, req.identity());
            return MemoryScopeCtx.run(scope, assembly);
        }
        return assembly.get();
    }

    private ContextPlan planScoped(ContextRequest req, int window, int outputReserve, long currentUserId,
                                   Set<Long> visibleHistory, ContextPlan.Stage stage) {
        List<ContextFragment> fragments = new ArrayList<>();
        fragments.add(ContextFragment.system(ContextFragment.Layer.INSTRUCTION, "system_base",
                ContextFragment.Trust.INSTRUCTION, Objects.toString(req.systemBase(), "")));
        ContextFragment current = null;
        var userRef = new ContextFragment.Ref("user", "message:" + currentUserId);
        MemoryLiveContext.takeCaptured();
        try {
            for (ContextProvider provider : providers) {
                var contributed = provider.fragments(req);
                var snapshot = MemoryLiveContext.takeCaptured();
                if (snapshot != null) {
                    contributed.stream().flatMap(fragment -> fragment.messages().stream())
                            .filter(message -> "system".equals(message.path("role").asText()))
                            .findFirst().ifPresent(message -> ((ObjectNode) message)
                                    .set("_minis_memory", om.valueToTree(snapshot)));
                }
                for (ContextFragment fragment : contributed) {
                    if (fragment.source().startsWith("history/") && (fragment.refs().isEmpty()
                            || !fragment.refs().stream().map(ContextFragment.Ref::messageId)
                                    .allMatch(id -> id != null && visibleHistory.contains(id)))) {
                        continue;
                    }
                    if (currentUserId > 0 && fragment.refs().contains(userRef)) {
                        current = new ContextFragment(ContextFragment.Layer.TASK, fragment.source(),
                                fragment.trust(), fragment.refs(), fragment.messages());
                    } else {
                        fragments.add(fragment);
                    }
                }
            }
        } finally {
            MemoryLiveContext.takeCaptured();
        }
        if (currentUserId > 0 && current == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "本轮用户消息已不在会话中");
        }
        if (stage != null) {
            fragments.add(stage.continuation());
        }
        if (current != null) {
            fragments.add(current);
        }
        ObjectNode body = om.createObjectNode().put("model", req.model());
        if (req.toolSchemas() != null) {
            body.set("tools", req.toolSchemas());
        }
        var scope = new ContextPlan.Scope(req.sessionId(), MemoryScopeCtx.get().agentOrBuiltin(), req.projectId());
        return new ContextPlan(body, fragments, scope, window, outputReserve, stage);
    }

    private ObjectNode buildScoped(ContextRequest req,List<ContextProvider> extra) {
        cc.ginpika.minis.ai.memory.MemoryLiveContext.takeCaptured();
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
            ObjectNode message = msgs.addObject().put("role", "system").put("content", system.toString());
            var snapshot = cc.ginpika.minis.ai.memory.MemoryLiveContext.takeCaptured();
            if (snapshot != null) { message.set("_minis_memory", om.valueToTree(snapshot)); }
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
