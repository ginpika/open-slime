package cc.ginpika.minis.web.terminal;

import java.net.URI;
import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Single-use socket credentials minted only through the authenticated REST endpoint. */
public class TerminalTickets {
    public record Grant(String owner,String project,String terminal,long after,String origin,long expiresAt) {}
    public record Ticket(String url,long expiresAt) {}
    private final Map<String,Grant> tickets=new HashMap<>();
    private final LongSupplier clock;
    private final String socketPath;
    public TerminalTickets(String socketPath){this(socketPath,System::currentTimeMillis);}
    protected TerminalTickets(String socketPath,LongSupplier clock){this.clock=clock;this.socketPath=socketPath;}
    public synchronized Ticket issue(String owner,String project,String terminal,long after,String origin){
        long now=clock.getAsLong();tickets.values().removeIf(ticket->ticket.expiresAt()<=now);
        if(tickets.size()>=256){throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"终端连接请求过多，请稍后重试");}
        String token=UUID.randomUUID().toString().replace("-","");long expires=now+30000;
        tickets.put(token,new Grant(owner,project,terminal,after,normalizeOrigin(origin),expires));
        return new Ticket(socketPath+token,expires);
    }
    public synchronized Grant redeem(String token,String origin){
        Grant grant=tickets.remove(token);
        if(grant==null || grant.expiresAt()<=clock.getAsLong()){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"终端连接票据已过期或已使用");}
        if(!grant.origin().equals(normalizeOrigin(origin))){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"终端连接来源不匹配");}
        return grant;
    }
    static String normalizeOrigin(String text){
        try{
            URI uri=URI.create(text);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))){throw new IllegalArgumentException();}
            int port=uri.getPort();if(port==-1){port=uri.getScheme().equals("https")?443:80;}
            return uri.getScheme()+"://"+uri.getHost().toLowerCase(Locale.ROOT)+":"+port;
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"终端连接来源无效");}
    }
}
