package cc.ginpika.minis.coding.api;

import cc.ginpika.minis.coding.CodingFacade;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/coding")
public class CodingController {
    private final CodingFacade coding;
    private final cc.ginpika.minis.coding.workspace.WorkspaceProjects projects;
    public CodingController(CodingFacade coding,cc.ginpika.minis.coding.workspace.WorkspaceProjects projects){this.coding=coding;this.projects=projects;}
    @GetMapping("/workspaces") public List<Workspace> workspaces(HttpServletRequest req){CodingAccess.owner(req);if(!coding.capabilities().enabled()){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"编程模块未启用");}return projects.workspaces();}
    @GetMapping("/projects") public List<CodingProject> projects(HttpServletRequest req){return projects.projects(CodingAccess.owner(req));}
    @DeleteMapping("/projects/{id}/recent") public Map<String,Boolean> removeRecent(HttpServletRequest req,@PathVariable String id){projects.removeRecent(CodingAccess.owner(req),id);return Map.of("ok",true);}
    @PostMapping("/projects") public CodingProject open(HttpServletRequest req,@RequestBody OpenProject input){
        // Existing workspace access is authenticated, independent of the SSH account or SSO role.
        // Bind project ownership to the session; WorkspaceProjects validates the target and path.
        String owner=CodingAccess.owner(req);
        if(!coding.capabilities().enabled()){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"编程模块未启用");}return projects.open(owner,input);
    }
    @GetMapping("/projects/{id}/files") public cc.ginpika.minis.agent.workspace.WorkspaceDtos.DirView files(HttpServletRequest req,@PathVariable String id,@RequestParam(defaultValue=".") String path,@RequestParam(required=false) String runId){return projects.list(CodingAccess.owner(req),id,path,runId);}
    @GetMapping("/projects/{id}/file") public cc.ginpika.minis.agent.workspace.WorkspaceDtos.FileView file(HttpServletRequest req,@PathVariable String id,@RequestParam String path,@RequestParam(required=false) String runId){return projects.read(CodingAccess.owner(req),id,path,runId);}
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> error(ResponseStatusException error){return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));}
    @GetMapping("/capabilities") public Capabilities capabilities(HttpServletRequest req){CodingAccess.owner(req);return coding.capabilities();}
    @GetMapping("/repositories") public List<Repository> repositories(HttpServletRequest req){return coding.repositories(CodingAccess.owner(req));}
    @PostMapping("/repositories") public Repository register(HttpServletRequest req,@RequestBody RegisterRepository input){
        return coding.register(CodingAccess.administrator(req),input);
    }
    @GetMapping("/runs") public List<Run> runs(HttpServletRequest req){return coding.runs(CodingAccess.owner(req));}
    @GetMapping("/sessions") public List<Session> sessions(HttpServletRequest req){return coding.sessions(CodingAccess.owner(req));}
    @GetMapping("/sessions/{id}/runs") public List<Run> sessionRuns(HttpServletRequest req,@PathVariable String id){return coding.sessionRuns(CodingAccess.owner(req),id);}
    @GetMapping("/runs/{id}/session") public Session session(HttpServletRequest req,@PathVariable String id){return coding.sessionOf(CodingAccess.owner(req),id);}
    @GetMapping("/roles") public List<Role> roles(HttpServletRequest req){return coding.roles(CodingAccess.owner(req));}
    @PutMapping("/roles/{id}") public Role role(HttpServletRequest req,@PathVariable String id,@RequestBody RoleConfig input){return coding.saveRole(CodingAccess.owner(req),id,input);}
    @GetMapping("/runs/{id}/executions") public List<Child> children(HttpServletRequest req,@PathVariable String id){return coding.children(CodingAccess.owner(req),id);}
    @PostMapping("/runs/{id}/executions/{child}/stop") public Map<String,Boolean> stopChild(HttpServletRequest req,@PathVariable String id,@PathVariable String child){coding.stopChild(CodingAccess.owner(req),id,child);return Map.of("stopped",true);}
    @PostMapping("/runs/{id}/executions/{child}/resume") public Child resumeChild(HttpServletRequest req,@PathVariable String id,@PathVariable String child,@RequestBody Map<String,String> input){return coding.resumeChild(CodingAccess.owner(req),id,child,input.getOrDefault("input","继续原任务，重新核对当前版本。"));}
    @PostMapping("/runs") @ResponseStatus(HttpStatus.ACCEPTED) public Run start(HttpServletRequest req,@RequestBody StartRun input,@RequestHeader(value="Idempotency-Key",required=false) String key){return coding.submit(CodingAccess.owner(req),input,key);}
    @GetMapping("/runs/{id}") public Run run(HttpServletRequest req,@PathVariable String id){return coding.require(CodingAccess.owner(req),id);}
    @PostMapping("/runs/{id}/resume") @ResponseStatus(HttpStatus.ACCEPTED) public Run resume(HttpServletRequest req,@PathVariable String id,@RequestBody(required=false) ResumeRun input){return coding.resume(CodingAccess.owner(req),id,input);}
    @PostMapping("/runs/{id}/messages") public Run steer(HttpServletRequest req,@PathVariable String id,@RequestBody Map<String,String> input){return coding.steer(CodingAccess.owner(req),id,input.get("input"));}
    @PostMapping("/runs/{id}/cancel") public Run cancel(HttpServletRequest req,@PathVariable String id){return coding.cancel(CodingAccess.owner(req),id);}
    @PostMapping("/runs/{id}/accept") public Run accept(HttpServletRequest req,@PathVariable String id,@RequestBody AcceptChanges input){return coding.accept(CodingAccess.owner(req),id,input.artifactHash());}
    @GetMapping("/runs/{id}/diff") public Diff diff(HttpServletRequest req,@PathVariable String id){return coding.diff(CodingAccess.owner(req),id);}
    @GetMapping("/runs/{id}/operations") public List<Operation> operations(HttpServletRequest req,@PathVariable String id){return coding.operations(CodingAccess.owner(req),id);}
    @GetMapping("/runs/{id}/permissions") public Map<String,Boolean> permissions(HttpServletRequest req,@PathVariable String id){return coding.permissions(CodingAccess.owner(req),id);}
    @GetMapping("/runs/{id}/events") public List<Event> events(HttpServletRequest req,@PathVariable String id,@RequestParam(defaultValue="0") long after){return coding.events(CodingAccess.owner(req),id,after);}
    @GetMapping(value="/runs/{id}/events/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest req,@PathVariable String id,@RequestHeader(value="Last-Event-ID",required=false) String header,@RequestParam(defaultValue="0") long after){
        String owner=CodingAccess.owner(req);coding.require(owner,id);long cursor=after;
        if(header!=null){try{cursor=Math.max(cursor,Long.parseLong(header));}catch(NumberFormatException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"事件游标无效");}}
        SseEmitter emitter=new SseEmitter(300_000L);AtomicBoolean alive=new AtomicBoolean(true);
        emitter.onCompletion(()->alive.set(false));emitter.onTimeout(()->alive.set(false));emitter.onError(error->alive.set(false));
        long initial=cursor;
        Thread.startVirtualThread(()->{
            long last=initial,heartbeat=System.currentTimeMillis();
            try{
                while(alive.get()){
                    var rows=coding.events(owner,id,last);
                    for(Event event:rows){if(!alive.get()){break;}emitter.send(SseEmitter.event().id(Long.toString(event.id())).name(event.type()).data(event.payload()));last=event.id();}
                    if(System.currentTimeMillis()-heartbeat>15_000){emitter.send(SseEmitter.event().comment("heartbeat"));heartbeat=System.currentTimeMillis();}
                    Thread.sleep(500);
                }
            }catch(Exception error){alive.set(false);emitter.complete();}
        });
        return emitter;
    }
}
