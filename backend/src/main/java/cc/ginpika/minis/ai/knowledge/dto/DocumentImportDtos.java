package cc.ginpika.minis.ai.knowledge.dto;

/** 文档导入 DTO：把粘贴的文本直接保存为 markdown 文章（原文落 data/article/{url}.md，索引行落 documents 表） */
public final class DocumentImportDtos {

    /** title 可空：前端标题栏填写；为空时后端从正文 front-matter / 首个标题行自动提取 */
    public record ImportRequest(String content, String title) {
    }

    /** url 为文档 slug，文件落 data/article/{url}.md */
    public record ImportResult(String url, String title) {
    }

    private DocumentImportDtos() {
    }
}
