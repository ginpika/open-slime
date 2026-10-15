package cc.ginpika.minis.coding.config;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodingApplicationYamlTest {
    @Test
    void applicationYamlLoadsAndBindsCodingDefaults() throws IOException {
        var environment = loadApplicationYaml();

        // Check the resource keys as well as the bean defaults, so missing YAML
        // entries cannot silently pass through default Java field values.
        assertEquals("0", environment.getProperty("minis.coding.max-response-tokens"));
        assertEquals("32768", environment.getProperty("minis.coding.response-reserve-tokens"));
        assertEquals("3", environment.getProperty("minis.coding.max-output-continuations"));
        assertEquals("true", environment.getProperty("minis.web.headless"));

        var props = bindCoding(environment);
        assertEquals(0L, props.getMaxEstimatedTokens());
        assertEquals(0, props.getMaxResponseTokens());
        assertEquals(32_768, props.getResponseReserveTokens());
        assertEquals(3, props.getMaxOutputContinuations());
    }

    @Test
    void codingOutputEnvironmentOverridesBindIndependentlyOfRunBudget() throws IOException {
        var environment = loadApplicationYaml();
        environment.getPropertySources().addFirst(new MapPropertySource("test-environment", Map.of(
                "MINIS_CODING_RESPONSE_TOKENS", "16384",
                "MINIS_CODING_RESPONSE_RESERVE", "65536",
                "MINIS_CODING_MAX_TOKENS", "900000",
                "MINIS_CODING_OUTPUT_CONTINUATIONS", "4")));

        var props = bindCoding(environment);
        assertEquals(900_000L, props.getMaxEstimatedTokens());
        assertEquals(16_384, props.getMaxResponseTokens());
        assertEquals(65_536, props.getResponseReserveTokens());
        assertEquals(4, props.getMaxOutputContinuations());
    }

    private static StandardEnvironment loadApplicationYaml() throws IOException {
        var environment = new StandardEnvironment();
        // Keep this configuration regression check independent of the machine's
        // environment; it never starts the application or connects to services.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        return environment;
    }

    private static CodingProps bindCoding(StandardEnvironment environment) {
        return Binder.get(environment).bind("minis.coding", Bindable.of(CodingProps.class)).get();
    }
}
