package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.ChatTodoApprovals;
import cc.ginpika.minis.agent.chat.ChatTodoApprovals.Approval;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sessions/{id}/todo-approvals")
public class ChatTodoApprovalController {
    private final ChatTodoApprovals approvals;

    public ChatTodoApprovalController(ChatTodoApprovals approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public ResponseEntity<List<Approval>> list(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(approvals.list(id, actor(request)));
    }

    public record Decision(String decision) {}

    @PostMapping("/{operationId}")
    public ResponseEntity<Approval> decide(@PathVariable String id, @PathVariable long operationId,
                                           @RequestBody Decision decision, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(approvals.decide(id, operationId, actor(request), decision.decision()));
    }

    private static String actor(HttpServletRequest request) {
        if (!(request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user)
                || user.getId() == null || user.getId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录");
        }
        return user.getId();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException error) {
        String message = error.getReason() == null ? "待办审批操作未完成" : error.getReason();
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", message));
    }
}
