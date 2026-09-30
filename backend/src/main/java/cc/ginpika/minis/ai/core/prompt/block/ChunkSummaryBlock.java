package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 段落摘要（文档分析步骤 3 · Chunk 增强） */
@Component
public class ChunkSummaryBlock implements PromptBlock {

    public static final String KEY = "chunk_summary";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "段落摘要";
    }

    @Override
    public String description() {
        return "概括该段落究竟讲了什么（主题 + 核心事实），60 字以内。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_CHUNK_SUMMARY);
    }

    @Override
    public String builtin() {
        return """
                你是文档检索增强专家。请针对 <chunk> 段落本身生成一句话摘要，用于 RAG 检索时的段落定位：
                概括该段落究竟讲了什么（主题 + 核心事实），客观具体，不要复述父文档标题，60 字以内。

                【文档类型与注意项】<docType> 为父文档类型，请据其调整摘要侧重与措辞：
                - TECHNICAL（技术手册、规范、严谨知识）：保留关键术语、规格与结论，不要口语化改写；
                - NARRATIVE（主观叙述、个人博客、评论、发言）：概括人物、事件、观点或情感走向，可提炼主观表达；
                - CONVERSATIONAL（对话、聊天记录）：点明说话人与话题，不要把寒暄或玩笑当作事实陈述；
                - UNKNOWN（未判定）：按 <chunk> 内容自行判断，保持客观。

                只输出这句话本身，不要任何前缀、引号或解释。
                """;
    }
}
