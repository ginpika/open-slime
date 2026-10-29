package cc.ginpika.minis.web.controller;

import static cc.ginpika.minis.todo.TodoDtos.*;

import cc.ginpika.minis.todo.TodoService;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class PersonalTodoController {
    private final TodoService todos;
    public PersonalTodoController(TodoService todos) { this.todos=todos; }

    @GetMapping("/todos/overview")
    public Overview overview(HttpServletRequest request,@RequestParam(defaultValue="UTC") String zone,
                             @RequestParam(required=false) String projectId) {
        return todos.overview(owner(request),zone,projectId);
    }
    @GetMapping("/todos/review")
    public Review review(HttpServletRequest request,@RequestParam String month,@RequestParam(defaultValue="UTC") String zone,
                         @RequestParam(required=false) String projectId) {
        return todos.review(owner(request),month,zone,projectId);
    }
    @GetMapping("/todos/{id}")
    public Detail detail(HttpServletRequest request,@PathVariable String id) { return todos.detail(owner(request),id); }
    @GetMapping("/todos/projects")
    public java.util.List<ProjectSummary> projects(HttpServletRequest request) { return todos.projectSummaries(owner(request)); }
    @GetMapping("/todos/day")
    public DayWork day(HttpServletRequest request,@RequestParam(required=false) String day,
                       @RequestParam(defaultValue="UTC") String zone,@RequestParam(required=false) String projectId) {
        return todos.day(owner(request),day,zone,projectId);
    }
    @PostMapping("/todos")
    public Item create(HttpServletRequest request,@RequestBody ItemRequest body) { return todos.create(owner(request),body); }
    @PutMapping("/todos/{id}")
    public Item replace(HttpServletRequest request,@PathVariable String id,@RequestBody ItemRequest body) { return todos.replace(owner(request),id,body); }
    @PostMapping("/todos/{id}/state")
    public Item transition(HttpServletRequest request,@PathVariable String id,@RequestBody TransitionRequest body) { return todos.transition(owner(request),id,body); }
    @PostMapping("/todos/{id}/start")
    public Focus start(HttpServletRequest request,@PathVariable String id,@RequestBody StartRequest body) { return todos.start(owner(request),id,body); }
    @PostMapping("/focus-sessions/{id}/stop")
    public Focus stop(HttpServletRequest request,@PathVariable String id) { return todos.stop(owner(request),id); }
    @PostMapping("/focus-sessions")
    public Focus addFocus(HttpServletRequest request,@RequestBody FocusRequest body) { return todos.addFocus(owner(request),body); }
    @PutMapping("/focus-sessions/{id}")
    public Focus editFocus(HttpServletRequest request,@PathVariable String id,@RequestBody FocusRequest body) { return todos.editFocus(owner(request),id,body); }
    @DeleteMapping("/focus-sessions/{id}")
    public Deleted deleteFocus(HttpServletRequest request,@PathVariable String id,@RequestParam long version) { return todos.deleteFocus(owner(request),id,version); }
    @PutMapping("/day-notes/{day}")
    public DayNote saveNote(HttpServletRequest request,@PathVariable String day,@RequestParam(defaultValue="UTC") String zone,
                            @RequestBody NoteRequest body) { return todos.saveNote(owner(request),day,zone,body); }

    /** New private data is always scoped to the authenticated person, never supplied by a client. */
    private static String owner(HttpServletRequest request) {
        if(request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user && user.getId()!=null&&!user.getId().isBlank()) return user.getId();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,String>> failure(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"操作未完成":error.getReason()));
    }
}
