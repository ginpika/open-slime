package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.retrieval.FastSearchService;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final FastSearchService fast;

    public SearchController(FastSearchService fast) {
        this.fast = fast;
    }

    @PostMapping("/search")
    public SearchDtos.SearchResponse search(@RequestBody(required = false) SearchDtos.SearchRequest req) {
        if (req == null) {
            return fast.search("");
        }
        return fast.search(req.query(), Boolean.TRUE.equals(req.rag()));
    }
}
