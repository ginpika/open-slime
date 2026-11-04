package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.computer.browser.ChromeBridge;
import cc.ginpika.minis.ai.computer.window.WindowCapture;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/computer")
public class ComputerController {
    private final ChromeBridge chrome;
    private final WindowCapture windows;
    public ComputerController(ChromeBridge chrome, WindowCapture windows) { this.chrome = chrome; this.windows = windows; }
    @GetMapping public Map<String, Object> status() {
        return Map.of("desktopLocal", chrome.desktopLocal(), "chromeConnected", chrome.connected(),
                "windowCapture", windows.available(), "isolatedChrome", true);
    }
    @PostMapping("/chrome/pair") public Map<String, String> pair(HttpServletRequest request) {
        if (!chrome.desktopLocal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅桌面端本机模式可以连接用户 Chrome");
        try {
            if (!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chrome 配对只接受本机请求");
            String secret = chrome.pair();
            return Map.of("connectionUrl", "ws://127.0.0.1:" + request.getLocalPort() + "/ws/computer/chrome/" + secret);
        } catch (java.net.UnknownHostException error) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无法确认本机请求"); }
    }
    @DeleteMapping("/chrome/connection") public Map<String, Boolean> disconnect() {
        if (!chrome.desktopLocal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅桌面端本机模式可以管理 Chrome 连接");
        chrome.disconnect(); return Map.of("connected", false);
    }
    @GetMapping(value = "/chrome/extension", produces = "application/zip")
    public org.springframework.http.ResponseEntity<byte[]> extension() throws java.io.IOException {
        if (!chrome.desktopLocal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chrome 扩展仅供桌面端本机模式配对");
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String name : new String[]{"manifest.json", "background.js", "browser.js", "popup.html", "popup.js"}) {
                try (var input = getClass().getResourceAsStream("/computer/chrome-extension/" + name)) {
                    if (input == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "扩展资源缺失，请重新组装后端运行时");
                    zip.putNextEntry(new java.util.zip.ZipEntry("slime-chrome-extension/" + name));
                    input.transferTo(zip); zip.closeEntry();
                }
            }
        }
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=slime-chrome-extension.zip")
                .header("Cache-Control", "no-store").body(bytes.toByteArray());
    }
}
