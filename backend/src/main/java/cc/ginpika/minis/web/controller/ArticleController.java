package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.dto.DocumentDtos;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 文档（博客式文章）：分页列表 + markdown 详情。索引在 documents 表（url 非空的行），原文存 data/article/{url}.md */
@RestController
@RequestMapping("/api/articles")
public class ArticleController {

    private static final int MAX_SIZE = 50;

    private final DocumentStore documents;

    public ArticleController(DocumentStore documents) {
        this.documents = documents;
    }

    @GetMapping
    public DocumentDtos.Page list(@RequestParam(defaultValue = "1") Integer page,
                                 @RequestParam(defaultValue = "10") Integer size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), MAX_SIZE);
        List<DocumentDtos.Item> items = documents.articlePage(p, s).stream()
                .map(r -> new DocumentDtos.Item(r.url(), r.title(), r.summary(), r.createdAt()))
                .toList();
        return new DocumentDtos.Page(documents.articleCount(), p, s, items);
    }

    @GetMapping("/{url}")
    public DocumentDtos.Detail detail(@PathVariable String url) {
        DocumentStore.ArticleMeta meta = documents.articleMeta(url);
        String markdown = meta == null ? null : documents.markdown(url);
        if (meta == null || markdown == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文章不存在: " + url);
        }
        return new DocumentDtos.Detail(meta.url(), meta.title(), meta.summary(), meta.createdAt(), markdown);
    }
}
