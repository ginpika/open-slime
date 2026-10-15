package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.stats.dto.StatsDtos;
import cc.ginpika.minis.stats.TokenUsageService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：整个应用的 LLM token 消耗。
 *
 * <p>数据来自 token_usage（每次 chat/embedding/rerank 调用一行），
 * 所有聚合与明细使用同一日期窗口，金额按供应商/模型及生效价格分别估算。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TokenUsageService usage;

    public StatsController(TokenUsageService usage) {
        this.usage = usage;
    }

    /** days 默认 30、上限 365，-1 为本月；from/to 可指定最多 366 天的日期范围。 */
    @GetMapping
    public StatsDtos.StatsView stats(@RequestParam(defaultValue = "30") int days,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required=false) String source,
            @RequestParam(required=false) String provider,
            @RequestParam(required=false) String model) {
        return usage.view(days,from,to,source,provider,model);
    }

    @PutMapping("/prices")
    public java.util.Map<String,Boolean> savePrice(@RequestBody StatsDtos.PriceRequest request) {
        usage.savePrice(request);
        return java.util.Map.of("saved",true);
    }

    @DeleteMapping("/prices/{id}")
    public java.util.Map<String,Boolean> deletePrice(@PathVariable long id) {
        usage.deletePrice(id);
        return java.util.Map.of("deleted",true);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<java.util.Map<String,String>> invalid(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(java.util.Map.of("message",
                error.getReason()==null ? "统计请求失败" : error.getReason()));
    }
}
