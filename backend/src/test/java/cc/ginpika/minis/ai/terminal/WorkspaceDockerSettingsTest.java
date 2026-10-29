package cc.ginpika.minis.ai.terminal;

import cc.ginpika.minis.ai.terminal.dto.TerminalDtos;
import cc.ginpika.minis.ai.terminal.store.TerminalStore;
import cc.ginpika.minis.config.TerminalProps;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceDockerSettingsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void defaultsAndMountBindingSurviveSaveReadAndEdit() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
            var store = new TerminalStore(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
            var docker = mock(ExecTarget.class);
            when(docker.kind()).thenReturn("docker");
            var service = new TerminalService(new TerminalProps(), store, json, List.of(docker));
            var first = service.create(request("{\"kind\":\"docker\",\"enabled\":true}"));
            var second = service.create(request("{\"kind\":\"docker\"}"));
            assertNotEquals(first.name(), second.name());
            assertEquals("ubuntu:24.04", first.image());
            assertEquals("/workspace", first.workdir());
            assertTrue(first.enabled());
            assertNull(first.hostWorkdir());

            var bound = service.create(request(json.writeValueAsString(Map.of("kind", "docker", "name", "bound",
                    "image", "alpine:latest", "hostWorkdir", "D:\\Projects\\中文 项目", "workdir", "/work/app"))));
            assertEquals("D:\\Projects\\中文 项目", store.targetById(bound.id()).orElseThrow().hostWorkdir());
            var edited = service.update(bound.id(), request("""
                    {"kind":"docker","name":"bound","hostWorkdir":"/srv/project"}
                    """));
            assertEquals("/srv/project", edited.hostWorkdir());
            assertEquals("/workspace", edited.workdir());
            service.update(bound.id(), request("{\"kind\":\"docker\",\"name\":\"bound\"}"));
            assertNull(store.targetById(bound.id()).orElseThrow().hostWorkdir());

            for (String body : List.of(
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"relative\"}",
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"/srv/app\",\"workdir\":\"relative\"}",
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"/srv/app\",\"workdir\":\"/\"}",
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"/srv/app\",\"workdir\":\"/./\"}",
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"/srv/app\",\"workdir\":\"/a/../b\"}",
                    "{\"kind\":\"docker\",\"hostWorkdir\":\"/srv/app\",\"container\":\"existing\"}")) {
                assertThrows(ResponseStatusException.class, () -> service.create(request(body)));
            }
            assertEquals(3, store.targets().size(), "Invalid binding must not create a workspace");
        }
    }

    private TerminalDtos.TargetRequest request(String body) throws Exception {
        return json.readValue(body, TerminalDtos.TargetRequest.class);
    }
}
