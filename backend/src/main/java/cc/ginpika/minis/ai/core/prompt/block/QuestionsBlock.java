package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 假设性问题（文档分析步骤 3 · Chunk 增强） */
@Component
public class QuestionsBlock implements PromptBlock {

    public static final String KEY = "questions";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "假设性问题";
    }

    @Override
    public String description() {
        return "针对 <chunk> 生成恰好 5 个用户可能提出、该段落能直接解答的问题。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_QUESTIONS);
    }

    @Override
    public String builtin() {
        return """
                你是文档检索增强专家。请基于父文档信息（标题 + 文档类型 <docType> + 全文概述），针对 <chunk> 生成恰好 5 个假设性问题——
                用户检索时可能提出的、该段落能直接解答的问题：口语化、目的性强（如「雪之下雪乃的生日是哪天？」），
                覆盖段落核心事实、互不重复。

                【文档类型与注意项】<docType> 为父文档类型，请据其调整提问措辞：
                - TECHNICAL（技术手册、规范、严谨知识）：沿用原文术语与规格，问题须能被文档中确凿的事实回答，不得臆造参数或结论；
                - NARRATIVE（主观叙述、个人博客、评论、发言）：可围绕人物、事件、观点与情感提问，允许口语化表达；
                - CONVERSATIONAL（对话、聊天记录）：以说话人与话题为线索，不要把寒暄或玩笑当作事实前提；
                - UNKNOWN（未判定）：按 <chunk> 内容自行判断，保持客观。

                只输出 JSON 对象：{"questions":["...","...","...","...","..."]}
                """;
    }
}
