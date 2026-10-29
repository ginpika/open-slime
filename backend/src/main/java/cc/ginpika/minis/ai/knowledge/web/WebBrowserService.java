package cc.ginpika.minis.ai.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** All Playwright objects live on one dedicated thread; HTTP and Agent threads never touch them. */
@Service
public class WebBrowserService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-browser"); t.setDaemon(true); return t;
    });
    private final WebUrlPolicy policy;
    private final ObjectMapper om;
    private final String executable;
    private final boolean headless;
    @Value("${minis.web.proxy-server:}") private String proxyServer = "";
    @Value("${minis.web.locale:zh-CN}") private String locale = "zh-CN";
    @Value("${minis.web.timezone:Asia/Shanghai}") private String timezone = "Asia/Shanghai";
    private String userAgent;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private long selectionOrder;
    private final Map<String, OwnedCapture> captures = new LinkedHashMap<>();
    private Playwright playwright;
    private Browser browser;
    private final String readability;
    private final String extraction;

    private static class Session {
        final String id = UUID.randomUUID().toString();
        final String owner, chat;
        final BrowserContext context;
        final boolean interactive;
        final Page page;
        int status;
        long touched = System.currentTimeMillis();
        long selected;
        Session(String owner, String chat, BrowserContext context, boolean interactive) {
            this.owner = owner; this.chat = chat; this.context = context; this.interactive = interactive; this.page = context.newPage();
        }
    }
    private record OwnedCapture(String owner, WebDtos.Capture capture) {}

    public WebBrowserService(WebUrlPolicy policy, ObjectMapper om,
            @Value("${minis.web.executable-path:}") String executable,
            @Value("${minis.web.headless:true}") boolean headless) {
        this.policy = policy; this.om = om; this.executable = executable; this.headless = headless;
        try (var input = getClass().getResourceAsStream("/web/Readability.js")) {
            if (input == null) throw new IllegalStateException("缺少网页正文提取脚本");
            readability = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (var extractor = getClass().getResourceAsStream("/web/SnapshotExtract.js")) {
                if (extractor == null) throw new IllegalStateException("缺少网页结构提取脚本");
                extraction = new String(extractor.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private <T> T execute(Callable<T> task) {
        Future<T> future = worker.submit(() -> { expire(); return task.call(); });
        try { return future.get(90, TimeUnit.SECONDS); }
        catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new IllegalStateException("浏览器操作已中断"); }
        catch (TimeoutException e) { future.cancel(true); throw new IllegalStateException("浏览器操作超时，请稍后刷新"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException ex) throw ex;
            if (e.getCause() instanceof IllegalStateException ex) throw ex;
            if (e.getCause() instanceof PlaywrightException ex) {
                String detail = ex.getMessage();
                String message = detail.contains("ERR_PROXY_CONNECTION_FAILED") ? "浏览器代理无法连接，请检查代理配置后重试"
                        : detail.contains("ERR_NAME_NOT_RESOLVED") ? "无法解析网页域名，请检查地址和网络"
                        : detail.contains("ERR_CONNECTION") ? "无法连接网页，请检查网络或稍后重试"
                        : "浏览器未能完成操作，请刷新网页后重试";
                throw new IllegalStateException(message, ex);
            }
            throw new IllegalStateException("浏览器操作失败：" + e.getCause().getMessage(), e.getCause());
        }
    }

    private void launch() {
        if (browser != null && browser.isConnected()) return;
        if (playwright != null) playwright.close();
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless)
                .setChannel("chromium").setTimeout(30_000).setChromiumSandbox(true)
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(List.of("--disable-dev-shm-usage", "--disable-blink-features=AutomationControlled", "--window-size=1280,800"));
        if (!proxyServer.isBlank()) options.setProxy(proxyServer);
        if (!executable.isBlank()) options.setExecutablePath(Path.of(executable));
        else if (System.getProperty("os.name").toLowerCase().contains("win")) {
            for (String path : List.of("C:/Program Files/Google/Chrome/Application/chrome.exe",
                    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")) {
                if (Files.isRegularFile(Path.of(path))) { options.setExecutablePath(Path.of(path)); break; }
            }
        }
        try {
            browser = playwright.chromium().launch(options);
            CDPSession nativeBrowser = browser.newBrowserCDPSession();
            try { userAgent = nativeBrowser.send("Browser.getVersion").get("userAgent").getAsString().replace("HeadlessChrome/", "Chrome/"); }
            finally { nativeBrowser.detach(); }
        }
        catch (PlaywrightException e) {
            throw new IllegalStateException("浏览器未就绪：请安装 Chrome / Edge 并配置 MINIS_WEB_EXECUTABLE_PATH，"
                    + "或执行 backend/install-browser.ps1 安装 Chromium。" + e.getMessage(), e);
        }
    }

    private Session newSession(String owner, String chat, boolean interactive) {
        launch();
        if (sessions.size() >= 8) throw new IllegalStateException("浏览器会话已达 8 个，请先关闭不用的浏览器");
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1280, 800).setScreenSize(1280, 800).setDeviceScaleFactor(1)
                .setLocale(locale).setTimezoneId(timezone).setUserAgent(userAgent)
                .setColorScheme(ColorScheme.LIGHT).setIsMobile(false).setHasTouch(false)
                .setAcceptDownloads(false).setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        context.route("**/*", route -> {
            try {
                String method = route.request().method();
                if (!interactive && !Set.of("GET", "HEAD", "OPTIONS").contains(method)) { route.abort(); return; }
                policy.validate(route.request().url());
                route.resume();
            } catch (Exception e) { route.abort(); }
        });
        context.routeWebSocket("**/*", socket -> {
            try {
                if (!interactive) { socket.close(new WebSocketRoute.CloseOptions().setCode(1008).setReason("Read-only browser")); return; }
                policy.validate(socket.url().replaceFirst("(?i)^wss:","https:").replaceFirst("(?i)^ws:","http:"));
                socket.connectToServer();
            } catch (Exception e) { socket.close(new WebSocketRoute.CloseOptions().setCode(1008).setReason("Public connections only")); }
        });
        context.addInitScript("window.RTCPeerConnection = undefined;");
        context.setDefaultTimeout(8_000);
        context.setDefaultNavigationTimeout(25_000);
        Session session = new Session(owner, chat, context, interactive);
        session.page.onResponse(response -> {
            if (response.request().isNavigationRequest() && response.request().frame() == session.page.mainFrame()) session.status = response.status();
        });
        session.page.onDialog(Dialog::dismiss);
        context.onPage(page -> { if (page != session.page) page.close(); });
        sessions.put(session.id, session);
        touch(session);
        return session;
    }

    private void touch(Session session) {
        session.touched = System.currentTimeMillis();
        session.selected = ++selectionOrder;
    }

    private Session session(String id, String owner) {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) throw new IllegalArgumentException("浏览器会话不存在或不属于当前执行，请重新打开");
        touch(session);
        return session;
    }

    private Session currentSession(String owner, String chat) {
        if (chat == null || chat.isBlank()) return null;
        return sessions.values().stream()
                .filter(s -> s.owner.equals(owner) && chat.equals(s.chat))
                .max(Comparator.comparingLong(s -> s.selected)).orElse(null);
    }

    private Session resolveSession(String id, String owner, String chat) {
        if (id != null && !id.isBlank()) {
            Session s = sessions.get(id);
            if (s == null || !s.owner.equals(owner) || !Objects.equals(s.chat, chat))
                throw new IllegalArgumentException("浏览器会话不存在或不属于当前会话，请先 list 查看已有页面");
            return session(id, owner);
        }
        Session s = currentSession(owner, chat);
        if (s == null) throw new IllegalArgumentException("当前没有打开的网页，请先 open 一个网页");
        touch(s);
        return s;
    }

    public List<WebDtos.BrowserTab> tabs(String owner, String chat) {
        return execute(() -> {
            Session current = currentSession(owner, chat);
            return sessions.values().stream()
                    .filter(s -> s.owner.equals(owner) && Objects.equals(chat, s.chat))
                    .map(s -> new WebDtos.BrowserTab(s.id, s.page.url(), s.page.title(), s == current))
                    .toList();
        });
    }

    public WebDtos.BrowserView command(String owner, String chat, WebDtos.Command command, boolean screenshot) {
        return command(owner,chat,command,screenshot,false);
    }

    /** Only an authenticated human controller can create or operate an interactive economic browser. */
    public WebDtos.BrowserView interactiveCommand(String owner, WebDtos.Command command) {
        if (!owner.startsWith("human:")) throw new IllegalArgumentException("交互浏览器仅向用户开放");
        return command(owner,null,command,true,true);
    }

    private WebDtos.BrowserView command(String owner, String chat, WebDtos.Command command, boolean screenshot, boolean interactive) {
        if (command == null || command.action() == null) throw new IllegalArgumentException("缺少浏览器 action");
        return execute(() -> {
            boolean implicit = command.browserId() == null || command.browserId().isBlank();
            boolean opening = "open".equals(command.action()) || "new_tab".equals(command.action());
            String url = opening ? policy.validate(command.url()) : null;
            boolean creating = "new_tab".equals(command.action())
                    || ("open".equals(command.action()) && implicit && currentSession(owner, chat) == null);
            if ("new_tab".equals(command.action()) && !implicit)
                throw new IllegalArgumentException("new_tab 不接受 browser_id；导航已有页面请使用 open");
            Session s = creating ? newSession(owner, chat, interactive) : resolveSession(command.browserId(), owner, chat);
            if (s.interactive != interactive && !"read".equals(command.action())) throw new IllegalArgumentException("浏览器用途不匹配，请重新打开");
            Page page = s.page;
            try {
            switch (command.action()) {
                case "open", "new_tab" -> {
                    // A model may repeat open after losing tool history. Preserve the live DOM and scroll position.
                    if (!creating && implicit && url.equals(page.url())) break;
                    Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    policy.validate(page.url());
                    if (response != null && response.status() >= 400 && response.status() != 403 && response.status() != 429)
                        throw new IllegalStateException("网页返回 HTTP " + response.status());
                }
                case "read" -> { }
                case "click" -> {
                    if (command.ref() == null && command.x() != null && command.y() != null) {
                        page.mouse().click(coordinate(command.x(), 1279), coordinate(command.y(), 799),
                                new Mouse.ClickOptions().setClickCount("double".equals(command.text()) ? 2 : 1));
                        break;
                    }
                    Locator target = target(page, command.ref());
                    target.evaluate("e => { if(e.tagName === 'A') e.target='_self'; }");
                    target.click();
                }
                case "fill" -> target(page, command.ref()).fill(command.text() == null ? "" : command.text());
                case "press" -> {
                    String key = command.text() == null ? "Enter" : command.text();
                    if (!Set.of("Enter", "Tab", "Escape", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Backspace", "Delete", "ControlOrMeta+A", "+", "-").contains(key)) throw new IllegalArgumentException("不支持的按键");
                    if (command.ref() == null) page.keyboard().press(key);
                    else target(page, command.ref()).press(key);
                }
                case "type" -> {
                    if (command.text() == null || command.text().length() > 4000) throw new IllegalArgumentException("输入文字最多 4000 字符");
                    page.keyboard().insertText(command.text());
                }
                case "double_click" -> page.mouse().dblclick(coordinate(command.x(),1279), coordinate(command.y(),799));
                case "drag" -> {
                    double x = coordinate(command.x(),1279), y = coordinate(command.y(),799);
                    if (command.deltaX() == null || command.deltaY() == null) throw new IllegalArgumentException("缺少拖动终点");
                    double endX = coordinate(x + command.deltaX(),1279), endY = coordinate(y + command.deltaY(),799);
                    page.mouse().move(x,y); page.mouse().down();
                    try { page.mouse().move(endX,endY,new Mouse.MoveOptions().setSteps(12)); }
                    finally { page.mouse().up(); }
                }
                case "scroll" -> {
                    if (command.x() != null && command.y() != null)
                        page.mouse().move(Math.max(0, Math.min(1279, command.x())), Math.max(0, Math.min(799, command.y())));
                    else page.mouse().move(640, 400);
                    double dx = wheelDelta(command.deltaX() == null ? 0 : command.deltaX());
                    double dy = wheelDelta(command.deltaY() == null ? ("up".equals(command.text()) ? -650 : 650) : command.deltaY());
                    page.mouse().wheel(dx, dy);
                }
                case "back" -> page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                case "close" -> { s.context.close(); sessions.remove(s.id); return null; }
                default -> throw new IllegalArgumentException("不支持的浏览器 action");
            }
            if (!page.url().equals("about:blank")) policy.validate(page.url());
            return observe(s, command.offset() == null ? 0 : command.offset(), screenshot);
            } catch (Exception e) {
                if (creating) { s.context.close(); sessions.remove(s.id); }
                throw e;
            }
        });
    }

    private Locator target(Page page, String ref) {
        if (ref == null || !ref.matches("r[0-9a-f]{8}-[0-9]{1,3}")) throw new IllegalArgumentException("请使用最近一次 read 返回的元素 ref");
        Locator locator = page.locator("[data-minis-ref=\"" + ref + "\"]");
        if (locator.count() != 1) throw new IllegalArgumentException("元素已过期，请重新 read");
        return locator;
    }

    private static double coordinate(Number value, int max) {
        if (value == null || !Double.isFinite(value.doubleValue()) || value.doubleValue() < 0 || value.doubleValue() > max)
            throw new IllegalArgumentException("浏览器坐标超出画面范围");
        return value.doubleValue();
    }

    private static double wheelDelta(double delta) {
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("滚动距离必须为有限数字");
        return Math.max(-2000, Math.min(2000, delta));
    }

    private WebDtos.BrowserView observe(Session s, int requestedOffset, boolean screenshot) {
        String body = s.page.locator("body").innerText();
        int offset = Math.max(0, Math.min(requestedOffset, body.length()));
        String prefix = "r" + UUID.randomUUID().toString().substring(0, 8) + "-";
        Object raw = s.page.evaluate("""
                prefix => {
                  document.querySelectorAll('[data-minis-ref]').forEach(e=>e.removeAttribute('data-minis-ref'));
                  return [...document.querySelectorAll('a,button,input,textarea,select,[role=button]')]
                    .filter(e=>e.getClientRects().length && getComputedStyle(e).visibility!=='hidden')
                    .slice(0,160).map((e,i)=>{const ref=prefix+i;e.setAttribute('data-minis-ref',ref);
                      return {ref,tag:e.tagName.toLowerCase(),label:(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.name||e.type||'').slice(0,180),href:e.href||''};});
                }
                """, prefix);
        List<WebDtos.Element> elements = om.convertValue(raw, om.getTypeFactory().constructCollectionType(List.class, WebDtos.Element.class));
        String screen = screenshot ? Base64.getEncoder().encodeToString(s.page.screenshot(
                new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(65).setTimeout(8_000))) : null;
        return new WebDtos.BrowserView(s.id, s.chat, s.page.url(), s.page.title(),
                body.substring(offset, Math.min(body.length(), offset + 20_000)), body.length(), offset,
                elements, isBlocked(s, body), screen);
    }

    private boolean isBlocked(Session s, String body) {
        return s.status == 403 || s.status == 429 || blocked(s.page.url(), s.page.title(), body);
    }

    static boolean blocked(String url, String title, String body) {
        String start = body.substring(0, Math.min(1200, body.length())).toLowerCase();
        return url.contains("/sorry/") || title.toLowerCase().matches(".*(just a moment|access denied|security check|captcha).*" )
                || start.contains("our systems have detected unusual traffic")
                || start.contains("verify you are human") || start.contains("checking your browser before accessing");
    }

    public WebDtos.Capture capture(String owner, String browserId) {
        return capture(owner, browserId, WebDtos.MediaOptions.defaults());
    }

    public WebDtos.Capture capture(String owner, String browserId, WebDtos.MediaOptions options) {
        return capture(owner, null, browserId, options);
    }

    public WebDtos.Capture capture(String owner, String chat, String browserId, WebDtos.MediaOptions options) {
        WebDtos.MediaOptions media = options == null ? WebDtos.MediaOptions.defaults() : options;
        return execute(() -> {
            // Existing direct API callers identify an owned browser; Agent calls also enforce the chat boundary.
            Session s = chat == null && browserId != null && !browserId.isBlank()
                    ? session(browserId, owner) : resolveSession(browserId, owner, chat);
            policy.validate(s.page.url());
            String body = s.page.locator("body").innerText();
            if (isBlocked(s, body)) throw new IllegalStateException("当前页面为验证或访问拦截页" + (s.status >= 400 ? "（HTTP " + s.status + "）" : "") + "，请先在浏览器中处理");
            JsonNodeArticle article = extract(s.page, media);
            if (article.content().length() > 2_000_000) throw new IllegalStateException("网页正文结构过大，请选择更具体的文章页面");
            var document = Jsoup.parseBodyFragment(article.content(), s.page.url());
            List<String> warnings = new ArrayList<>();
            try (var archive = new WebMediaArchive(policy, s.context, proxyServer, userAgent, locale, s.page.url())) {
                int count = 0;
                var images = new ArrayList<>(document.select("img"));
                images.sort(Comparator.comparingInt(image -> image.closest(".snapshot-gallery") != null ? 0
                        : image.closest(".snapshot-infobox") != null ? 1 : image.hasAttr("data-snapshot-gif") ? 2 : 3));
                if (media.includeImages()) for (var image : images) {
                    if (++count > SnapshotHtml.MAX_IMAGES) { image.removeAttr("src"); continue; }
                    List<String> sources = new ArrayList<>();
                    sources.add(image.absUrl("data-snapshot-original"));
                    sources.add(image.absUrl("src"));
                    sources.add(image.absUrl("data-src"));
                    sources.add(image.absUrl("data-snapshot-fallback"));
                    String data = "";
                    for (String url : sources.stream().filter(source -> !source.isBlank()).distinct().toList()) {
                        data = archive.download(url, SnapshotHtml.IMAGE_TYPES,
                                image.hasAttr("data-snapshot-gif") ? 16_000_000 : 12_000_000, true);
                        if (!data.isBlank()) break;
                    }
                    if (image.hasAttr("data-snapshot-gif") && !data.isBlank() && !data.startsWith("data:image/gif;"))
                        warnings.add("GIF 原格式未能保留，已保存可获取的图片格式；请检查动画信息是否完整");
                    image.attr("src", data);
                }
                for (String tag : List.of("audio", "video")) {
                    count = 0;
                    for (var element : document.select(tag)) {
                        if (!(tag.equals("audio") ? media.includeAudio() : media.includeVideo())) { element.remove(); continue; }
                        String data = "";
                        if (++count <= (tag.equals("audio") ? 4 : 2)) {
                            List<String> sources = new ArrayList<>();
                            sources.add(element.absUrl("src"));
                            element.select("source").forEach(source -> sources.add(source.absUrl("src")));
                            for (String url : sources.stream().distinct().limit(3).toList()) {
                                data = archive.download(url, tag.equals("audio") ? SnapshotHtml.AUDIO_TYPES : SnapshotHtml.VIDEO_TYPES,
                                        tag.equals("audio") ? 8_000_000 : 16_000_000, false);
                                if (!data.isBlank()) break;
                            }
                        }
                        element.select("source").remove();
                        element.attr("src", data);
                        if (media.includeImages() && element.hasAttr("poster"))
                            element.attr("poster", archive.download(element.absUrl("poster"), SnapshotHtml.IMAGE_TYPES, 4_000_000, true));
                    }
                }
            } catch (IllegalArgumentException e) { warnings.add(e.getMessage()); }
            SnapshotHtml.Evidence evidence = SnapshotHtml.clean(document.body().html(), s.page.url(), media);
            int chars = evidence.paragraphs().stream().mapToInt(p -> p.text().length()).sum();
            if (chars < 80) throw new IllegalStateException("未提取到足够正文，请打开文章详情或等待加载后重试");
            if (chars > 120_000) throw new IllegalStateException("正文超过 12 万字符，请选择更具体的页面");
            warnings.addAll(evidence.warnings());
            if (article.fallback()) warnings.add("未识别到文章结构，已按页面正文提取；请检查导航和无关内容");
            if (evidence.html().length() > 128_000_000) throw new IllegalStateException("快照超过 1.28 亿字符，请减少媒体选项后重试");
            WebDtos.Capture capture = new WebDtos.Capture(UUID.randomUUID().toString(), s.id, s.page.url(),
                    article.title(), article.author(), article.publishedAt(), System.currentTimeMillis(),
                    SnapshotHtml.hash(evidence.html()), evidence.paragraphs(), warnings, evidence.html());
            if (captures.size() >= 32) captures.remove(captures.keySet().iterator().next());
            while (!captures.isEmpty() && captures.values().stream().mapToLong(c -> c.capture().sourceHtml().length()).sum()
                    + capture.sourceHtml().length() > 128_000_000) captures.remove(captures.keySet().iterator().next());
            captures.put(capture.captureId(), new OwnedCapture(owner, capture));
            return capture;
        });
    }

    private record JsonNodeArticle(String title, String author, String publishedAt, String content, boolean fallback) {}

    private JsonNodeArticle extract(Page page, WebDtos.MediaOptions media) {
        Object raw = page.evaluate("options => {\n" + readability + "\n" + extraction + "\nreturn extractSnapshot(options);\n}",
                Map.of("images",media.includeImages(),"audio",media.includeAudio(),"video",media.includeVideo(),
                    "marker", "MINISMEDIA_" + UUID.randomUUID().toString().replace("-", "") + "_"));
        return om.convertValue(raw, JsonNodeArticle.class);
    }

    public WebDtos.Capture captured(String owner, String id) {
        return execute(() -> {
            OwnedCapture result = captures.get(id);
            if (result == null || !result.owner().equals(owner)) throw new IllegalArgumentException("正文提取已过期或不属于当前执行，请重新提取");
            return result.capture();
        });
    }

    /** Observation only: refresh never invalidates the Agent's current element references. */
    public List<WebDtos.BrowserView> views(String owner, String chat) {
        return execute(() -> {
            List<WebDtos.BrowserView> result = new ArrayList<>();
            for (Session s : sessions.values()) {
                if ((chat != null && chat.equals(s.chat)) || (chat == null && owner.equals(s.owner))) {
                    if (s.page.isClosed()) continue;
                    String text = s.page.locator("body").innerText();
                    result.add(new WebDtos.BrowserView(s.id, s.chat, s.page.url(), s.page.title(), "", text.length(), 0,
                            List.of(), isBlocked(s, text), Base64.getEncoder().encodeToString(
                            s.page.screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(65).setTimeout(8_000)))));
                }
            }
            return result;
        });
    }

    private void expire() {
        long threshold = System.currentTimeMillis() - 30 * 60_000;
        sessions.values().removeIf(s -> { if (s.touched < threshold || s.page.isClosed()) { s.context.close(); return true; } return false; });
        captures.values().removeIf(c -> c.capture().capturedAt() < threshold);
    }

    @PreDestroy
    public void close() {
        try { worker.submit(() -> { if (playwright != null) playwright.close(); }).get(10, TimeUnit.SECONDS); }
        catch (Exception ignored) { }
        worker.shutdownNow();
    }
}
