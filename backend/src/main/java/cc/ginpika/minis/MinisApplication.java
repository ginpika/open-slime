package cc.ginpika.minis;

import cc.ginpika.minis.config.Branding;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MinisApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MinisApplication.class);
        // 应用名统一取自根目录 branding.json（classpath 里的那份）
        app.setDefaultProperties(Map.of("spring.application.name", Branding.applicationName()));
        app.run(args);
    }
}
