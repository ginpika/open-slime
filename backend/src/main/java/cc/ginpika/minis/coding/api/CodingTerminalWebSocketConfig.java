package cc.ginpika.minis.coding.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class CodingTerminalWebSocketConfig implements WebSocketConfigurer {
    private final CodingTerminalSocket socket;
    public CodingTerminalWebSocketConfig(CodingTerminalSocket socket){this.socket=socket;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        // The interceptor compares Origin against the authenticated ticket's exact origin,
        // including the browser-facing port behind Vite/nginx, before accepting an upgrade.
        registry.addHandler(socket,"/ws/coding/terminals/{ticket}").addInterceptors(socket).setAllowedOriginPatterns("*");
    }
}
