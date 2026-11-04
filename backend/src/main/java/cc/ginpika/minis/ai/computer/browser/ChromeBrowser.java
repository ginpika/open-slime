package cc.ginpika.minis.ai.computer.browser;

import cc.ginpika.minis.ai.core.tool.ToolOutput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** A separate, always-headless Chrome process. No user's profile, CDP attachment, OS input or focus calls. */
@Service
public class ChromeBrowser {
    private final ObjectMapper json;
    private final String executable;
    private final boolean fakeIp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "computer-chrome"); t.setDaemon(true); return t; });
    private Playwright playwright;
    private Browser browser;
    private final Map<String, Session> sessions = new HashMap<>();
    private static class Session {
        final BrowserContext context;
        final Map<String, Page> tabs = new LinkedHashMap<>();
        final Map<String, Set<String>> refs = new HashMap<>();
        long touched = System.currentTimeMillis();
        Session(BrowserContext context) { this.context = context; }
    }
    public ChromeBrowser(ObjectMapper json,
            @Value("${minis.computer.chrome-executable:}") String executable,
            @Value("${minis.web.allow-fake-ip:false}") boolean fakeIp) {
        this.json = json; this.executable = executable; this.fakeIp = fakeIp;
    }
    public String run(String scope, JsonNode command) {
        Future<String> task = worker.submit(() -> execute(scope, command));
        try { return task.get(40, TimeUnit.SECONDS); }
        catch (InterruptedException error) { task.cancel(true); Thread.currentThread().interrupt(); throw new CancellationException("Chrome 操作已停止，请重新观察页面"); }
        catch (TimeoutException error) { task.cancel(true); throw new IllegalStateException("Chrome 操作超时，结果未知；先重新观察页面再继续"); }
        catch (ExecutionException error) { throw new IllegalStateException(error.getCause().getMessage(), error.getCause()); }
    }
    private void launch() {
        if (browser != null && browser.isConnected()) return;
        sessions.clear();
        if (playwright != null) playwright.close();
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        var options = new BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true)
                .setChannel("chrome").setTimeout(25_000).setArgs(List.of("--disable-dev-shm-usage"));
        if (!executable.isBlank()) options.setExecutablePath(Path.of(executable));
        try { browser = playwright.chromium().launch(options); }
        catch (PlaywrightException error) { throw new IllegalStateException("独立 Chrome 未就绪，请安装 Google Chrome，或配置 MINIS_COMPUTER_CHROME_EXECUTABLE", error); }
    }
    private Session session(String scope, boolean create) {
        if (browser != null && !browser.isConnected()) sessions.clear();
        sessions.entrySet().removeIf(e -> { if (System.currentTimeMillis() - e.getValue().touched < 30 * 60_000) return false; e.getValue().context.close(); return true; });
        var existing = sessions.get(scope);
        if (existing != null) { existing.touched = System.currentTimeMillis(); return existing; }
        if (!create) return null;
        launch();
        if (sessions.size() >= 8) throw new IllegalStateException("独立 Chrome 会话已满，请关闭不用的标签页");
        var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800)
                .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        context.addInitScript("window.RTCPeerConnection = undefined;");
        context.setDefaultTimeout(8_000); context.setDefaultNavigationTimeout(20_000);
        context.route("**/*", route -> { try { BrowserUrls.validate(route.request().url(), fakeIp); route.resume(); } catch (RuntimeException error) { route.abort(); } });
        context.routeWebSocket("**/*", route -> { try { BrowserUrls.validate(route.url().replaceFirst("^ws", "http"), fakeIp); route.connectToServer(); } catch (RuntimeException error) { route.close(); } });
        context.onPage(page -> { page.onDialog(Dialog::dismiss); });
        var created = new Session(context); sessions.put(scope, created); return created;
    }
    private String execute(String scope, JsonNode command) throws Exception {
        String action = command.path("action").asText();
        Session s = session(scope, "open".equals(action));
        if (s != null) s.tabs.entrySet().removeIf(e -> { if (!e.getValue().isClosed()) return false; s.refs.remove(e.getKey()); return true; });
        if ("list".equals(action)) {
            var out = json.createObjectNode().put("environment", "isolated"); var tabs = out.putArray("tabs");
            if (s != null) for (var tab : s.tabs.entrySet()) tabs.addObject().put("tab_id", tab.getKey()).put("url", tab.getValue().url()).put("title", tab.getValue().title());
            return out.toString();
        }
        if (s == null) throw new IllegalArgumentException("当前会话没有独立 Chrome 标签页，请先 open");
        String id = command.path("tab_id").asText();
        Page page;
        if ("open".equals(action) && id.isBlank()) {
            if (s.tabs.size() >= 16) throw new IllegalStateException("当前会话标签页已满，请先 close");
            // Validate before allocating a page.
            BrowserUrls.validate(command.path("url").asText(), fakeIp);
            id = "isolated:" + UUID.randomUUID(); page = s.context.newPage();
            page.onPopup(Page::close); s.tabs.put(id, page);
        } else { page = s.tabs.get(id); if (page == null) throw new IllegalArgumentException("tab_id 不属于当前会话，请先 list"); }
        if ("close".equals(action)) {
            page.close(); s.tabs.remove(id); s.refs.remove(id);
            if (s.tabs.isEmpty()) { s.context.close(); sessions.remove(scope); }
            return json.createObjectNode().put("closed", true).put("tab_id", id).toString();
        }
        Set<String> validRefs = s.refs.get(id);
        if (!Set.of("read", "screenshot").contains(action)) s.refs.remove(id);
        switch (action) {
            case "open" -> page.navigate(BrowserUrls.validate(command.path("url").asText(), fakeIp), new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            case "read", "screenshot" -> { }
            case "click", "fill", "select" -> {
                // Refs are checked before any action; observe() replaces them after every observation.
                String ref = command.path("ref").asText();
                if (validRefs == null || !validRefs.contains(ref)) throw new IllegalArgumentException("ref 已失效，请先 read 并使用最新元素引用");
                var target = page.locator("[data-slime-computer-ref=\"" + ref + "\"]");
                if ("click".equals(action)) { target.evaluate("e => { if(e.tagName === 'A' || e.tagName === 'FORM') e.target='_self'; }"); target.click(); }
                else if ("fill".equals(action)) {
                    if ("password".equalsIgnoreCase(target.getAttribute("type"))) throw new IllegalArgumentException("登录凭证请由用户在 Chrome 中填写，工具不接管密码");
                    target.fill(command.path("text").asText());
                } else target.selectOption(command.path("value").asText());
            }
            default -> throw new IllegalArgumentException("未知 Chrome action");
        }
        return observe(s, id, page, "screenshot".equals(action));
    }
    private String observe(Session s, String id, Page page, boolean screenshot) throws Exception {
        String prefix = "r" + UUID.randomUUID().toString().replace("-", "") + "-";
        Object elements = page.evaluate("""
            prefix => {
              document.querySelectorAll('[data-slime-computer-ref]').forEach(e=>e.removeAttribute('data-slime-computer-ref'));
              return [...document.querySelectorAll('a,button,input,textarea,select,[role=button]')]
                .filter(e=>e.getClientRects().length && getComputedStyle(e).visibility !== 'hidden').slice(0,120)
                .map((e,i)=>{const ref=prefix+i;e.setAttribute('data-slime-computer-ref',ref);
                  return {ref,tag:e.tagName.toLowerCase(),label:(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.name||e.type||'').slice(0,160),href:e.href||'',
                    ...(e.tagName==='SELECT'?{options:[...e.options].slice(0,40).map(o=>({value:o.value,label:o.label}))}:{})};});
            }
            """, prefix);
        JsonNode refs = json.valueToTree(elements); var names = new HashSet<String>(); refs.forEach(e -> names.add(e.path("ref").asText()));
        s.refs.put(id, names);
        String text = page.locator("body").innerText();
        var result = json.createObjectNode().put("environment", "isolated").put("tab_id", id).put("url", page.url())
                .put("title", page.title()).put("text", text.substring(0, Math.min(12_000, text.length()))).put("total_chars", text.length());
        result.set("elements", refs);
        if (!screenshot) return result.toString();
        String data = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(70).setTimeout(8_000)));
        return ToolOutput.image(result.toString(), data);
    }
    @PreDestroy public void close() {
        try { worker.submit(() -> { if (browser != null) browser.close(); if (playwright != null) playwright.close(); sessions.clear(); }).get(5, TimeUnit.SECONDS); }
        catch (Exception ignored) { }
        finally { worker.shutdownNow(); }
    }
}
