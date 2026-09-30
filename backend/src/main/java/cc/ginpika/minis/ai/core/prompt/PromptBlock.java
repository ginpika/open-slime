package cc.ginpika.minis.ai.core.prompt;

import java.util.Set;

/**
 * 一个提示词块：提示词的最小编排单位，每个块单独成类。
 *
 * <p>块是开发者亲手写下的编程契约（key、名称、说明、内置正文、可出现的上下文），各自独立演进、
 * 互不干扰——与工具侧的 {@code AiTool} 每工具一类完全同构。按上下文把块组装成最终 system 由
 * {@link PromptComposer} 负责（经 {@link PromptManager} 对外提供），用户版本/绑定/顺序的写操作
 * 也在 {@link PromptManager}。
 *
 * <p>内置正文只是缺省值：用户可在「潜意识 · 提示词」页为任意块保存多份版本
 * （{@code prompt_version}）并选定一份覆盖内置（{@code prompt_binding}）。
 * 注意：{@code identity} 块没有代码内置（来自 .env 的 {@code LLM_SYSTEM_PROMPT}），
 * 其 {@link #builtin()} 返回 null，由 {@link PromptManager} 兜底。
 */
public interface PromptBlock {

    /** 块 key：入库/绑定/接口都用它作稳定标识 */
    String key();

    /** 块名：提示词页展示用 */
    String name();

    /** 块说明：提示词页展示用（这块管什么、改了影响什么） */
    String description();

    /** 该块出现在哪些上下文（上下文 key，见 {@link PromptLibrary}） */
    Set<String> contexts();

    /** 内置正文（写死在类里）；null 表示内置由外部提供（identity ← .env） */
    String builtin();

    /**
     * 组装 system 时包裹本块的 XML 标签名（成对输出，如 {@code <persona>…</persona>}）：
     * 让模型清楚各块的边界与职责，避免相邻块的正文被读成一整段；
     * 返回 null 表示裸拼（不包裹）。
     */
    default String tag() {
        return null;
    }
}
