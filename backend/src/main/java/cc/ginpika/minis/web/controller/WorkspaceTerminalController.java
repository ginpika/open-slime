package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.computer.terminal.InteractiveTerminals;
import cc.ginpika.minis.ai.computer.terminal.WorkspaceTerminals;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import cc.ginpika.minis.web.terminal.TerminalTickets;
import cc.ginpika.minis.web.terminal.WorkspaceTerminalSocket;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/workspaces/{workspace}/terminals")
public class WorkspaceTerminalController {
    public record Size(int cols,int rows) {}
    public record Connect(long after) {}
    private final WorkspaceTerminals terminals;
    private final WorkspaceTerminalSocket socket;
    public WorkspaceTerminalController(WorkspaceTerminals terminals,WorkspaceTerminalSocket socket){this.terminals=terminals;this.socket=socket;}
    private String owner(HttpServletRequest request){
        if(request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user && user.getId()!=null && !user.getId().isBlank()){return user.getId();}
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
    }
    @GetMapping public List<InteractiveTerminals.View> list(HttpServletRequest req,@PathVariable String workspace){return terminals.list(owner(req),workspace);}
    @PostMapping public InteractiveTerminals.View create(HttpServletRequest req,@PathVariable String workspace,@RequestBody Size size){return terminals.create(owner(req),workspace,size.cols(),size.rows());}
    @PostMapping("/{id}/connect") public TerminalTickets.Ticket connect(HttpServletRequest req,@PathVariable String workspace,@PathVariable String id,@RequestBody Connect input){
        String owner=owner(req);terminals.checkAccess(owner,workspace,id,input.after());
        String origin=req.getHeader("Origin");
        if(origin==null){var uri=java.net.URI.create(req.getRequestURL().toString());origin=uri.getScheme()+"://"+uri.getRawAuthority();}
        return socket.ticket(owner,workspace,id,input.after(),origin);
    }
    @DeleteMapping("/{id}") public Map<String,Boolean> delete(HttpServletRequest req,@PathVariable String workspace,@PathVariable String id){terminals.delete(owner(req),workspace,id);return Map.of("ok",true);}
    @ExceptionHandler(ResponseStatusException.class) public org.springframework.http.ResponseEntity<Map<String,String>> error(ResponseStatusException error){return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));}
}
