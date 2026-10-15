package cc.ginpika.minis.ai.llm;

import cc.ginpika.minis.config.*;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
@EnabledIfEnvironmentVariable(named="MINIS_TEST_LOOPBACK",matches="true")
class UsageRecordingTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicReference<String> response=new AtomicReference<>();
    private TokenUsageService usage;
    private ModelTarget target;
    @BeforeEach void setup() throws Exception {
        usage=mock(TokenUsageService.class);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] data=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,data.length);
            try(var out=exchange.getResponseBody()){out.write(data);}
        });
        server.start();
        target=new ModelTarget("http://127.0.0.1:"+server.getAddress().getPort(),"fixture-key","m");
    }
    @AfterEach void close(){server.stop(0);}
    private OpenAiChatClient client(){return new OpenAiChatClient(new LlmProps(),mock(SettingsService.class),json,usage);}
    private com.fasterxml.jackson.databind.node.ObjectNode body(){
        var body=json.createObjectNode();
        body.putArray("messages").addObject().put("role","user").put("content","fixture prompt");
        return body;
    }
    @Test void interruptedOutputIsRecordedOnceAsPartialInsteadOfDisappearing() {
        response.set("data: {\"choices\":[{\"delta\":{\"content\":\"partial answer\"}}]}\n\n");
        assertThrows(OpenAiChatClient.ChatFailure.class,()->client().chatRaw(target,body()));
        var capture=ArgumentCaptor.forClass(TokenUsageService.Usage.class);
        verify(usage,times(1)).record(eq("chat"),eq(target),capture.capture(),eq("interrupted"));
        assertEquals("partial",capture.getValue().quality());
        assertTrue(capture.getValue().completionTokens()>0);
    }
    @Test void usageTailIsRecordedOnceWithCachedAndReasoningDetails() {
        response.set("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                +"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_tokens_details\":{\"cached_tokens\":30},\"completion_tokens_details\":{\"reasoning_tokens\":10}}}\n\ndata: [DONE]\n\n");
        client().chatRaw(target,body());
        var capture=ArgumentCaptor.forClass(TokenUsageService.Usage.class);
        verify(usage,times(1)).record(eq("chat"),eq(target),capture.capture(),eq("success"));
        assertEquals("reported",capture.getValue().quality());
        assertEquals(30,capture.getValue().cachedTokens()); assertEquals(120,capture.getValue().totalTokens());
    }
    @Test void completeOutputWithoutUsageIsExplicitlyEstimated() {
        response.set("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        client().chatRaw(target,body());
        var capture=ArgumentCaptor.forClass(TokenUsageService.Usage.class);
        verify(usage).record(eq("chat"),eq(target),capture.capture(),eq("success"));
        assertEquals("estimated",capture.getValue().quality()); assertTrue(capture.getValue().promptTokens()>0);
    }
    @Test void embeddingTotalOnlyBecomesRealInputAndTestSourceIsPreserved() {
        response.set("{\"data\":[{\"embedding\":[0.1,0.2]}],\"usage\":{\"total_tokens\":12}}");
        var client=new EmbeddingClient(new LlmProps(),mock(SettingsService.class),json,usage);
        client.embed(target,"fixture","model-test");
        var capture=ArgumentCaptor.forClass(TokenUsageService.Usage.class);
        verify(usage).record(eq("model-test"),eq(target),capture.capture(),eq("success"));
        assertEquals(12,capture.getValue().promptTokens()); assertEquals("reported",capture.getValue().quality());
    }
    @Test void malformedResponseAndPreCancelledRequestAreStillVisibleAsUnknown() {
        response.set("data: {invalid}\n\n");
        assertThrows(OpenAiChatClient.ChatFailure.class,()->client().chatRaw(target,body()));
        var capture=ArgumentCaptor.forClass(TokenUsageService.Usage.class);
        verify(usage).record(eq("chat"),eq(target),capture.capture(),eq("failed"));
        assertEquals("partial",capture.getValue().quality());
        reset(usage);
        var control=new OpenAiChatClient.CallControl();control.cancel();
        assertThrows(OpenAiChatClient.ChatFailure.class,()->client().chatRaw(target,body(),null,"chat",control));
        verify(usage).record(eq("chat"),eq(target),any(),eq("interrupted"));
    }
}

