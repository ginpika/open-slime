package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.stats.dto.StatsDtos;
import cc.ginpika.minis.stats.TokenUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：整个应用的 LLM token 消耗。
 *
 * <p>数据来自 token_usage（每次 chat/embedding/rerank 调用一行），
 * 返回总量 + 分来源 + 分模型 + 最近 days 天按日趋势。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TokenUsageService usage;

    public StatsController(TokenUsageService usage) {
        this.usage = usage;
    }

    /** 统计页数据；days 为趋势区间（天，默认 30，上限 365） */
    @GetMapping
    public StatsDtos.StatsView stats(@RequestParam(defaultValue = "30") int days) {
        return usage.view(days);
    }
}
