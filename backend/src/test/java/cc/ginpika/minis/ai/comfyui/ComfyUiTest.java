package cc.ginpika.minis.ai.comfyui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cc.ginpika.minis.ai.comfyui.integration.GenerateImageTool;
import cc.ginpika.minis.ai.comfyui.integration.GeneratedImageReply;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.web.controller.ComfyUiController;
import cc.ginpika.minis.web.desktop.DesktopTokenFilter;
import cc.ginpika.minis.config.DesktopProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class ComfyUiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private ComfyUiStore store;
    private ComfyUiSettings settings;
    private ComfyUiClient client;
    private ComfyUiService service;
    private HttpServer server;
    private final AtomicReference<JsonNode> submitted = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicInteger submissions = new AtomicInteger();
    private final AtomicInteger historyCalls = new AtomicInteger();
    private String history;
    private int promptStatus = 200;
    private byte[] imageBody;
    private boolean queueFirst;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        store = new ComfyUiStore(jdbc);
        settings = new ComfyUiSettings(store,json);
        client = new ComfyUiClient(json);
        service = new ComfyUiService(settings,client,store);
        history = """
            {"remote-id":{"status":{"completed":true,"status_str":"success"},"outputs":{
              "9":{"images":[{"filename":"result image.png","subfolder":"sub path","type":"output"}]},
              "10":{"images":[{"filename":"preview.png","type":"temp"}]}}}}
            """;
        imageBody = png;
    }
    @AfterEach void close() { if (server != null) server.stop(0); source.destroy(); }

    private String remote() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/comfy/system_stats", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{\"system\":{},\"devices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/comfy/prompt", exchange -> {
            submissions.incrementAndGet();
            submitted.set(json.readTree(exchange.getRequestBody().readAllBytes()));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = (promptStatus == 200 ? "{\"prompt_id\":\"remote-id\"}" : "{\"error\":\"secret-key\"}").getBytes(StandardCharsets.UTF_8);
            if (promptStatus == 503) exchange.getResponseHeaders().set("Retry-After","0");
            exchange.sendResponseHeaders(promptStatus,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/comfy/history/remote-id", exchange -> {
            int attempt = historyCalls.incrementAndGet();
            byte[] bytes = (queueFirst && attempt == 1 ? "{}" : history).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/comfy/view", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(200,imageBody.length); exchange.getResponseBody().write(imageBody); exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/comfy";
    }
    private ComfyUiSettings.Endpoint endpoint(String url) {
        return settings.saveEndpoint(null,new ComfyUiSettings.Endpoint(null,"GPU",url,"secret-key")).endpoints().getLast();
    }
    private ObjectNode workflow() throws Exception {
        return (ObjectNode) json.readTree("""
            {"6":{"class_type":"CLIPTextEncode","inputs":{"text":"template prompt","clip":["4",1]}},
             "3":{"class_type":"KSampler","inputs":{"seed":7,"steps":20,"positive":["6",0]}},
             "9":{"class_type":"SaveImage","inputs":{"images":["3",0]}},
             "10":{"class_type":"PreviewImage","inputs":{"images":["3",0]}}}
            """);
    }
    private ComfyUiSettings.Profile draft(String endpoint, String name) throws Exception {
        return new ComfyUiSettings.Profile(null,name,"插画模型",endpoint,workflow(),"6","text","3","seed","",30);
    }
    private ComfyUiSettings.Profile save(String endpoint, String name) throws Exception {
        return settings.saveProfile(null,draft(endpoint,name)).profiles().getLast();
    }

    @Test void reusesEndpointsAndKeepsDefaultAndExistingImagesIndependentOfDeletedProfiles() throws Exception {
        var endpoint = endpoint(remote());
        var first = save(endpoint.id(),"模型 A");
        var second = save(endpoint.id(),"模型 B");
        assertEquals(first.id(),settings.resolve(null).id());
        assertEquals(first.endpointId(),second.endpointId());
        assertThrows(ResponseStatusException.class, () -> settings.deleteEndpoint(endpoint.id()));
        settings.select(second.id());
        var result = service.generate(null,"a fox");
        settings.deleteProfile(second.id());
        assertEquals(first.id(),settings.resolve(null).id());
        settings.deleteProfile(first.id());
        settings.deleteEndpoint(endpoint.id());
        assertTrue(settings.view().profiles().isEmpty());
        assertArrayEquals(png,service.image(result.images().getFirst().id()).bytes());
        assertThrows(ResponseStatusException.class, () -> settings.resolve("missing"));
    }

    @Test void submitsCopiesPollsQueueAndSavesOnlyFinalOutputWithEncodedViewParameters() throws Exception {
        queueFirst = true;
        var endpoint = endpoint(remote());
        var profile = save(endpoint.id(),"模型");
        var result = service.generate(profile.id(),"一只狐狸 \"你好\"\n雪地");
        assertEquals("Bearer secret-key",auth.get());
        assertEquals(1,submissions.get());
        assertEquals(2,historyCalls.get());
        assertEquals("一只狐狸 \"你好\"\n雪地",submitted.get().path("prompt").path("6").path("inputs").path("text").asText());
        assertFalse(submitted.get().path("client_id").asText().isBlank());
        assertNotEquals(7,submitted.get().path("prompt").path("3").path("inputs").path("seed").asLong());
        assertEquals(20,submitted.get().path("prompt").path("3").path("inputs").path("steps").asInt());
        var original = settings.resolve(profile.id()).workflow();
        assertEquals("template prompt",original.path("6").path("inputs").path("text").asText());
        assertEquals(7,original.path("3").path("inputs").path("seed").asInt());
        assertEquals(1,result.images().size());
        assertEquals("remote-id",result.promptId());
        assertTrue(query.get().contains("filename=result%20image.png"));
        assertTrue(query.get().contains("subfolder=sub%20path"));
        assertEquals("completed",jdbc.queryForObject("SELECT status FROM comfyui_generation WHERE id=?",String.class,result.generationId()));
        assertArrayEquals(png,service.image(result.images().getFirst().id()).bytes());
    }

    @Test void supportsModelSpecificFieldsFixedSeedAndExplicitPreviewOutput() throws Exception {
        var endpoint = endpoint(remote());
        ObjectNode graph = workflow();
        ((ObjectNode)graph.path("6").path("inputs")).put("instruction","original");
        var p = settings.saveProfile(null,new ComfyUiSettings.Profile(null,"另一个模型","",endpoint.id(),graph,"6","instruction","","","10",30)).profiles().getFirst();
        service.generate(p.id(),"new prompt");
        assertEquals("new prompt",submitted.get().path("prompt").path("6").path("inputs").path("instruction").asText());
        assertEquals("template prompt",submitted.get().path("prompt").path("6").path("inputs").path("text").asText());
        assertEquals(7,submitted.get().path("prompt").path("3").path("inputs").path("seed").asInt());
        assertTrue(query.get().contains("filename=preview.png"));
        assertTrue(query.get().contains("type=temp"));
    }

    @Test void rejectsCanvasJsonMissingFieldsAndLinkOverwritesBeforeSaving() throws Exception {
        var endpoint = endpoint("https://gpu.example");
        var p = draft(endpoint.id(),"bad");
        var canvas = json.createObjectNode(); canvas.putArray("nodes");
        assertThrows(ResponseStatusException.class, () -> settings.saveProfile(null,new ComfyUiSettings.Profile(null,p.name(),"",endpoint.id(),canvas,"6","text","","","",30)));
        assertThrows(ResponseStatusException.class, () -> settings.saveProfile(null,new ComfyUiSettings.Profile(null,p.name(),"",endpoint.id(),workflow(),"6","clip","","","",30)));
        assertThrows(ResponseStatusException.class, () -> settings.saveProfile(null,new ComfyUiSettings.Profile(null,p.name(),"",endpoint.id(),workflow(),"6","text","3","positive","",30)));
        assertThrows(ResponseStatusException.class, () -> settings.saveProfile(null,new ComfyUiSettings.Profile(null,p.name(),"",endpoint.id(),workflow(),"6","text","","","missing",30)));
        assertThrows(ResponseStatusException.class, () -> settings.saveProfile(null,new ComfyUiSettings.Profile(null,p.name(),"",endpoint.id(),workflow(),"6","text","","","",1)));
        assertTrue(settings.view().profiles().isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.generate(null,"hello"));
    }

    @Test void testsEndpointWithoutGeneratingAndAllowsNoAuthentication() throws Exception {
        String base = remote();
        var result = service.testEndpoint(new ComfyUiSettings.Endpoint(null,"GPU",base + "/",null));
        assertTrue(result.ok());
        assertNull(auth.get());
        assertEquals(0,submissions.get());
        assertThrows(ResponseStatusException.class, () -> endpoint("file:///etc/test"));
        assertThrows(ResponseStatusException.class, () -> endpoint("https://key@host/path"));
        assertThrows(ResponseStatusException.class, () -> endpoint("https://host/?token=key"));
    }

    @Test void rejectsUpstreamAuthenticationFailureWithoutRetryOrCredentialDisclosure() throws Exception {
        promptStatus = 401;
        var p = save(endpoint(remote()).id(),"model");
        var error = assertThrows(ResponseStatusException.class, () -> service.generate(p.id(),"hello"));
        assertTrue(error.getReason().contains("认证失败"));
        assertFalse(error.getMessage().contains("secret-key"));
        assertEquals(1,submissions.get());
        assertEquals("failed",jdbc.queryForObject("SELECT status FROM comfyui_generation",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM comfyui_image",Integer.class));
    }

    @Test void failsOnExecutionErrorEvenWhenCompletedAndDoesNotFetchImages() throws Exception {
        history = "{\"remote-id\":{\"status\":{\"completed\":true,\"status_str\":\"error\"},\"outputs\":{}}}";
        var p = save(endpoint(remote()).id(),"model");
        var error = assertThrows(ResponseStatusException.class, () -> service.generate(p.id(),"hello"));
        assertTrue(error.getReason().contains("执行失败"));
        assertNull(query.get());
        assertEquals("remote-id",jdbc.queryForObject("SELECT prompt_id FROM comfyui_generation",String.class));
    }

    @Test void doesNotReplaySubmissionEvenWhenGatewayRequestsImmediateRetry() throws Exception {
        promptStatus = 503;
        var p = save(endpoint(remote()).id(),"model");
        assertThrows(ResponseStatusException.class, () -> service.generate(p.id(),"hello"));
        assertEquals(1,submissions.get());
        assertEquals(0,historyCalls.get());
    }

    @Test void rejectsEmptyOutputAndNonImageResponses() throws Exception {
        String base = remote();
        var p = save(endpoint(base).id(),"model");
        imageBody = "<html>proxy error</html>".getBytes(StandardCharsets.UTF_8);
        assertTrue(assertThrows(ResponseStatusException.class, () -> service.generate(p.id(),"hello")).getReason().contains("有效"));
        history = "{\"remote-id\":{\"status\":{\"completed\":true,\"status_str\":\"success\"},\"outputs\":{}}}";
        assertTrue(assertThrows(ResponseStatusException.class, () -> service.generate(p.id(),"hello")).getReason().contains("没有图片"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM comfyui_image",Integer.class));
    }

    @Test void timeoutIsBoundedAndFailedGenerationKeepsRemoteId() throws Exception {
        var endpoint = endpoint("https://gpu.example");
        var p = save(endpoint.id(),"model");
        var error = assertThrows(ResponseStatusException.class, () -> client.awaitImages(endpoint,"remote-id","",System.nanoTime() - 1));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT,error.getStatusCode());
        var fake = mock(ComfyUiClient.class);
        when(fake.submit(any(),any(),anyLong())).thenReturn("submitted-id");
        when(fake.awaitImages(any(),anyString(),anyString(),anyLong())).thenThrow(error);
        assertThrows(ResponseStatusException.class, () -> new ComfyUiService(settings,fake,store).generate(p.id(),"hello"));
        assertEquals("submitted-id",jdbc.queryForObject("SELECT prompt_id FROM comfyui_generation",String.class));
        assertEquals("failed",jdbc.queryForObject("SELECT status FROM comfyui_generation",String.class));
        verify(fake,times(1)).submit(any(),any(),anyLong());
    }

    @Test void registersToolWithProfilesAndNoSecretsThenReturnsPersistedImageUrls() throws Exception {
        var p = save(endpoint(remote()).id(),"绘画模型");
        var tool = new GenerateImageTool(settings,service,json);
        var manager = new ToolManager(List.of(tool),json);
        String schema = manager.toolsOf(ToolSurface.CHAT).toString();
        assertTrue(schema.contains(p.id()));
        assertTrue(schema.contains("绘画模型"));
        assertFalse(schema.contains("secret-key"));
        assertFalse(schema.contains("127.0.0.1"));
        assertTrue(manager.readOnlyToolsOf(ToolSurface.CHAT).isEmpty());
        assertFalse(tool.writes());
        assertNotNull(tool.validate(json.createObjectNode()));
        var result = json.readTree(tool.execute(json.createObjectNode().put("prompt","你好")));
        assertTrue(result.path("images").get(0).path("url").asText().startsWith("/api/comfyui/images/"));
    }

    @Test void imageHttpEndpointRetainsDesktopAuthenticationAndCorrectMime() throws Exception {
        var p = save(endpoint(remote()).id(),"model");
        var image = service.generate(p.id(),"hello").images().getFirst();
        var desktop = new DesktopProps(); desktop.setEnabled(true); desktop.setLocalToken("desktop-token");
        var mvc = MockMvcBuilders.standaloneSetup(new ComfyUiController(settings,service)).addFilters(new DesktopTokenFilter(desktop)).build();
        mvc.perform(get(image.url())).andExpect(status().isUnauthorized());
        mvc.perform(get(image.url()).header("X-Minis-Token","desktop-token")).andExpect(status().isOk())
            .andExpect(content().contentType("image/png")).andExpect(content().bytes(png))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/api/comfyui/images/missing").header("X-Minis-Token","desktop-token")).andExpect(status().isNotFound());
        mvc.perform(post("/api/comfyui/generate").header("X-Minis-Token","desktop-token").contentType("application/json").content("{\"prompt\":\" \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void appendsMissingImagesToChatReplyButNeverDuplicatesOrAcceptsOtherToolOutputs() throws Exception {
        String url = "/api/comfyui/images/11111111-1111-1111-1111-111111111111";
        var call = json.readTree("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"generate_image\"}}]}");
        var result = json.createObjectNode().put("role","tool").put("tool_call_id","call-1");
        result.put("content", "{\"images\":[{\"url\":\"" + url + "\"}]}");
        var messages = List.of(call,result);
        String appended = GeneratedImageReply.appendMissing("已生成",messages,json);
        assertEquals("已生成\n\n![生成图片](" + url + ")",appended);
        assertEquals(appended,GeneratedImageReply.appendMissing(appended,messages,json));
        assertTrue(GeneratedImageReply.appendMissing("[查看图片](" + url + ")",messages,json).contains("![生成图片]"));
        assertEquals("unchanged",GeneratedImageReply.appendMissing("unchanged",List.of(result),json));
    }
}
