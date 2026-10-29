package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.economy.EconomyService;
import cc.ginpika.minis.economy.EconomyStore;
import static cc.ginpika.minis.economy.EconomyModels.*;
import cc.ginpika.minis.ai.knowledge.web.WebBrowserService;
import cc.ginpika.minis.ai.knowledge.web.WebDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/economy")
public class EconomyController {
    private final EconomyService service;
    private final WebBrowserService browser;
    private final ObjectMapper om;
    private final EconomyStore store;
    public EconomyController(EconomyService service,EconomyStore store,WebBrowserService browser,ObjectMapper om){this.service=service;this.store=store;this.browser=browser;this.om=om;}
    private String owner(HttpServletRequest request) {
        var user=(SsoUser)request.getAttribute(SsoInterceptor.ATTR);
        if(user==null)throw new IllegalStateException("缺少用户身份");
        return "human:"+user.getId();
    }
    @PostMapping("/studies") public Study create(HttpServletRequest request,@RequestBody NewStudy input){return store.create(owner(request),input);}
    @GetMapping("/studies") public StudyPage history(HttpServletRequest request,
            @RequestParam(required=false) String kind,@RequestParam(required=false) String symbol,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to,@RequestParam(defaultValue="1") int page){
        return store.history(owner(request),kind,symbol,from,to,page);
    }
    @GetMapping("/studies/{id}") public StudyDetail detail(HttpServletRequest request,@PathVariable String id){return store.detail(owner(request),id);}
    @PostMapping("/studies/{id}/observations") public Observation step(HttpServletRequest request,@PathVariable String id,@RequestBody CaptureInput input){return service.analyzeStep(owner(request),id,input);}
    @PostMapping("/observations/{id}/retry") public Observation retry(HttpServletRequest request,@PathVariable String id){return service.retryStep(owner(request),id);}
    @GetMapping("/observations/{id}/image") public ResponseEntity<Map<String,String>> image(HttpServletRequest request,@PathVariable String id){
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(Map.of("image",store.image(owner(request),id).dataUrl()));
    }
    @PostMapping("/studies/{id}/submit") public Report submit(HttpServletRequest request,@PathVariable String id,@RequestBody SubmitInput input){return service.submit(owner(request),id,input);}
    @PostMapping("/browser") public JsonNode browser(HttpServletRequest request,@RequestBody WebDtos.Command input){return om.valueToTree(browser.interactiveCommand(owner(request),input));}
    @GetMapping("/reports") public List<JsonNode> legacy(HttpServletRequest request){return store.legacy(owner(request));}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> failed(IllegalStateException e){return ResponseEntity.status(502).body(Map.of("error",e.getMessage()));}
}
