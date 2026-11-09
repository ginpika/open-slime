package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.config.*;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
@EnabledIfEnvironmentVariable(named="MINIS_TEST_LOOPBACK",matches="true",disabledReason="HTTP 回环测试需要显式启用；受限环境跳过，不重复尝试绑定端口")
class OpenAiChatProtocolTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private OpenAiChatClient client;
    private ModelTarget target;
    private final AtomicReference<String> response=new AtomicReference<>();
    private final AtomicReference<Integer> status=new AtomicReference<>(200);
    private final AtomicReference<String> request=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] data=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",status.get()==200?"text/event-stream":"application/json");
            exchange.sendResponseHeaders(status.get(),data.length);try(var stream=exchange.getResponseBody()){stream.write(data);}
        });server.start();
        client=new OpenAiChatClient(new LlmProps(),mock(SettingsService.class),json,mock(TokenUsageService.class));
        target=new ModelTarget("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-key","fixture-model");
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    private com.fasterxml.jackson.databind.node.ObjectNode body(){var body=json.createObjectNode();body.putArray("messages").addObject().put("role","assistant").put("content","previous").put("reasoning_content","exact prior reasoning");return body;}
    @Test void streamedReasoningIsPreservedAndSentBackUnchanged() throws Exception {
        response.set("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think \"}}]}\n\n"
                +"data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"fully\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        var result=client.chatRaw(target,body());assertEquals("think fully",result.message().path("reasoning_content").asText());
        assertEquals("exact prior reasoning",json.readTree(request.get()).path("messages").get(0).path("reasoning_content").asText());
    }
    @Test void eofBeforeCompletionNeverReturnsTruncatedToolArgumentsAsAValidResult(){
        response.set("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"apply_patch\",\"arguments\":\"{\"}}]}}]}\n\n");
        var error=assertThrows(OpenAiChatClient.ChatFailure.class,()->client.chatRaw(target,body()));assertTrue(error.retryable());assertTrue(error.getMessage().contains("完成前断开"));
    }
    @Test void invalidReasoningRequestIsA400FailureRatherThanATransientNetworkFailure(){
        status.set(400);response.set("{\"error\":{\"message\":\"The reasoning_content in the thinking mode must be passed back to the API.\"}}");
        var error=assertThrows(OpenAiChatClient.ChatFailure.class,()->client.chatRaw(target,body()));assertEquals(400,error.status());assertFalse(error.retryable());
    }
    @Test void serviceFailureIsRetryable(){
        status.set(503);response.set("{\"error\":{\"message\":\"temporarily unavailable\"}}");
        var error=assertThrows(OpenAiChatClient.ChatFailure.class,()->client.chatRaw(target,body()));assertEquals(503,error.status());assertTrue(error.retryable());
    }

    @Test void submissionReportsFinalWireBodyForProviderFailureAndSkipsPreCancelledRequest() throws Exception {
        status.set(503);
        response.set("{\"error\":{\"message\":\"temporarily unavailable\"}}");
        List<ObjectNode> sent = new ArrayList<>();
        List<Long> lengths = new ArrayList<>();
        OpenAiChatClient.SubmissionListener observer = (wire, bytes) -> {
            sent.add(wire.deepCopy());
            lengths.add(bytes);
        };
        var draft = body().put("model", "preview-model");
        draft.putArray("messages").addObject().put("role", "user").put("content", "检查请求字节数");
        assertThrows(OpenAiChatClient.ChatFailure.class,
                () -> client.chatRaw(target, draft, null, "chat", null, observer));
        assertEquals(1, sent.size());
        assertEquals(json.readTree(request.get()), sent.getFirst());
        assertEquals("fixture-model", sent.getFirst().path("model").asText());
        assertTrue(sent.getFirst().path("stream_options").path("include_usage").asBoolean());
        assertEquals((long) request.get().getBytes(StandardCharsets.UTF_8).length, lengths.getFirst());

        var cancelled = new OpenAiChatClient.CallControl();
        cancelled.cancel();
        assertThrows(OpenAiChatClient.ChatFailure.class,
                () -> client.chatRaw(target, draft, null, "chat", cancelled, observer));
        assertEquals(1, sent.size());
    }
}
