package cc.ginpika.minis.web.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WorkspaceTerminalWebSocketConfig implements WebSocketConfigurer {
    private final WorkspaceTerminalSocket socket;
    public WorkspaceTerminalWebSocketConfig(WorkspaceTerminalSocket socket){this.socket=socket;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(socket,"/ws/workspaces/terminals/{ticket}").addInterceptors(socket).setAllowedOriginPatterns("*");
    }
}
