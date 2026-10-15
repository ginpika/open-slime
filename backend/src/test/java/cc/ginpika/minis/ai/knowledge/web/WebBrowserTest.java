package cc.ginpika.minis.ai.knowledge.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Opt-in real Chromium test: local fixtures exercise the browser, no external service or LLM required. */
class WebBrowserTest {
    @Test void realBrowserNavigationExtractionIsolationAndFrozenEvidence() throws Exception {
        assumeTrue("true".equals(System.getenv("MINIS_BROWSER_TEST")),"Set MINIS_BROWSER_TEST=true to run real-browser integration");
        String executable=System.getenv("MINIS_WEB_EXECUTABLE_PATH");
        if(executable==null) for(String path:List.of("C:/Program Files/Google/Chrome/Application/chrome.exe","C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")) {
            if(Files.isRegularFile(Path.of(path))) { executable=path;break; }
        }
        if(executable==null)executable="";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        AtomicInteger mediaRequests = new AtomicInteger();
        server.createContext("/media",exchange->{
            mediaRequests.incrementAndGet();
            String path=exchange.getRequestURI().getPath();
            if (path.endsWith("sound.wav") && !"fixture=retained".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                exchange.sendResponseHeaders(403,-1); exchange.close(); return;
            }
            if(path.equals("/media/redirect")) {
                exchange.getResponseHeaders().add("Location","http://192.168.1.1/private.wav"); exchange.sendResponseHeaders(302,-1); exchange.close(); return;
            }
            byte[] data=path.endsWith(".png")?Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII="):
                    path.endsWith(".webm")?new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3}:new byte[48];
            exchange.getResponseHeaders().add("Content-Type",path.endsWith(".png")?"image/png":path.endsWith(".webm")?"video/webm":"audio/wav");
            if(path.contains("oversize")) data=new byte[8_000_001];
            exchange.sendResponseHeaders(200,0); // deliberately chunked: no Content-Length
            try { exchange.getResponseBody().write(data); } catch(java.io.IOException ignored) { /* bounded downloader cancels */ }
            exchange.close();
        });
        server.createContext("/",exchange->{
            String body="""
                <!doctype html><html><head><title>Research fixture</title><meta name="author" content="Fixture Author"><style>body{min-height:4000px}#nested{position:fixed;top:10px;right:10px;width:200px;height:150px;overflow:auto}</style></head>
                <body><nav>Navigation that should not become the evidence</nav><article><h1>Source knowledge</h1>
                <p>This is a complete original paragraph explaining the source evidence, its numbers and its conditions. The model must preserve the important details.</p>
                <p>Another paragraph presents a contrasting view and a limitation. The observation is a claim made by its author, not an independently established universal fact.</p>
                <pre>const answer = 42;</pre><table><tr><th>Value</th><th>Unit</th></tr><tr><td>42</td><td>items</td></tr></table>
                <p>A final paragraph provides sufficient article content for the reader extractor to identify the primary text and retain its argument.</p></article>
                <input aria-label="Search" name="q"><a href="/next">Next article</a><div id="environment"></div>
                <div id="nested"><div style="width:1200px;height:1500px">Nested scroll area</div></div><div id="scroll-state">Scroll:0,0,0</div>
                <script>document.querySelector('#environment').textContent='Environment: '+navigator.language+' '+Intl.DateTimeFormat().resolvedOptions().timeZone+' '+navigator.webdriver+' '+navigator.userAgent+' WebSocket:'+typeof WebSocket;</script></body></html>
                """;
            body=body.replace("</article>","<img src='/media/image.png' alt='Evidence image'><audio src='/media/sound.wav' preload='none'></audio><video preload='none'><source src='/media/movie.webm' type='video/webm'></video><audio src='/media/oversize.wav' preload='none'></audio><audio src='/media/redirect' preload='none'></audio></article>");
            body=body.replace("</body>","<script>function reportScroll(){document.querySelector('#scroll-state').textContent='Scroll:'+Math.round(scrollY)+','+Math.round(document.querySelector('#nested').scrollTop)+','+Math.round(document.querySelector('#nested').scrollLeft);} addEventListener('scroll',reportScroll); document.querySelector('#nested').addEventListener('scroll',reportScroll);</script></body>");
            if(exchange.getRequestURI().getPath().equals("/next"))body=body.replace("Source knowledge","Updated knowledge").replace("42","43");
            byte[] data=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","text/html;charset=UTF-8");
            exchange.getResponseHeaders().add("Set-Cookie","fixture=retained; Path=/; SameSite=Lax");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/forbidden")?403:200,data.length);exchange.getResponseBody().write(data);exchange.close();
        });
        server.start();
        WebUrlPolicy fixtures=new WebUrlPolicy(){@Override public String validate(String raw){if(!raw.startsWith(base))throw new IllegalArgumentException("Fixture URL only");return raw;}};
        WebBrowserService browser=new WebBrowserService(fixtures,new ObjectMapper(),executable,true);
        try {
            var view=browser.command("owner-a","chat-a",command(null,"open",base+"/",null,null),true);
            assertFalse(view.screenshot().isBlank());assertEquals("Research fixture",view.title());
            assertTrue(view.text().contains("Environment: zh-CN Asia/Shanghai false"));
            assertFalse(view.text().contains("HeadlessChrome")); assertTrue(view.text().contains("WebSocket:function"));
            String id=view.browserId();
            assertThrows(IllegalArgumentException.class,()->browser.command("owner-b",null,command(id,"read",null,null,null),false));
            var capture=browser.capture("owner-a",id);
            assertTrue(capture.sourceHtml().contains("const answer = 42;"));
            assertTrue(capture.paragraphs().stream().anyMatch(p->p.text().contains("contrasting view")));
            assertTrue(capture.sourceHtml().contains("data:image/png;base64,"));
            assertFalse(capture.sourceHtml().contains("<audio"));assertFalse(capture.sourceHtml().contains("<video"));
            var selected=browser.capture("owner-a",id,new WebDtos.MediaOptions(true,true,true));
            assertTrue(selected.sourceHtml().contains("data:audio/wav;base64,"));
            assertTrue(selected.sourceHtml().contains("data:video/webm;base64,"));
            assertTrue(selected.warnings().stream().anyMatch(w->w.contains("音频"))); // oversized stream and rejected private redirect
            int requests=mediaRequests.get();
            var plain=browser.capture("owner-a",id,new WebDtos.MediaOptions(false,false,false));
            assertEquals(requests,mediaRequests.get()); // unselected media is never archived
            assertFalse(plain.sourceHtml().contains("data:"));
            assertNotEquals(plain.contentHash(),selected.contentHash());
            browser.command("owner-a","chat-a",new WebDtos.Command(id,"scroll",null,null,null,0,1150,80,100.0,240.0),true);
            assertTrue(browser.command("owner-a","chat-a",command(id,"read",null,null,null),false).text().contains("Scroll:0,240,100"));
            browser.command("owner-a","chat-a",new WebDtos.Command(id,"scroll",null,null,null,0,600,400,0.0,300.0),true);
            assertTrue(browser.command("owner-a","chat-a",command(id,"read",null,null,null),false).text().contains("Scroll:300,240,100"));
            browser.command("owner-a","chat-a",command(id,"scroll",null,null,"up"),true);
            assertTrue(browser.command("owner-a","chat-a",command(id,"read",null,null,null),false).text().contains("Scroll:0,240,100"));
            assertThrows(IllegalArgumentException.class,()->browser.command("owner-a","chat-a",new WebDtos.Command(id,"scroll",null,null,null,0,600,400,Double.NaN,0.0),false));
            String stale=view.elements().stream().filter(e->e.tag().equals("input")).findFirst().orElseThrow().ref();
            var refreshed=browser.command("owner-a","chat-a",command(id,"read",null,null,null),false);
            assertThrows(IllegalArgumentException.class,()->browser.command("owner-a","chat-a",command(id,"fill",null,stale,"query"),false));
            String link=refreshed.elements().stream().filter(e->e.label().equals("Next article")).findFirst().orElseThrow().ref();
            // Passive UI polling must not invalidate the references the Agent is about to use.
            assertEquals(1,browser.views("human-user","chat-a").size());
            var next=browser.command("owner-a","chat-a",command(id,"click",null,link,null),false);
            assertTrue(next.text().contains("Updated knowledge"));
            assertTrue(browser.captured("owner-a",capture.captureId()).sourceHtml().contains("const answer = 42;"));
            assertNotEquals(capture.contentHash(),browser.capture("owner-a",id).contentHash());
            assertTrue(browser.views("owner-b",null).isEmpty());
            var forbidden=browser.command("owner-a","chat-a",command(id,"open",base+"/forbidden",null,null),false);
            assertTrue(forbidden.blocked());
            assertThrows(IllegalStateException.class,()->browser.capture("owner-a",id));
            browser.command("owner-a","chat-a",command(id,"close",null,null,null),false);
            assertTrue(browser.views("owner-a",null).isEmpty());
        } finally {browser.close();server.stop(0);}
    }
    private WebDtos.Command command(String id,String action,String url,String ref,String text){return new WebDtos.Command(id,action,url,ref,text,0,null,null);}
}
