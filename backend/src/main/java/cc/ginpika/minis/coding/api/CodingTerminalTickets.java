package cc.ginpika.minis.coding.api;
import cc.ginpika.minis.web.terminal.TerminalTickets;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;
@Component
public class CodingTerminalTickets extends TerminalTickets {
    public CodingTerminalTickets(){super("/ws/coding/terminals/");}
    CodingTerminalTickets(LongSupplier clock){super("/ws/coding/terminals/",clock);}
}
