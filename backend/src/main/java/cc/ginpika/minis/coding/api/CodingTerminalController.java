package cc.ginpika.minis.coding.api;

import cc.ginpika.minis.coding.CodingFacade;
import cc.ginpika.minis.coding.runner.CodingTerminals;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/coding/projects/{project}/terminals")
public class CodingTerminalController {
    public record Size(int cols,int rows) {}
    public record Input(String data) {}
    public record Connect(long after) {}
    private final CodingTerminals terminals;
    private final CodingFacade coding;
    private final CodingTerminalTickets tickets;
    public CodingTerminalController(CodingTerminals terminals,CodingFacade coding,CodingTerminalTickets tickets){this.terminals=terminals;this.coding=coding;this.tickets=tickets;}
    private String owner(HttpServletRequest request){
        String owner=CodingAccess.owner(request);
        if(!coding.capabilities().enabled()){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"编程模块未启用");}return owner;
    }
    @GetMapping public List<CodingTerminals.View> list(HttpServletRequest req,@PathVariable String project){return terminals.list(owner(req),project);}
    @PostMapping public CodingTerminals.View create(HttpServletRequest req,@PathVariable String project,@RequestBody Size size){return terminals.create(owner(req),project,size.cols(),size.rows());}
    @PostMapping("/{id}/connect") public CodingTerminalTickets.Ticket connect(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestBody Connect input){
        String owner=owner(req);terminals.checkAccess(owner,project,id,input.after());
        String origin=req.getHeader("Origin");if(origin==null){var uri=java.net.URI.create(req.getRequestURL().toString());origin=uri.getScheme()+"://"+uri.getRawAuthority();}
        return tickets.issue(owner,project,id,input.after(),origin);
    }
    @GetMapping("/{id}") public CodingTerminals.Output read(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestParam(defaultValue="0") long after){return terminals.read(owner(req),project,id,after);}
    @PostMapping("/{id}/input") public Map<String,Boolean> input(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestBody Input input){terminals.input(owner(req),project,id,input.data());return Map.of("ok",true);}
    @PostMapping("/{id}/resize") public Map<String,Boolean> resize(HttpServletRequest req,@PathVariable String project,@PathVariable String id,@RequestBody Size size){terminals.resize(owner(req),project,id,size.cols(),size.rows());return Map.of("ok",true);}
    @DeleteMapping("/{id}") public Map<String,Boolean> delete(HttpServletRequest req,@PathVariable String project,@PathVariable String id){terminals.delete(owner(req),project,id);return Map.of("ok",true);}
    @ExceptionHandler(ResponseStatusException.class) public org.springframework.http.ResponseEntity<Map<String,String>> error(ResponseStatusException error){return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));}
}
