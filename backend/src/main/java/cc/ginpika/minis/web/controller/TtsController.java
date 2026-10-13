package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.config.TtsService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
public class TtsController {
    private final TtsService tts;
    public TtsController(TtsService tts) { this.tts=tts; }
    public record Selection(String profileId) {}
    public record Speech(String agentId,String text) {}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null ? "TTS 请求失败" : e.getReason()));
    }
    @GetMapping("/api/settings/tts") public TtsService.View view() { return tts.view(); }
    @PostMapping("/api/settings/tts/endpoints") public TtsService.View createEndpoint(@RequestBody TtsService.Endpoint e) { return tts.saveEndpoint(null,e); }
    @PostMapping("/api/settings/tts/endpoints/{id}/test") public Object testEndpoint(@PathVariable String id) { return tts.testEndpoint(id); }
    @PostMapping("/api/settings/tts/endpoints/test") public Object testEndpoint(@RequestBody TtsService.Endpoint e) { return tts.testEndpoint(e); }
    @PutMapping("/api/settings/tts/endpoints/{id}") public TtsService.View updateEndpoint(@PathVariable String id,@RequestBody TtsService.Endpoint e) { return tts.saveEndpoint(id,e); }
    @DeleteMapping("/api/settings/tts/endpoints/{id}") public TtsService.View deleteEndpoint(@PathVariable String id) { return tts.deleteEndpoint(id); }
    @GetMapping("/api/settings/tts/parameters") public Object defaults() { return tts.defaults(); }
    @PostMapping("/api/settings/tts") public TtsService.View create(@RequestBody TtsService.Profile p) { return tts.save(null,p); }
    @PutMapping("/api/settings/tts/{id}") public TtsService.View update(@PathVariable String id,@RequestBody TtsService.Profile p) { return tts.save(id,p); }
    @DeleteMapping("/api/settings/tts/{id}") public TtsService.View delete(@PathVariable String id) { return tts.delete(id); }
    @PutMapping("/api/settings/tts/default") public TtsService.View defaults(@RequestBody Selection s) { tts.select("default",s.profileId()); return tts.view(); }
    @GetMapping("/api/agents/{id}/tts") public Map<String,String> agent(@PathVariable String id) { String selected=tts.selection(id); return Map.of("profileId",selected==null ? "" : selected); }
    @PutMapping("/api/agents/{id}/tts") public Selection agent(@PathVariable String id,@RequestBody Selection s) { tts.select("agent:"+id,s.profileId()); return s; }
    @PostMapping("/api/tts/speech") public ResponseEntity<byte[]> speak(@RequestBody Speech s) {
        TtsService.Profile p=tts.resolve(s.agentId());
        String mime=switch(p.parameters().path("format").asText()) { case "wav" -> "audio/wav"; case "opus" -> "audio/ogg"; default -> "audio/mpeg"; };
        return ResponseEntity.ok().header("Content-Type",mime).header("Cache-Control","no-store")
            .header("X-TTS-Profile-Id",p.id()).header("X-TTS-Model",p.model())
            .header("X-TTS-Reference-Id",p.referenceId()).body(tts.speak(p,s.text()));
    }
}
