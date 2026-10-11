package cc.ginpika.minis.agent.heartbeat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import org.springframework.stereotype.Component;

/**
 * 把「提示词正文 + 本轮交代」当场组装成一条完整的 LLM 上下文。
 *
 * <p>两类调用方共用这一条组装路径：某 Agent 的心跳主会话（{@link #build(String)}，只有正文）
 * 与它的常驻职责（{@link #build(PlanStore.PlanRow)}，另有角色与「这次做什么」的 input）。
 *
 * <p>「注入器」退役后，这里只剩正文：素材不再声明式注入，需要什么由模型在执行时自己调工具现取
 * （everything in tools &amp; bash）。组装仍交给上下文平面（{@link ContextBuilder}）的
 * {@link ContextRequest.Surface#HEARTBEAT} 面——目前该面没有额外 provider，于是正文即 system，
 * input 作为单独一条 user 消息追加。
 *
 * <p>子代理不走这里：它没有正文与交代之外的东西（见 {@code SubagentService}），system 是固定的内置人格。
 */
@Component
public class HeartbeatContextBuilder {

    /** 组装结果：messages 数组 + 其序列化文本（落库用） */
    public record Built(ArrayNode messages, String json) {
    }

    private final ContextBuilder context;

    public HeartbeatContextBuilder(ContextBuilder context) {
        this.context = context;
    }

    /**
     * 组装某 Agent 心跳主会话的上下文：它只有一份正文——正文即它的全部人格与职责，恒为 system，
     * 没有「本次交代」这一层（每拍要做什么由它自己看着名册决定）。
     */
    public Built build(String content) {
        return built("system", content, null);
    }

    /**
     * 组装一份职责执行的全部 messages。
     *
     * <p>职责自己的 role 决定正文落在哪条消息（system / user）；它的 input 作为「本次的具体交代」
     * 追加在后——正文管「你是谁、怎么做事」，input 管「这一轮做什么」。
     */
    public Built build(PlanStore.PlanRow plan) {
        return built(plan.role(), plan.content(), plan.input());
    }

    /** role=user 时正文作一条 user 消息；否则作 system 基底。input 非空时另追加一条 user 消息 */
    private Built built(String role, String content, String input) {
        String body0 = content == null ? "" : content;
        boolean userRole = "user".equalsIgnoreCase(role);
        ObjectNode body = context.build(new ContextRequest(null, userRole ? "" : body0, null,
                false, null, 0, null, ContextRequest.Surface.HEARTBEAT));
        ArrayNode messages = (ArrayNode) body.get("messages");
        if (userRole && !body0.isBlank()) {
            messages.addObject().put("role", "user").put("content", body0);
        }
        if (input != null && !input.isBlank()) {
            messages.addObject().put("role", "user").put("content", input);
        }
        return new Built(messages, messages.toString());
    }
}
