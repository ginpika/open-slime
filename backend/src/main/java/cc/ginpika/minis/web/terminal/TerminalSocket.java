package cc.ginpika.minis.web.terminal;

import cc.ginpika.minis.ai.terminal.InteractiveTerminals;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Output metadata + binary bytes are acknowledged after xterm renders, bounding pending output. */
public class TerminalSocket extends TextWebSocketHandler implements HandshakeInterceptor {
    private static final String GRANT=TerminalSocket.class.getName()+".grant";
    private static final class Connection {
        final WebSocketSession socket;final TerminalTickets.Grant grant;
        long sent,acked;volatile boolean closed;volatile Thread pump;
        Connection(WebSocketSession socket,TerminalTickets.Grant grant){this.socket=socket;this.grant=grant;sent=acked=grant.after();}
        synchronized void sent(long offset){sent=offset;}
        synchronized void acknowledge(long offset){if(offset<acked || offset>sent){throw new IllegalArgumentException("终端输出确认游标无效");}acked=offset;notifyAll();}
        synchronized void awaitAck() throws InterruptedException,IOException {
            long until=System.currentTimeMillis()+30000;
            while(!closed && acked<sent){long remaining=until-System.currentTimeMillis();if(remaining<=0){throw new IOException("终端接收超时，请重新连接");}wait(remaining);}
        }
        synchronized void stop(){closed=true;notifyAll();if(pump!=null){pump.interrupt();}}
    }
    private final InteractiveTerminals terminals;
    protected final TerminalTickets tickets;
    private final Runnable checkEnabled;
    private final ObjectMapper json;
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    public TerminalSocket(InteractiveTerminals terminals,TerminalTickets tickets,Runnable checkEnabled,ObjectMapper json){this.terminals=terminals;this.tickets=tickets;this.checkEnabled=checkEnabled;this.json=json;}
    private void enabled(){checkEnabled.run();}
    @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){
        try{
            enabled();String path=request.getURI().getPath();String token=path.substring(path.lastIndexOf('/')+1);
            var grant=tickets.redeem(token,request.getHeaders().getOrigin());
            terminals.checkAccess(grant.owner(),grant.project(),grant.terminal(),grant.after());attributes.put(GRANT,grant);return true;
        }catch(ResponseStatusException error){response.setStatusCode(error.getStatusCode());return false;}
    }
    @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception error) {}
    @Override public synchronized void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        if(!(socket.getAttributes().get(GRANT) instanceof TerminalTickets.Grant grant)){socket.close(CloseStatus.POLICY_VIOLATION);return;}
        if(connections.size()>=128){socket.close(CloseStatus.SERVICE_OVERLOAD);return;}
        socket.setTextMessageSizeLimit(65536);
        var connection=new Connection(socket,grant);connections.put(socket.getId(),connection);
        connection.pump=Thread.ofVirtual().unstarted(()->stream(connection));connection.pump.start();
    }
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message) throws Exception {
        Connection connection=connections.get(socket.getId());if(connection==null || connection.closed){return;}
        try{
            enabled();var row=json.readTree(message.getPayload());var grant=connection.grant;
            switch(row.path("type").asText()){
                case "ack" -> {if(!row.path("offset").isIntegralNumber() || !row.path("offset").canConvertToLong()){throw new IllegalArgumentException("输出游标无效");}connection.acknowledge(row.path("offset").asLong());}
                case "input" -> {if(!row.path("data").isTextual()){throw new IllegalArgumentException("终端输入无效");}terminals.input(grant.owner(),grant.project(),grant.terminal(),row.path("data").asText());}
                case "resize" -> terminals.resize(grant.owner(),grant.project(),grant.terminal(),row.path("cols").asInt(),row.path("rows").asInt());
                default -> throw new IllegalArgumentException("未知终端消息");
            }
        }catch(Exception error){fail(connection,error);}
    }
    private void stream(Connection connection){
        var grant=connection.grant;long after=grant.after();
        try{
            while(!connection.closed && connection.socket.isOpen()){
                enabled();var output=terminals.awaitOutput(grant.owner(),grant.project(),grant.terminal(),after);
                if(connection.closed){break;}
                if(output.data().length>0){
                    connection.sent(output.nextOffset());
                    synchronized(connection.socket){
                        send(connection,Map.of("type","output","offset",output.nextOffset(),"reset",output.reset()));
                        connection.socket.sendMessage(new BinaryMessage(output.data()));
                    }
                    connection.awaitAck();after=output.nextOffset();
                }
                if(!output.running()){
                    send(connection,Map.of("type","exit","error",output.error()==null?"":output.error()));
                    terminals.delete(grant.owner(),grant.project(),grant.terminal());connection.socket.close(CloseStatus.NORMAL);break;
                }
                if(output.data().length==0){synchronized(connection.socket){connection.socket.sendMessage(new PingMessage());}}
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(Exception error){if(!connection.closed){fail(connection,error);}}
        finally{connections.remove(connection.socket.getId(),connection);connection.stop();}
    }
    private void send(Connection connection,Object value) throws IOException {synchronized(connection.socket){if(connection.socket.isOpen()){connection.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));}}}
    private void fail(Connection connection,Exception error){
        try{
            if(error instanceof ResponseStatusException status && (status.getStatusCode().value()==404 || status.getStatusCode().value()==409)){send(connection,Map.of("type","exit","error",status.getStatusCode().value()==404?"":String.valueOf(status.getReason())));}
            else{String reason=error instanceof ResponseStatusException status?status.getReason():error.getMessage();send(connection,Map.of("type","error","message",reason==null?"终端连接失败":reason));}
            connection.socket.close(CloseStatus.SERVER_ERROR);
        }catch(IOException ignored){}finally{connections.remove(connection.socket.getId(),connection);connection.stop();}
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){var connection=connections.remove(socket.getId());if(connection!=null){connection.stop();}}
    @Override public void handleTransportError(WebSocketSession socket,Throwable exception) throws Exception {var connection=connections.get(socket.getId());if(connection!=null){fail(connection,new IOException("终端网络连接中断",exception));}}
    @PreDestroy public void close(){connections.values().forEach(connection->{connection.stop();try{connection.socket.close(CloseStatus.GOING_AWAY);}catch(IOException ignored){}});connections.clear();}
}
