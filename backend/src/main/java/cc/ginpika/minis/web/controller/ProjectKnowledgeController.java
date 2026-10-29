package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.project.ProjectKnowledgeStore;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projects/{project}/knowledge")
public class ProjectKnowledgeController {
    private final ProjectKnowledgeStore knowledge;
    public ProjectKnowledgeController(ProjectKnowledgeStore knowledge) { this.knowledge=knowledge; }
    @GetMapping
    public ProjectKnowledgeStore.Page<ProjectKnowledgeStore.Entry> list(@PathVariable String project,
            @RequestParam(defaultValue="") String query,@RequestParam(defaultValue="false") boolean retired,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit,HttpServletRequest http) {
        user(http); return knowledge.list(project,query,retired,offset,limit);
    }
    @GetMapping("/progress")
    public ProjectKnowledgeStore.Progress progress(@PathVariable String project,@RequestParam(defaultValue="0") int offset,
            @RequestParam(defaultValue="20") int limit,HttpServletRequest http) {
        user(http);return knowledge.progress(project,offset,limit);
    }
    @GetMapping("/documents/{key}")
    public ProjectKnowledgeStore.Document read(@PathVariable String project,@PathVariable String key,
            @RequestParam(required=false) Integer version,HttpServletRequest http) {
        user(http);return knowledge.read(project,key,version);
    }
    @GetMapping("/documents/{key}/history")
    public List<ProjectKnowledgeStore.Document> history(@PathVariable String project,@PathVariable String key,HttpServletRequest http) {
        user(http);return knowledge.history(project,key);
    }
    @PutMapping("/documents/{key}")
    public ProjectKnowledgeStore.Document save(@PathVariable String project,@PathVariable String key,
            @RequestBody ProjectKnowledgeStore.Write request,HttpServletRequest http) {
        return knowledge.save(project,key,request,null,"user:"+user(http));
    }
    private static String user(HttpServletRequest request) {
        if(!(request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user) || user.getId()==null || user.getId().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
        return user.getId();
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason()==null?"请求失败":error.getReason()));
    }
}
