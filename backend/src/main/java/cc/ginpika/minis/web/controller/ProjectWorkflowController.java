package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.ProjectWorkflowService;
import cc.ginpika.minis.project.ProjectStore.ProjectRow;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/sessions")
public class ProjectWorkflowController {
    private final ProjectWorkflowService workflows;
    public ProjectWorkflowController(ProjectWorkflowService workflows) { this.workflows=workflows; }
    public record OrganizeRequest(String name, String kind, String note, String projectId) {}
    @PostMapping("/{id}/organize")
    public ProjectRow organize(@PathVariable String id, @RequestBody OrganizeRequest request) {
        if (request.projectId()!=null && !request.projectId().isBlank()) {
            return workflows.organizeExisting(id,request.projectId());
        }
        return workflows.organize(id,request.name(),request.kind(),request.note());
    }
    @PostMapping("/{id}/continue")
    public ProjectWorkflowService.Continuation resume(@PathVariable String id) {
        return workflows.continueInNewSession(id);
    }
    @DeleteMapping("/{id}/continuation")
    public java.util.Map<String,Boolean> revoke(@PathVariable String id) {
        workflows.revokeAll(id);
        return java.util.Map.of("revoked",true);
    }
}
