package cc.ginpika.minis.coding.api;

import cc.ginpika.minis.coding.CodingFacade;
import cc.ginpika.minis.coding.api.CodingDtos.*;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
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
    @GetMapping("/workspaces") public List<Workspace> workspaces(HttpServletRequest req){user(req);if(!coding.capabilities().enabled()){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"编程模块未启用");}return projects.workspaces();}
    @GetMapping("/projects") public List<CodingProject> projects(HttpServletRequest req){return projects.projects(user(req).getId());}
    @PostMapping("/projects") public CodingProject open(HttpServletRequest req,@RequestBody OpenProject input){
        // Existing workspace access is authenticated, independent of the SSH account or SSO role.
        // Bind project ownership to the session; WorkspaceProjects validates the target and path.
        var actor=user(req);
        if(!coding.capabilities().enabled()){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"编程模块未启用");}return projects.open(actor.getId(),input);
    }
    @GetMapping("/projects/{id}/files") public cc.ginpika.minis.agent.workspace.WorkspaceDtos.DirView files(HttpServletRequest req,@PathVariable String id,@RequestParam(defaultValue=".") String path,@RequestParam(required=false) String runId){return projects.list(user(req).getId(),id,path,runId);}
    @GetMapping("/projects/{id}/file") public cc.ginpika.minis.agent.workspace.WorkspaceDtos.FileView file(HttpServletRequest req,@PathVariable String id,@RequestParam String path,@RequestParam(required=false) String runId){return projects.read(user(req).getId(),id,path,runId);}
    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> error(ResponseStatusException error){return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));}
    private SsoUser user(HttpServletRequest request){Object value=request.getAttribute(SsoInterceptor.ATTR);if(!(value instanceof SsoUser user) || user.getId().isBlank()){throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");}if(!CodingFacade.allowsActorAt(user.getId(),request.getRemoteAddr())){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"开发身份仅允许本机访问编程模块；远程部署请配置 SSO");}return user;}
    @GetMapping("/capabilities") public Capabilities capabilities(HttpServletRequest req){user(req);return coding.capabilities();}
    @GetMapping("/repositories") public List<Repository> repositories(HttpServletRequest req){return coding.repositories(user(req).getId());}
    @PostMapping("/repositories") public Repository register(HttpServletRequest req,@RequestBody RegisterRepository input){
        SsoUser user=user(req);if(!"admin".equals(user.getRole())){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仓库路径登记需要管理员身份");}return coding.register(user.getId(),input);
    }
    @GetMapping("/runs") public List<Run> runs(HttpServletRequest req){return coding.runs(user(req).getId());}
    @GetMapping("/sessions") public List<Session> sessions(HttpServletRequest req){return coding.sessions(user(req).getId());}
    @GetMapping("/sessions/{id}/runs") public List<Run> sessionRuns(HttpServletRequest req,@PathVariable String id){return coding.sessionRuns(user(req).getId(),id);}
    @GetMapping("/runs/{id}/session") public Session session(HttpServletRequest req,@PathVariable String id){return coding.sessionOf(user(req).getId(),id);}
    @GetMapping("/roles") public List<Role> roles(HttpServletRequest req){return coding.roles(user(req).getId());}
    @PutMapping("/roles/{id}") public Role role(HttpServletRequest req,@PathVariable String id,@RequestBody RoleConfig input){return coding.saveRole(user(req).getId(),id,input);}
    @GetMapping("/runs/{id}/executions") public List<Child> children(HttpServletRequest req,@PathVariable String id){return coding.children(user(req).getId(),id);}
    @PostMapping("/runs/{id}/executions/{child}/stop") public Map<String,Boolean> stopChild(HttpServletRequest req,@PathVariable String id,@PathVariable String child){coding.stopChild(user(req).getId(),id,child);return Map.of("stopped",true);}
    @PostMapping("/runs/{id}/executions/{child}/resume") public Child resumeChild(HttpServletRequest req,@PathVariable String id,@PathVariable String child,@RequestBody Map<String,String> input){return coding.resumeChild(user(req).getId(),id,child,input.getOrDefault("input","继续原任务，重新核对当前版本。"));}
    @PostMapping("/runs") @ResponseStatus(HttpStatus.ACCEPTED) public Run start(HttpServletRequest req,@RequestBody StartRun input,@RequestHeader(value="Idempotency-Key",required=false) String key){return coding.submit(user(req).getId(),input,key);}
    @GetMapping("/runs/{id}") public Run run(HttpServletRequest req,@PathVariable String id){return coding.require(user(req).getId(),id);}
    @PostMapping("/runs/{id}/resume") @ResponseStatus(HttpStatus.ACCEPTED) public Run resume(HttpServletRequest req,@PathVariable String id,@RequestBody(required=false) ResumeRun input){return coding.resume(user(req).getId(),id,input);}
    @PostMapping("/runs/{id}/messages") public Run steer(HttpServletRequest req,@PathVariable String id,@RequestBody Map<String,String> input){return coding.steer(user(req).getId(),id,input.get("input"));}
    @PostMapping("/runs/{id}/cancel") public Run cancel(HttpServletRequest req,@PathVariable String id){return coding.cancel(user(req).getId(),id);}
    @PostMapping("/runs/{id}/accept") public Run accept(HttpServletRequest req,@PathVariable String id,@RequestBody AcceptChanges input){return coding.accept(user(req).getId(),id,input.artifactHash());}
    @GetMapping("/runs/{id}/diff") public Diff diff(HttpServletRequest req,@PathVariable String id){return coding.diff(user(req).getId(),id);}
    @GetMapping("/runs/{id}/operations") public List<Operation> operations(HttpServletRequest req,@PathVariable String id){return coding.operations(user(req).getId(),id);}
    @GetMapping("/runs/{id}/events") public List<Event> events(HttpServletRequest req,@PathVariable String id,@RequestParam(defaultValue="0") long after){return coding.events(user(req).getId(),id,after);}
    @GetMapping(value="/runs/{id}/events/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest req,@PathVariable String id,@RequestHeader(value="Last-Event-ID",required=false) String header,@RequestParam(defaultValue="0") long after){
        String owner=user(req).getId();coding.require(owner,id);long cursor=after;
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
