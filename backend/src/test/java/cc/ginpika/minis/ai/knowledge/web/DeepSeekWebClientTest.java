package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import cc.ginpika.minis.config.DeepSeekWebSettings.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class DeepSeekWebClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private DeepSeekWebClient client;
    private int status = 200;
    private String response;
    private final AtomicInteger failFirst = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(json.readTree(exchange.getRequestBody()));
            int code = failFirst.getAndUpdate(v -> Math.max(0, v - 1)) > 0 ? 429 : status;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); client = new DeepSeekWebClient(json);
        response = """
                {"results":[{"title":"网页","url":"https://example.com/a","snippet":"短摘要","summary":"长摘要",
                "content":"正文","rerank_score":2.4,"authority":3,"site_name":null,"date":null},
                {"title":"重复","url":"https://example.com/a"},{"url":"javascript:alert(1)"}]}
                """;
    }
    @AfterEach void close() { if (server != null) server.stop(0); }
    private Config config(boolean v1) { return new Config("http://127.0.0.1:" + server.getAddress().getPort() + (v1 ? "/v1/" : ""), "fixture-key", true, true, true, 5, 2); }
    @Test void searchUsesRawJsonProtocolAndFiltersDuplicateOrUnsafeLinks() {
        var hits = client.search(config(true), "DeepSeek site:example.com", 5, false);
        assertEquals(List.of("/v1/tools/search"), paths); assertEquals("Bearer fixture-key", auth.getFirst());
        assertEquals("DeepSeek site:example.com", bodies.getFirst().path("query").asText());
        assertFalse(bodies.getFirst().has("model")); assertFalse(bodies.getFirst().path("need_content").asBoolean());
        assertEquals(1, hits.size()); assertEquals("", hits.getFirst().content()); assertEquals("", hits.getFirst().date());
        assertEquals(2.4, hits.getFirst().score());
    }
    @Test void fetchNormalizesEndpointAndRequiresRealBody() {
        response = "{\"title\":\"标题\",\"content\":\"# 正文\"}";
        var page = client.fetch(config(false), "https://example.com/article");
        assertEquals("# 正文", page.content()); assertEquals("/v1/tools/fetch", paths.getFirst());
        assertEquals("https://example.com/article", bodies.getFirst().path("url").asText());
        response = "{\"content\":\"\"}";
        assertThrows(IllegalStateException.class, () -> client.fetch(config(false), "https://example.com"));
    }
    @Test void retryIsBoundedAndPermanentErrorsNeverEchoResponseBodies() {
        failFirst.set(1);
        assertEquals(1, client.search(config(false), "hello", 5, true).size()); assertEquals(2, paths.size());
        paths.clear(); status = 402; response = "fixture-key private upstream body";
        var error = assertThrows(IllegalStateException.class, () -> client.search(config(false), "hello", 5, false));
        assertEquals(1, paths.size()); assertTrue(error.getMessage().contains("402")); assertFalse(error.getMessage().contains("fixture-key"));
        paths.clear(); status = 429;
        assertThrows(IllegalStateException.class, () -> client.search(config(false), "hello", 5, false));
        assertEquals(2, paths.size());
    }
    @Test void malformedResponseIsFailureAndInvalidInputSendsNoRequest() {
        response = "{}";
        assertThrows(IllegalStateException.class, () -> client.search(config(false), "hello", 5, false));
        paths.clear();
        assertThrows(IllegalArgumentException.class, () -> client.search(config(false), "😀".repeat(129), 5, false));
        assertThrows(IllegalArgumentException.class, () -> client.fetch(config(false), "https://user:pass@example.com"));
        assertThrows(IllegalArgumentException.class, () -> client.fetch(config(false), "file:///etc/passwd"));
        assertTrue(paths.isEmpty());
    }
}
