package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.roundtable.CollaborationService;
import cc.ginpika.minis.agent.roundtable.CollaborationStore;
import cc.ginpika.minis.ai.core.UserScope;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

@RestController
public class CollaborationController {
    private final CollaborationService collaboration;
    private final cc.ginpika.minis.agent.roundtable.RoundtableRewindService rewinds;
    private final cc.ginpika.minis.agent.roundtable.CollaborationTaskLifecycle lifecycle;
    public CollaborationController(CollaborationService collaboration, cc.ginpika.minis.agent.roundtable.RoundtableRewindService rewinds,
            cc.ginpika.minis.agent.roundtable.CollaborationTaskLifecycle lifecycle) {
        this.collaboration = collaboration; this.rewinds = rewinds;this.lifecycle=lifecycle;
    }
    public record Members(List<String> memberIds) {}
    public record NewTask(String projectId, String title, List<String> memberIds, String coordinatorId) {}
    public record NewIssue(String title, List<String> memberIds) {}
    public record Conclusion(String conclusion) {}
    public record Rewind(Long messageId) {}
    public record Archive(Boolean archived) {}

    @PutMapping("/api/agents/collaboration/tasks/{id}/archive")
    public Map<String,Boolean> archive(@PathVariable String id,@RequestBody Archive request,HttpServletRequest http) {
        return asUser(http,()->{
            if(request.archived()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供 archived 归档状态");
            lifecycle.archive(id,request.archived());return Map.of("archived",request.archived());
        });
    }
    @DeleteMapping("/api/agents/collaboration/tasks/{id}")
    public Map<String,Boolean> delete(@PathVariable String id,HttpServletRequest http) {
        return asUser(http,()->{lifecycle.delete(id);return Map.of("deleted",true);});
    }
    @GetMapping("/api/agents/collaboration/workspaces")
    public List<cc.ginpika.minis.coding.api.CodingDtos.Workspace> workspaces(HttpServletRequest http) {
        workspaceAccess(http);
        return asUser(http,collaboration::workspaces);
    }
    @PutMapping("/api/projects/{id}/collaboration-workspace")
    public cc.ginpika.minis.agent.roundtable.CollaborationWorkspaceBinding.Location workspace(@PathVariable String id,
            @RequestBody cc.ginpika.minis.coding.api.CodingDtos.OpenProject request,HttpServletRequest http) {
        workspaceAccess(http);
        return asUser(http,() -> collaboration.bindWorkspace(id,request));
    }

    @PostMapping("/api/agents/sessions/{id}/rewind")
    public cc.ginpika.minis.agent.chat.dto.ChatDtos.Rewind rewind(@PathVariable String id, @RequestBody Rewind req,HttpServletRequest http) {
        return asUser(http,() -> rewinds.rewind(id, req.messageId()));
    }

    @GetMapping("/api/agents/collaboration")
    public CollaborationService.Workspace workspace(HttpServletRequest http) { return asUser(http,collaboration::workspace); }
    @PutMapping("/api/projects/{id}/collaboration-members")
    public List<String> members(@PathVariable String id, @RequestBody Members req,HttpServletRequest http) {
        return asUser(http,() -> collaboration.members(id, req.memberIds()));
    }
    @PostMapping("/api/agents/collaboration/tasks")
    public AgentDtos.NewSession task(@RequestBody NewTask req,HttpServletRequest http) {
        return asUser(http,() -> collaboration.task(req.projectId(), req.title(), req.memberIds(), req.coordinatorId()));
    }
    @PostMapping("/api/agents/sessions/{id}/issues")
    public CollaborationStore.Issue issue(@PathVariable String id, @RequestBody NewIssue req,HttpServletRequest http) {
        return asUser(http,() -> collaboration.createIssue(id, req.title(), req.memberIds()));
    }
    @PutMapping("/api/agents/issues/{id}/conclusion")
    public CollaborationStore.Issue resolve(@PathVariable String id, @RequestBody Conclusion req,HttpServletRequest http) {
        return asUser(http,() -> collaboration.resolve(id, req.conclusion()));
    }

    private static SsoUser user(HttpServletRequest request) {
        Object actor=request.getAttribute(SsoInterceptor.ATTR);
        if(!(actor instanceof SsoUser user) || user.getId()==null || user.getId().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
        return user;
    }

    private static void workspaceAccess(HttpServletRequest request) {
        if(!cc.ginpika.minis.coding.CodingFacade.allowsActorAt(user(request).getId(),request.getRemoteAddr()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"开发身份仅允许本机访问工作目录；远程部署请配置 SSO");
    }

    private static <T> T asUser(HttpServletRequest http,Supplier<T> action) {
        try(var actor=UserScope.bind(user(http).getId())) { return action.get(); }
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));
    }
}
