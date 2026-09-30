package cc.ginpika.minis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 品牌信息：读取打进 classpath 的 branding.json（由 Maven 从仓库根目录同名文件拷入）。
 *
 * <p>改正式名称只需改根目录 branding.json，后端各处的展示随之统一。
 */
@Component
public class Branding {

    private static final String RESOURCE = "branding.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final String slug;

    public Branding() {
        JsonNode node = read();
        this.name = node.path("displayName").asText("minis");
        this.slug = node.path("slug").asText("minis");
    }

    /** 面向用户显示的正式名称 */
    public String name() {
        return name;
    }

    /** 技术标识（小写）：用于服务标识、应用名等 */
    public String slug() {
        return slug;
    }

    /** 健康检查里的服务标识：{@code <slug>-backend} */
    public String serviceId() {
        return slug + "-backend";
    }

    /** 供 main 在 Spring 启动前设置 spring.application.name 使用 */
    public static String applicationName() {
        return read().path("slug").asText("minis");
    }

    private static JsonNode read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return MAPPER.readTree(in);
        } catch (Exception e) {
            // 缺少配置时回落到默认值，不阻断启动
            return MAPPER.createObjectNode();
        }
    }
}
