package cc.ginpika.minis.ai.core.prompt.dto;

import java.util.List;

/** 提示词页请求/响应对（ai.core.prompt.PromptManager ↔ 前端） */
public final class PromptDtos {

    /** 用户保存的一份提示词版本 */
    public record VersionView(String id, String name, String content, long updatedAt) {
    }

    /** 提示词位视图：内置正文 + 用户版本 + 当前生效版本（activeVersionId 为空 = 用内置） */
    public record SlotView(String key, String name, String description, String builtin,
                           String builtinSource, String activeVersionId, List<VersionView> versions) {
    }

    /** 代码控制的运行时素材块（只读、不可拖拽）：说明执行时这里会被填入什么 */
    public record RuntimeView(String key, String name, String description) {
    }

    /**
     * 场景上下文视图：可拖拽排序的提示词位（parts，顺序即实际拼装顺序）
     * + 代码控制的运行时素材（runtime，恒在其后）。
     */
    public record ContextView(String key, String name, String description,
                              List<SlotView> parts, List<RuntimeView> runtime) {
    }

    /** 大类视图：提示词页的一级标签，下挂若干上下文 */
    public record CategoryView(String key, String name, String description,
                               List<ContextView> contexts) {
    }

    public record PromptsView(List<CategoryView> categories) {
    }

    /** 新建/更新版本的请求体 */
    public record VersionRequest(String name, String content) {
    }

    /** 保存某提示词位「当前生效正文」：已绑定版本则改它，仍是内置则自动分叉成新版本并绑定 */
    public record ContentRequest(String content) {
    }

    /** 绑定请求：versionId 为空/缺省或 "builtin" = 用内置 */
    public record BindRequest(String versionId) {
    }

    /** 上下文内提示词位的拼装顺序（按数组下标为准） */
    public record OrderRequest(List<String> slots) {
    }

    private PromptDtos() {
    }
}
