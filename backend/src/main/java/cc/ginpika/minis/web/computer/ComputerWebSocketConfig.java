package cc.ginpika.minis.web.computer;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class ComputerWebSocketConfig implements WebSocketConfigurer {
    private final ChromeSocket socket;
    public ComputerWebSocketConfig(ChromeSocket socket) { this.socket = socket; }
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/computer/chrome/{pairing}").addInterceptors(socket).setAllowedOriginPatterns("chrome-extension://*");
    }
}
