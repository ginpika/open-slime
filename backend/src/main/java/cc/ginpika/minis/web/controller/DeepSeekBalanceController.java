package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.config.DeepSeekBalanceService;
import cc.ginpika.minis.config.DeepSeekWebSettings;
import cc.ginpika.minis.config.SettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Resolve saved credentials server-side, never accept or return a key in the balance request. */
@RestController
public class DeepSeekBalanceController {
    private final SettingsService models;
    private final DeepSeekWebSettings web;
    private final DeepSeekBalanceService balances;
    public DeepSeekBalanceController(SettingsService models, DeepSeekWebSettings web, DeepSeekBalanceService balances) {
        this.models = models;
        this.web = web;
        this.balances = balances;
    }
    @GetMapping("/api/settings/models/{id}/balance")
    public ResponseEntity<DeepSeekBalanceService.View> model(@PathVariable String id, @RequestParam(defaultValue = "false") boolean refresh) {
        var target = models.requireModelTarget(id);
        return response(balances.balance(target.baseUrl(), target.apiKey(), refresh));
    }
    @GetMapping("/api/settings/deepseek-web/balance")
    public ResponseEntity<DeepSeekBalanceService.View> web(@RequestParam(defaultValue = "false") boolean refresh) {
        var target = web.resolve();
        return response(balances.balance(target.baseUrl(), target.apiKey(), refresh));
    }
    private static ResponseEntity<DeepSeekBalanceService.View> response(DeepSeekBalanceService.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
}
