package cc.ginpika.minis.ai.knowledge.dto;

import java.util.List;

/** 文档（博客式文章）DTO：读者视角的列表分页 + markdown 详情，以及发布/更新请求与结果 */
public final class DocumentDtos {

    /** 列表项：url 为文档 slug */
    public record Item(String url, String title, String summary, long createdAt) {
    }

    /** 分页结果：total 为文档总数，page/size 为当前页 */
    public record Page(long total, int page, int size, List<Item> items) {
    }

    /** 详情：markdown 为 data/article/{url}.md 的原文 */
    public record Detail(String url, String title, String summary, long createdAt, String markdown) {
    }

    /** 发布/更新文档：markdown 存 data/article/{url}.md，索引行落 documents 表（更新保留首发布时间） */
    public record PublishRequest(String url, String title, String summary, String content) {
    }

    /** 发布结果：url 为落库后的文档 slug */
    public record PublishResult(String url) {
    }

    private DocumentDtos() {
    }
}
