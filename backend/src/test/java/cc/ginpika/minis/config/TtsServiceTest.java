package cc.ginpika.minis.config;

import cc.ginpika.minis.config.store.TtsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class TtsServiceTest {
    private final ObjectMapper om = new ObjectMapper();
    private TtsService service() {
        // A shared connection keeps the in-memory SQLite database alive across operations.
        var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tts_profile(id TEXT PRIMARY KEY, config TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE tts_endpoint(id TEXT PRIMARY KEY, config TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE tts_selection(agent_id TEXT PRIMARY KEY, profile_id TEXT)");
        return new TtsService(new TtsStore(jdbc), om);
    }
    private TtsService.Profile profile(TtsService tts,String name, String url) {
        String endpoint=tts.view().endpoints().isEmpty()
            ? tts.saveEndpoint(null,new TtsService.Endpoint(null,"Fish Audio",url,"test-key")).endpoints().getFirst().id()
            : tts.view().endpoints().getFirst().id();
        return new TtsService.Profile(null,name,endpoint,"s2.1-pro","voice-id",null);
    }
    @Test void persistsParametersInheritsDefaultAndClearsDeletedSelections() {
        var tts=service();
        assertThrows(ResponseStatusException.class,()->tts.resolve("a"));
        var a=tts.save(null,profile(tts,"a","https://api.fish.audio")).profiles().getFirst();
        var draft=profile(tts,"b","https://api.fish.audio");
        var params=tts.defaults().put("temperature",0.2);
        var b=tts.save(null,new TtsService.Profile(null,draft.name(),draft.endpointId(),"s2-pro",draft.referenceId(),params)).profiles().getLast();
        assertEquals(a.endpointId(),b.endpointId());
        assertEquals("s2.1-pro",a.model());
        assertEquals("s2-pro",b.model());
        assertThrows(ResponseStatusException.class,()->tts.deleteEndpoint(a.endpointId()));
        tts.saveEndpoint(a.endpointId(),new TtsService.Endpoint(a.endpointId(),"Updated","https://api.fish.audio","new-key"));
        assertEquals("new-key",tts.view().endpoints().getFirst().apiKey());
        assertEquals(a.id(),tts.resolve("a").id());
        tts.select("agent:a",b.id());
        assertEquals(0.2,tts.resolve("a").parameters().path("temperature").asDouble());
        tts.delete(b.id());
        assertEquals(a.id(),tts.resolve("a").id());
        assertThrows(ResponseStatusException.class,()->tts.select("default","missing"));
        tts.delete(a.id());
        assertThrows(ResponseStatusException.class,()->tts.resolve("a"));
        tts.deleteEndpoint(a.endpointId());
        assertTrue(tts.view().endpoints().isEmpty());
    }
    @Test void sendsFishHeadersParametersAndReturnsAudio() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new AtomicReference<String>();
        var model=new AtomicReference<String>();
        var auth=new AtomicReference<String>();
        server.createContext("/v1/tts",exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            model.set(exchange.getRequestHeaders().getFirst("model"));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] audio={1,2,3}; exchange.sendResponseHeaders(200,audio.length);
            exchange.getResponseBody().write(audio); exchange.close();
        });
        server.start();
        try {
            var tts=service();
            var p=tts.save(null,profile(tts,"test","http://127.0.0.1:"+server.getAddress().getPort()+"/v1")).profiles().getFirst();
            tts.saveEndpoint(p.endpointId(),new TtsService.Endpoint(p.endpointId(),"Updated","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","updated-key"));
            assertArrayEquals(new byte[]{1,2,3},tts.speak(p,"你好"));
            var json=om.readTree(request.get());
            assertEquals("你好",json.path("text").asText());
            assertEquals("voice-id",json.path("reference_id").asText());
            assertEquals(0.7,json.path("temperature").asDouble());
            assertEquals("s2.1-pro",model.get());
            assertEquals("Bearer updated-key",auth.get());
            var another=tts.save(null,new TtsService.Profile(null,"Another",p.endpointId(),"s2-pro","voice-other",null)).profiles().getLast();
            tts.speak(another,"另一份档案");
            assertEquals("s2-pro",model.get());
            assertEquals("voice-other",om.readTree(request.get()).path("reference_id").asText());
        } finally { server.stop(0); }
    }
    @Test void testsSavedAndDraftEndpointsAndRejectsAuthenticationOrInvalidResponses() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var responseBody=new AtomicReference<String>("{\"credit\":\"0\"}");
        var auth=new AtomicReference<String>();
        var method=new AtomicReference<String>();
        server.createContext("/wallet/self/api-credit",exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] body=responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var tts=service();
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var draft=new TtsService.Endpoint(null,"Draft",base+"/v1/","draft-key");
            var result=tts.testEndpoint(draft);
            assertTrue(result.ok());
            assertEquals("GET",method.get());
            assertEquals("Bearer draft-key",auth.get());
            assertTrue(tts.view().endpoints().isEmpty());
            var saved=tts.saveEndpoint(null,new TtsService.Endpoint(null,"Saved",base,"saved-key")).endpoints().getFirst();
            assertTrue(tts.testEndpoint(saved.id()).ok());
            assertEquals("Bearer saved-key",auth.get());
            status.set(401);
            result=tts.testEndpoint(saved.id());
            assertFalse(result.ok());
            assertTrue(result.error().contains("API Key"));
            status.set(404);
            assertTrue(tts.testEndpoint(saved.id()).error().contains("Base URL"));
            status.set(200); responseBody.set("<html>not an API</html>");
            assertFalse(tts.testEndpoint(saved.id()).ok());
            responseBody.set("{}");
            assertFalse(tts.testEndpoint(saved.id()).ok());
            assertThrows(ResponseStatusException.class,()->tts.testEndpoint(new TtsService.Endpoint(null,"","invalid","key")));
        } finally { server.stop(0); }
    }
    @Test void exposesSynthesisFailureReasonAndRedactsCredentials() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var status=new java.util.concurrent.atomic.AtomicInteger(402);
        var responseBody=new AtomicReference<String>("{\"message\":\"Insufficient credits\",\"reason\":\"model permission denied test-key\"}");
        server.createContext("/v1/tts",exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type","application/json");
            byte[] body=responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var tts=service();
            var p=tts.save(null,profile(tts,"test","http://127.0.0.1:"+server.getAddress().getPort())).profiles().getFirst();
            var error=assertThrows(ResponseStatusException.class,()->tts.speak(p,"你好"));
            assertEquals(502,error.getStatusCode().value());
            assertTrue(error.getReason().contains("HTTP 402"));
            assertTrue(error.getReason().contains("Insufficient credits"));
            assertFalse(error.getReason().contains("test-key"));
            status.set(422); responseBody.set("{\"detail\":[{\"loc\":[\"body\",\"reference_id\"],\"msg\":\"invalid voice\"}]}");
            error=assertThrows(ResponseStatusException.class,()->tts.speak(p,"你好"));
            assertTrue(error.getReason().contains("invalid voice"));
            status.set(503); responseBody.set("<html>proxy failure</html>");
            error=assertThrows(ResponseStatusException.class,()->tts.speak(p,"你好"));
            assertTrue(error.getReason().contains("HTTP 503"));
            assertFalse(error.getReason().contains("<html>"));
            status.set(200); responseBody.set("{\"message\":\"not audio\"}");
            error=assertThrows(ResponseStatusException.class,()->tts.speak(p,"你好"));
            assertTrue(error.getReason().contains("not audio"));
        } finally { server.stop(0); }
    }
    @Test void speakingSameTextAfterAgentSelectionChangeUsesNewProfile() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/v1/tts",exchange -> {
            var body=om.readTree(exchange.getRequestBody());
            requests.add(body);
            byte[] audio=body.path("reference_id").asText().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,audio.length);
            exchange.getResponseBody().write(audio); exchange.close();
        });
        server.start();
        try {
            var tts=service();
            var a=tts.save(null,profile(tts,"a","http://127.0.0.1:"+server.getAddress().getPort())).profiles().getFirst();
            var b=tts.save(null,new TtsService.Profile(null,"b",a.endpointId(),a.model(),"voice-new",tts.defaults().put("temperature",0.2))).profiles().getLast();
            var controller=new cc.ginpika.minis.web.controller.TtsController(tts);
            var input=new cc.ginpika.minis.web.controller.TtsController.Speech("agent1","同一段历史回复");
            tts.select("agent:agent1",a.id());
            var first=controller.speak(input);
            tts.select("agent:agent1",b.id());
            var second=controller.speak(input);
            assertEquals(a.id(),first.getHeaders().getFirst("X-TTS-Profile-Id"));
            assertEquals(b.id(),second.getHeaders().getFirst("X-TTS-Profile-Id"));
            assertEquals("voice-new",second.getHeaders().getFirst("X-TTS-Reference-Id"));
            assertFalse(java.util.Arrays.equals(first.getBody(),second.getBody()));
            assertEquals(2,requests.size());
            assertEquals(requests.getFirst().path("text"),requests.getLast().path("text"));
            assertEquals("voice-new",requests.getLast().path("reference_id").asText());
            assertEquals(0.2,requests.getLast().path("temperature").asDouble());
            assertEquals("no-store",second.getHeaders().getCacheControl());
        } finally { server.stop(0); }
    }
}
