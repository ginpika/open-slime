package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.web.*;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/web")
public class WebSnapshotController {
    private final WebBrowserService browser;
    private final WebSnapshotService snapshots;
    private final WebSnapshotStore store;
    private final ObjectMapper om;
    public WebSnapshotController(WebBrowserService browser,WebSnapshotService snapshots,WebSnapshotStore store,ObjectMapper om){this.browser=browser;this.snapshots=snapshots;this.store=store;this.om=om;}
    private String owner(HttpServletRequest request){
        SsoUser user=(SsoUser)request.getAttribute(SsoInterceptor.ATTR);
        if(user==null)throw new IllegalStateException("缺少用户身份");
        return "human:"+user.getId();
    }
    @PostMapping("/browser") public JsonNode command(HttpServletRequest request,@RequestBody WebDtos.Command command){
        // A closed browser is JSON null, rather than an empty response that breaks response.json().
        return om.valueToTree(browser.command(owner(request),null,command,true));
    }
    @GetMapping("/browser") public List<WebDtos.BrowserView> views(HttpServletRequest request,@RequestParam(required=false) String chatSessionId){
        // Chat is an existing shared workspace in Minis; this endpoint is observation-only.
        return browser.views(owner(request),chatSessionId);
    }
    @PostMapping("/browser/{id}/capture") public WebDtos.Capture capture(HttpServletRequest request,@PathVariable String id,@RequestBody(required=false) WebDtos.MediaOptions media){return browser.capture(owner(request),id,media);}
    @PostMapping("/distill") public WebDtos.SaveRequest distill(HttpServletRequest request,@RequestBody WebDtos.DistillRequest input){return snapshots.distill(owner(request),input);}
    @PostMapping("/snapshots") public WebDtos.Snapshot save(HttpServletRequest request,@RequestBody WebDtos.SaveRequest input){return snapshots.save(owner(request),input);}
    @GetMapping("/snapshots") public List<WebDtos.Snapshot> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return store.list(Math.max(1,page),Math.max(1,Math.min(50,size)));}
    @GetMapping("/snapshots/{id}") public WebDtos.Snapshot detail(@PathVariable String id){return store.get(id);}
    @PatchMapping("/snapshots/{id}/title") public WebDtos.Renamed rename(@PathVariable String id,@RequestBody WebDtos.Rename input){return store.rename(id,input.title());}
    @GetMapping("/snapshots/{id}/images") public SnapshotImages.Catalog images(@PathVariable String id){return SnapshotImages.extract(store.get(id)).catalog();}
    @GetMapping(value="/snapshots/{id}/images.zip",produces="application/zip") public ResponseEntity<StreamingResponseBody> imageZip(@PathVariable String id){
        var snapshot=store.get(id);
        var archive=SnapshotImages.extract(snapshot);
        if(archive.catalog().imageCount()==0)throw new IllegalArgumentException("该快照没有可导出的离线图片");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition","attachment; filename=\"snapshot-"+snapshot.id()+"-images.zip\"")
                .header("X-Content-Type-Options","nosniff")
                .body(output->archive.writeZip(output,om));
    }
    @PatchMapping("/snapshots/{id}") public WebDtos.Snapshot toggle(@PathVariable String id,@RequestBody WebDtos.Toggle input){
        var snapshot=store.toggle(id,input.enabled());if(input.enabled())snapshots.index(id);return snapshot;
    }
    @DeleteMapping("/snapshots/{id}") public java.util.Map<String,Boolean> delete(@PathVariable String id){store.delete(id);return java.util.Map.of("deleted",true);}
    @PostMapping("/snapshots/{id}/index") public WebDtos.Snapshot index(@PathVariable String id){snapshots.index(id);return store.get(id);}
    @GetMapping(value="/snapshots/{id}/html",produces="text/html;charset=UTF-8") public ResponseEntity<byte[]> html(@PathVariable String id){
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"snapshot-"+store.get(id).id()+".html\"")
                .header("Content-Security-Policy","default-src 'none'; img-src data:; media-src data:; style-src 'unsafe-inline'; sandbox")
                .body(SnapshotHtml.render(store.get(id)).getBytes(StandardCharsets.UTF_8));
    }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Error> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(new Error(e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Error> unavailable(IllegalStateException e){return ResponseEntity.status(502).body(new Error(e.getMessage()));}
    public record Error(String error){}
}
