package cc.ginpika.minis.coding;

import cc.ginpika.minis.ai.computer.terminal.ExecTarget;
import cc.ginpika.minis.coding.api.*;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.coding.runner.CodingTerminals;
import cc.ginpika.minis.coding.workspace.WorkspaceProjects;
import cc.ginpika.minis.config.*;
import cc.ginpika.minis.web.desktop.DesktopTokenFilter;
import cc.ginpika.minis.web.sso.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real HTTP + WebSocket upgrade through desktop/SSO gates, without touching the user's database. */
@SpringBootTest(classes=CodingTerminalWebSocketTest.Fixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.config.name=terminal-websocket-test","logging.level.root=WARN"})
class CodingTerminalWebSocketTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration(exclude=DataSourceAutoConfiguration.class)
    @Import({CodingTerminalController.class,CodingTerminalSocket.class,CodingTerminalWebSocketConfig.class,CodingTerminalTickets.class,WebConfig.class,DesktopTokenFilter.class})
    static class Fixture {
        @Bean cc.ginpika.minis.coding.integration.CodingMemory codingMemory(){return mock(cc.ginpika.minis.coding.integration.CodingMemory.class);}
        static final List<Shell> shells=new CopyOnWriteArrayList<>();
        @Bean DesktopProps desktop(){var props=new DesktopProps();props.setEnabled(true);props.setLocalToken("fixture-token");return props;}
        @Bean SsoInterceptor sso(DesktopProps desktop,ObjectMapper json){var service=mock(SsoService.class);when(service.isEnabled()).thenReturn(false);return new SsoInterceptor(new SsoProps(),desktop,service,json);}
        @Bean CodingFacade coding(){var coding=mock(CodingFacade.class);when(coding.capabilities()).thenReturn(new Capabilities(true,false,"",60,30,"workspace"));return coding;}
        @Bean cc.ginpika.minis.coding.engine.CodingSubagents subagents(){return mock(cc.ginpika.minis.coding.engine.CodingSubagents.class);}
        @Bean cc.ginpika.minis.coding.engine.CodingRoles roles(){return mock(cc.ginpika.minis.coding.engine.CodingRoles.class);}
        @Bean WorkspaceProjects projects() throws Exception {
            var projects=mock(WorkspaceProjects.class);
            when(projects.project(anyString(),eq("p"))).thenReturn(new CodingProject("p","fixture","w","workspace","docker","app",null,true,0));
            when(projects.openShell(anyString(),eq("p"),anyInt(),anyInt())).thenAnswer(inv->{var shell=new Shell();shells.add(shell);return shell;});return projects;
        }
        @Bean CodingTerminals terminals(WorkspaceProjects projects){return new CodingTerminals(projects);}
    }
    static class Shell implements ExecTarget.Shell {
        final PipedInputStream output=new PipedInputStream(65536);final PipedOutputStream feed;
        final ByteArrayOutputStream input=new ByteArrayOutputStream();volatile int cols,rows;
        Shell() throws IOException{feed=new PipedOutputStream(output);}
        public InputStream output(){return output;}public OutputStream input(){return input;}
        public void resize(int cols,int rows){this.cols=cols;this.rows=rows;}
        public void close() throws IOException{feed.close();output.close();}
    }
    static class Listener implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();final BlockingQueue<byte[]> bytes=new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();final ObjectMapper json=new ObjectMapper();
        final StringBuilder text=new StringBuilder();final ByteArrayOutputStream binary=new ByteArrayOutputStream();
        public void onOpen(WebSocket socket){socket.request(1);}
        public CompletionStage<?> onText(WebSocket socket,CharSequence data,boolean last){
            try{text.append(data);if(last){messages.add(json.readTree(text.toString()));text.setLength(0);}}catch(Exception error){closed.completeExceptionally(error);}
            socket.request(1);return null;
        }
        public CompletionStage<?> onBinary(WebSocket socket,ByteBuffer data,boolean last){byte[] chunk=new byte[data.remaining()];data.get(chunk);binary.writeBytes(chunk);if(last){bytes.add(binary.toByteArray());binary.reset();}socket.request(1);return null;}
        public CompletionStage<?> onClose(WebSocket socket,int status,String reason){closed.complete(status);return null;}
        public void onError(WebSocket socket,Throwable error){closed.completeExceptionally(error);}
        JsonNode next() throws InterruptedException{var row=messages.poll(5,TimeUnit.SECONDS);assertNotNull(row,"missing socket control message");return row;}
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired CodingTerminals terminals;
    private final HttpClient http=HttpClient.newHttpClient();
    private HttpResponse<String> post(String path,String body,boolean token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header("Origin","http://terminal.test");
        if(token){request.header("X-Minis-Token","fixture-token");}
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String ticket(String id,long after) throws Exception {
        var response=post("/api/coding/projects/p/terminals/"+id+"/connect","{\"after\":"+after+"}",true);assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).path("url").asText();
    }
    private WebSocket connect(String path,String origin,Listener listener){return http.newWebSocketBuilder().header("Origin",origin).buildAsync(URI.create("ws://127.0.0.1:"+port+path),listener).join();}
    @Test void authenticatedStreamSupportsFlowControlReconnectInputResizeAndExit() throws Exception {
        assertEquals(401,post("/api/coding/projects/p/terminals","{\"cols\":80,\"rows\":24}",false).statusCode());
        var created=post("/api/coding/projects/p/terminals","{\"cols\":80,\"rows\":24}",true);assertEquals(200,created.statusCode(),created.body());
        String id=json.readTree(created.body()).path("id").asText();Shell shell=Fixture.shells.getLast();
        String wrongOrigin=ticket(id,0);
        var denied=assertThrows(CompletionException.class,()->connect(wrongOrigin,"http://evil.test",new Listener()));
        assertEquals(403,((WebSocketHandshakeException)denied.getCause()).getResponse().statusCode());
        String path=ticket(id,0);var listener=new Listener();WebSocket socket=connect(path,"http://terminal.test",listener);
        try{
            var replay=assertThrows(CompletionException.class,()->connect(path,"http://terminal.test",new Listener()));assertEquals(401,((WebSocketHandshakeException)replay.getCause()).getResponse().statusCode());
            shell.feed.write("你好\r\n".getBytes(StandardCharsets.UTF_8));shell.feed.flush();
            var first=listener.next();assertEquals("output",first.path("type").asText());assertArrayEquals("你好\r\n".getBytes(StandardCharsets.UTF_8),listener.bytes.poll(5,TimeUnit.SECONDS));
            shell.feed.write("next".getBytes(StandardCharsets.UTF_8));shell.feed.flush();
            assertNull(listener.messages.poll(100,TimeUnit.MILLISECONDS),"server must wait for render ACK before sending more output");
            socket.sendText("{\"type\":\"ack\",\"offset\":"+first.path("offset").asLong()+"}",true).join();
            var second=listener.next();assertArrayEquals("next".getBytes(StandardCharsets.UTF_8),listener.bytes.poll(5,TimeUnit.SECONDS));
            socket.sendText("{\"type\":\"ack\",\"offset\":"+second.path("offset").asLong()+"}",true).join();
            socket.sendText("{\"type\":\"input\",\"data\":\"echo 中文\\r\"}",true).join();
            socket.sendText("{\"type\":\"resize\",\"cols\":120,\"rows\":35}",true).join();
            long until=System.currentTimeMillis()+3000;while(shell.cols!=120 && System.currentTimeMillis()<until){Thread.sleep(5);}
            assertEquals(120,shell.cols);assertEquals(35,shell.rows);assertEquals("echo 中文\r",shell.input.toString(StandardCharsets.UTF_8));
            socket.sendClose(WebSocket.NORMAL_CLOSURE,"reconnect").join();listener.closed.get(5,TimeUnit.SECONDS);
            var resumed=new Listener();socket=connect(ticket(id,second.path("offset").asLong()),"http://terminal.test",resumed);
            shell.feed.write("resumed".getBytes(StandardCharsets.UTF_8));shell.feed.flush();
            var finalOutput=resumed.next();assertArrayEquals("resumed".getBytes(StandardCharsets.UTF_8),resumed.bytes.poll(5,TimeUnit.SECONDS));
            socket.sendText("{\"type\":\"ack\",\"offset\":"+finalOutput.path("offset").asLong()+"}",true).join();
            shell.feed.close();assertEquals("exit",resumed.next().path("type").asText());assertEquals(1000,resumed.closed.get(5,TimeUnit.SECONDS));
            assertTrue(terminals.list("local-dev","p").isEmpty());
        }finally{socket.abort();terminals.delete("local-dev","p",id);}
    }
}
