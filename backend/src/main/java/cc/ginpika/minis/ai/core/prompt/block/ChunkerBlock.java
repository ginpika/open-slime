package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 文本切分器（超长段落按语义切分） */
@Component
public class ChunkerBlock implements PromptBlock {

    public static final String KEY = "chunker";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "文本切分器";
    }

    @Override
    public String description() {
        return "不得修改、增删或改写原文的任何文字，只输出 JSON 字符串数组。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_CHUNKER);
    }

    @Override
    public String builtin() {
        return """
                你是文本切分器。把用户给出的文本按语义边界切成若干连续片段，要求：
                - 在话题转换、句子完结等自然边界处切分
                - 每个片段目标 500~800 个字符，任何片段不得超过 1200 个字符
                - 不得修改、增删或改写原文的任何文字
                - 只输出 JSON 字符串数组，不要输出任何其他内容，如 ["片段一", "片段二"]""";
    }
}
