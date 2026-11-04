package cc.ginpika.minis.web.terminal;

import cc.ginpika.minis.ai.computer.terminal.WorkspaceTerminals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceTerminalSocket extends TerminalSocket {
    public WorkspaceTerminalSocket(WorkspaceTerminals terminals,ObjectMapper json){
        super(terminals,new TerminalTickets("/ws/workspaces/terminals/"),()->{},json);
    }
    public TerminalTickets.Ticket ticket(String owner,String workspace,String id,long after,String origin){
        return tickets.issue(owner,workspace,id,after,origin);
    }
}
