package cc.ginpika.minis.coding.api;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CodingTerminalTicketsTest {
    @Test void ticketsExpireAreSingleUseAndBindEveryConnectionAttribute(){
        var now=new AtomicLong(1000);var tickets=new CodingTerminalTickets(now::get);
        var ticket=tickets.issue("alice","p","t",42,"https://ide.example");
        String token=ticket.url().substring(ticket.url().lastIndexOf('/')+1);
        var grant=tickets.redeem(token,"https://ide.example:443");
        assertEquals("alice",grant.owner());assertEquals("p",grant.project());assertEquals("t",grant.terminal());assertEquals(42,grant.after());
        assertThrows(ResponseStatusException.class,()->tickets.redeem(token,"https://ide.example"));
        var expired=tickets.issue("alice","p","t",0,"http://localhost:5173");now.addAndGet(30000);
        assertThrows(ResponseStatusException.class,()->tickets.redeem(expired.url().substring(expired.url().lastIndexOf('/')+1),"http://localhost:5173"));
    }
    @Test void ticketCannotBeUsedFromAnotherOriginAndMissingOriginIsRejected(){
        var tickets=new CodingTerminalTickets();var ticket=tickets.issue("alice","p","t",0,"http://localhost:5173");
        String token=ticket.url().substring(ticket.url().lastIndexOf('/')+1);
        assertThrows(ResponseStatusException.class,()->tickets.redeem(token,"http://localhost:5174"));
        for(String origin:new String[]{null,"null","file:///tmp/index.html","https://evil@example.com","https://example.com/path"}){
            assertThrows(ResponseStatusException.class,()->tickets.issue("alice","p","t",0,origin));
        }
    }
}
