package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.roundtable.CollaborationQuality;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Read task evidence through its session; this endpoint never starts or changes work. */
@RestController
public class CollaborationQualityController {
    private final CollaborationQuality quality;
    public CollaborationQualityController(CollaborationQuality quality){this.quality=quality;}
    @GetMapping("/api/agents/collaboration/tasks/{session}/todos/{todo}/quality")
    public CollaborationQuality.Quality quality(@PathVariable String session,@PathVariable String todo,HttpServletRequest request){
        if(!(request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user) || user.getId()==null || user.getId().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要登录");
        return quality.find(session,todo);
    }
}
