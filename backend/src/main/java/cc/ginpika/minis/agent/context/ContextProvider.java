package cc.ginpika.minis.agent.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 上下文源：多路上下文汇成一次请求体的一个插口（STEP 2 上下文平面）。
 *
 * <p>把「这次上下文里有什么」拆成若干独立来源——会话历史、在跑子代理、知识检索、记忆、代码库……
 * 每一个都是本接口的一个实现，自己现取素材、自己决定往哪加；{@link ContextBuilder} 只按顺序把它们
 * 拼起来，不关心谁是谁。于是新增一路上下文＝新增一个实现，不必再动组装代码。
 *
 * <p>两条插口，按需覆写：
 * <ul>
 *   <li>{@link #systemSuffix}——追在 system 基底之后（如「在跑的子代理」进度块）；</li>
 *   <li>{@link #messages}——插在 system 之后的消息序列（如整段历史回放）。</li>
 * </ul>
 * 顺序由 Spring 的 {@code @Order} 决定（数值越小越靠前），两条插口各自独立按序拼接。
 *
 * <p>现状（STEP 2）：已启用 {@link cc.ginpika.minis.agent.chat.turn.History}（会话历史 Turn 回放）、
 * {@link ActiveSubagentProvider}、
 * {@link TodoProvider}、{@link KnowledgeProvider}、{@link MemoryProvider} 五路源——知识 / 记忆两路
 * 属行为变更，由 {@code cc.ginpika.minis.config.ContextProps} 控制、默认关；代码库 / 文件树一路是
 * 「逐步插 provider」的后续落点（待 STEP 5），届时各自实现本接口、加一个 {@code @Component} 即可，
 * {@link ContextBuilder} 无需改动。
 */
public interface ContextProvider {

    /** 追在 system 基底之后的尾段；返回空串表示本源不追加 system（默认不追加） */
    default String systemSuffix(ContextRequest req) {
        return "";
    }

    /** 插在 system 之后的消息；返回空列表表示本源不贡献消息（默认无） */
    default List<JsonNode> messages(ContextRequest req) {
        return List.of();
    }
}
