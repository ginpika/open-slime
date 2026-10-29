package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.memory.graph.MemoryGraphStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Administrative provenance inspection. Agent tools use their execution scope, never this human endpoint. */
@RestController
@RequestMapping("/api/memory/graph")
public class MemoryGraphController {
    private final MemoryGraphStore graph;
    private final JdbcTemplate jdbc;
    public MemoryGraphController(MemoryGraphStore graph,JdbcTemplate jdbc) { this.graph=graph;this.jdbc=jdbc; }
    @GetMapping("/claims/{id}/evidence")
    public MemoryGraphStore.EvidencePage evidence(@PathVariable long id,@RequestParam(defaultValue="0") int offset,
                                                @RequestParam(defaultValue="8") int limit) {
        return graph.evidence(MemoryExec.system(null),id,offset,limit);
    }
    public record Review(long expectedVersion,boolean accept,String reason) {}
    @PostMapping("/claims/{id}/review")
    public Map<String,Object> review(@PathVariable long id,@RequestBody Review review) {
        try {graph.reviewLegacy(id,review.expectedVersion(),review.accept(),review.reason());}
        catch(IllegalArgumentException e) {throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,e.getMessage(),e);}
        catch(IllegalStateException e) {throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,e.getMessage(),e);}
        return Map.of("id",id,"status",review.accept()?"active":"revoked");
    }
    @GetMapping("/status")
    public Map<String,Object> status() {
        return Map.of("claims",jdbc.queryForObject("SELECT count(*) FROM graph_claim",Long.class),
                "episodes",jdbc.queryForObject("SELECT count(*) FROM graph_episode",Long.class),
                "relations",jdbc.queryForObject("SELECT count(*) FROM graph_relation",Long.class),
                "extraction",jdbc.queryForList("SELECT state,count(*) AS count,max(attempts) AS maxAttempts FROM graph_ingest_job GROUP BY state"));
    }
}
