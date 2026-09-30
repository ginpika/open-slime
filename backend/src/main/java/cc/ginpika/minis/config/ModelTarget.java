package cc.ginpika.minis.config;

/**
 * 一次模型调用的运行时端点（base-url / api-key / model）。
 * 由 SettingsService 解析：既可能来自设置页持久化的配置，也可能来自 .env（内置 "env" 项）。
 */
public record ModelTarget(String baseUrl, String apiKey, String model) {

    public boolean configured() {
        return !isBlank(baseUrl) && !isBlank(apiKey) && !isBlank(model);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
