package cc.ginpika.minis.ai.core.prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段提示词：具名的纯文本片段，是提示词的最小单位。
 *
 * <p>提示词按「片段」而非「整串」来表达——一个片段只承担一件事（用户人格、内置增强、抽取规范……），
 * 需要时由 {@link #join} 依序拼接成最终的 system 内容。于是「提示词由哪些部分组成、谁在前谁在后」
 * 是一件看得见、可断言的事，而不是散落在各服务里的字符串加法。
 */
public record Prompt(String name, String content) {

    public Prompt {
        content = content == null ? "" : content.strip();
    }

    /** 该片段无内容（未配置的用户人格提示词即属此类） */
    public boolean isBlank() {
        return content.isBlank();
    }

    /**
     * 依序拼接若干提示词片段：跳过空白片段，段间以空行分隔；全部为空时返回空串。
     * 拼接只负责顺序——顺序由调用方声明，正是「人格在前、内置增强固定在后」这类约定的表达处。
     */
    public static String join(Prompt... prompts) {
        List<String> parts = new ArrayList<>();
        for (Prompt p : prompts) {
            if (p != null && !p.isBlank()) {
                parts.add(p.content());
            }
        }
        return String.join("\n\n", parts);
    }
}
