package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 文档摘要与分类（全文概述 + docType 判定） */
@Component
public class DocSummaryBlock implements PromptBlock {

    public static final String KEY = "doc_summary";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "文档摘要与分类";
    }

    @Override
    public String description() {
        return "生成 100~200 字中文摘要，并判定 TECHNICAL / NARRATIVE / CONVERSATIONAL。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_DOC_SUMMARY);
    }

    @Override
    public String builtin() {
        return """
                你是文档摘要与分类助手。阅读用户给出的全文，完成两件事：
                1. 生成一段 100~200 字的中文摘要，只保留文章主题、核心内容与覆盖的模块/主题词，不评论、不补充、不扩展；
                2. 判定该文档属于以下三类中的一类：
                   - TECHNICAL：技术手册、规范、严谨知识（教程、API 文档、标准、学术/工程说明）
                   - NARRATIVE：主观类表达、叙述、个人博客、评论、发言
                   - CONVERSATIONAL：对话，聊天记录
                只输出一个 JSON 对象，不要输出任何前后缀、标题、markdown 围栏或解释，格式为：
                {"summary":"<摘要正文>","docType":"TECHNICAL|NARRATIVE|CONVERSATIONAL"}""";
    }
}
