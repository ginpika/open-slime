package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.config.Branding;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查（B0 冒烟用 + 桌面壳启动探测/版本握手） */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final Branding branding;

    public HealthController(Branding branding) {
        this.branding = branding;
    }

    /** 打包产物的版本（jar MANIFEST 的 Implementation-Version）；源码直跑（IDE/spring-boot:run）为 dev */
    private static final String VERSION =
            HealthController.class.getPackage().getImplementationVersion() == null
                    ? "dev"
                    : HealthController.class.getPackage().getImplementationVersion();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "service", branding.serviceId(),
                "version", VERSION,
                "time", OffsetDateTime.now().toString());
    }
}
