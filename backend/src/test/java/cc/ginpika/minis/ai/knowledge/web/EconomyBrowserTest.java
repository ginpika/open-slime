package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EconomyBrowserTest {
    @Test void realCanvasInputDragPostAndLiveQuotesAreHumanScoped() throws Exception {
        assumeTrue("true".equals(System.getenv("MINIS_BROWSER_TEST")));
        String executable=System.getenv("MINIS_WEB_EXECUTABLE_PATH");
        if(executable==null) for(String p:List.of("C:/Program Files/Google/Chrome/Application/chrome.exe","C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"))if(Files.isRegularFile(Path.of(p))){executable=p;break;}
        if(executable==null)executable="";
        var posts=new AtomicInteger();var sockets=new AtomicInteger();
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var ws=new ServerSocket(0,5,InetAddress.getByName("127.0.0.1"));
        var wsThread=new Thread(()->{
            try(var socket=ws.accept()) {
                socket.setSoTimeout(10000);
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                String key="";String line;
                while((line=reader.readLine())!=null && !line.isEmpty()) if(line.toLowerCase().startsWith("sec-websocket-key:"))key=line.substring(line.indexOf(':')+1).strip();
                String accept=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                var out=socket.getOutputStream();out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                byte[] quote="quote:42".getBytes(StandardCharsets.UTF_8);out.write(0x81);out.write(quote.length);out.write(quote);out.flush();sockets.incrementAndGet();
                while(socket.getInputStream().read()!=-1) { }
            } catch(Exception ignored) { }
        },"economy-fixture-ws");wsThread.setDaemon(true);wsThread.start();
        String base="http://127.0.0.1:"+http.getAddress().getPort();
        http.createContext("/",exchange->{
            String html="""
              <!doctype html><html><head><title>Weekly canvas fixture</title></head><body>
              <input style="position:absolute;left:20px;top:20px;width:220px;height:50px" oninput="document.querySelector('#query').textContent='typed:'+this.value">
              <button style="position:absolute;left:20px;top:100px;width:180px;height:50px" onclick="fetch('/post',{method:'POST',body:'query'}).then(r=>r.text()).then(t=>document.querySelector('#post').textContent=t)">Query</button>
              <canvas width="400" height="250" style="position:absolute;left:100px;top:200px;background:#ddd"></canvas>
              <div style="position:absolute;top:500px"><p id="query"></p><p id="post"></p><p id="quote"></p><p id="click"></p><p id="drag"></p><p id="double"></p></div>
              <script>const c=document.querySelector('canvas');c.onclick=e=>document.querySelector('#click').textContent='click:'+e.offsetX+','+e.offsetY;
              c.onpointerup=e=>document.querySelector('#drag').textContent='end:'+e.clientX+','+e.clientY;
              c.ondblclick=e=>document.querySelector('#double').textContent='double:ok';
              new WebSocket('ws://127.0.0.1:WS_PORT/quotes').onmessage=e=>document.querySelector('#quote').textContent=e.data;</script>
              </body></html>
              """.replace("WS_PORT",String.valueOf(ws.getLocalPort()));
            byte[] bytes=html.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/html;charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        http.createContext("/post",exchange->{posts.incrementAndGet();byte[] bytes="post:ok".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});http.start();
        var policy=new WebUrlPolicy(){@Override public String validate(String url){if(!url.startsWith(base) && !url.startsWith("http://127.0.0.1:"+ws.getLocalPort()))throw new IllegalArgumentException("fixture only");return url;}};
        var browser=new WebBrowserService(policy,new ObjectMapper(),executable,true);
        try {
            assertThrows(IllegalArgumentException.class,()->browser.interactiveCommand("agent:a",cmd(null,"open",base,null,null,null,null)));
            var view=browser.interactiveCommand("human:a",cmd(null,"open",base,null,null,null,null));String id=view.browserId();
            assertFalse(view.screenshot().isBlank());
            browser.interactiveCommand("human:a",cmd(id,"click",null,null,60,40,null));
            browser.interactiveCommand("human:a",cmd(id,"type",null,"周线TEST",null,null,null));
            assertTrue(browser.interactiveCommand("human:a",cmd(id,"read",null,null,null,null,null)).text().contains("typed:周线TEST"));
            browser.interactiveCommand("human:a",cmd(id,"click",null,null,60,120,null));
            var clicked=browser.interactiveCommand("human:a",cmd(id,"click",null,null,150,250,null));
            assertTrue(clicked.text().contains("click:50,50"),clicked.text());
            assertTrue(browser.interactiveCommand("human:a",cmd(id,"click",null,"double",150,250,null)).text().contains("double:ok"));
            var dragged=browser.interactiveCommand("human:a",new WebDtos.Command(id,"drag",null,null,null,0,150,250,100.0,80.0));
            assertTrue(dragged.text().contains("end:250,330"),dragged.text());
            assertTrue(dragged.text().contains("post:ok"));assertTrue(dragged.text().contains("quote:42"));assertEquals(1,posts.get());assertEquals(1,sockets.get());
            assertThrows(IllegalArgumentException.class,()->browser.interactiveCommand("human:b",cmd(id,"read",null,null,null,null,null)));
            assertThrows(IllegalArgumentException.class,()->browser.interactiveCommand("human:a",cmd(id,"click",null,null,1300,50,null)));
            assertThrows(IllegalArgumentException.class,()->browser.command("human:a",null,cmd(id,"type",null,"forbidden",null,null,null),false));
            var readonly=browser.command("agent:a","chat",cmd(null,"open",base,null,null,null,null),false);
            browser.command("agent:a","chat",cmd(readonly.browserId(),"click",null,null,60,120,null),false);
            browser.command("agent:a","chat",cmd(readonly.browserId(),"read",null,null,null,null,null),false);
            assertEquals(1,posts.get(),"knowledge/Agent browsers remain read-only");
        } finally {browser.close();http.stop(0);ws.close();}
    }
    private WebDtos.Command cmd(String id,String action,String url,String text,Integer x,Integer y,String ref) {return new WebDtos.Command(id,action,url,ref,text,0,x,y);}
}
