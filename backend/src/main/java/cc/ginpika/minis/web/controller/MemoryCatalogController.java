package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Human-facing catalog. Page and lifecycle counts are computed before limiting the result set. */
@RestController
@RequestMapping("/api/memory/catalog")
public class MemoryCatalogController {
    private final JdbcTemplate jdbc;
    private final MemoryItemStore items;
    private final MemoryProps props;
    public MemoryCatalogController(JdbcTemplate jdbc, MemoryItemStore items, MemoryProps props) {
        this.jdbc=jdbc; this.items=items; this.props=props;
    }
    public record Entry(MemoryController.ItemView item, Long stopsAt, boolean archiveNext, String reason) {}
    public record Catalog(long checkedAt, long dueBefore, Map<String,Long> counts, long total, int page, int pages,
                          List<Entry> entries, List<Entry> attention, long pendingProfiles, long extractingMaterials) {}

    @GetMapping
    public Catalog catalog(@RequestParam(defaultValue="remembered") String view,
            @RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String agentId,
            @RequestParam(defaultValue="") String projectId, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        if(!Set.of("remembered","recent","attention","history").contains(view)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知记忆视图");
        long now=System.currentTimeMillis(), week=7*86_400_000L, horizon=now+week;
        // This is the exact protection and per-domain grouping used by MemoryConsolidator.trimEpisodes.
        String protectedSql="COALESCE(pinned,0)=1 OR COALESCE(retention,'') IN ('pinned','long') OR COALESCE(owner,'')='USER' OR scope IN ('open_issue','directive') OR COALESCE(layer,'') IN ('L1','L4')";
        String cte="WITH ranked AS (SELECT id,row_number() OVER(PARTITION BY COALESCE(owner,''),COALESCE(project_id,''),COALESCE(agent_id,''),COALESCE(domain,''),COALESCE(layer,''),"
                +"CASE WHEN layer='L3' THEN COALESCE(session_id,'') ELSE '' END,CASE WHEN layer='L3' THEN COALESCE(CAST(task_id AS TEXT),'null') ELSE '' END ORDER BY id DESC) AS position "
                +"FROM graph_claim WHERE scope='episode' AND status='active' AND NOT ("+protectedSql+")), timed AS (SELECT g.*,"
                +"min(COALESCE(valid_until,9223372036854775807),COALESCE(expires_soft,9223372036854775807),"
                +"CASE WHEN json_extract(temporal_json,'$.kind')='state' THEN COALESCE(invalid_at,9223372036854775807) ELSE 9223372036854775807 END) AS stop_at,"
                +"CASE WHEN COALESCE(r.position,0)>"+Math.max(1,props.getEpisodeMax())+" THEN 1 ELSE 0 END AS archive_next "
                +"FROM graph_claim g LEFT JOIN ranked r ON r.id=g.id), catalog AS (SELECT *,"
                +"CASE WHEN status='active' AND superseded_by IS NULL AND stop_at>"+now
                +" AND (COALESCE(json_extract(temporal_json,'$.kind'),'')<>'state' OR COALESCE(json_extract(temporal_json,'$.start'),0)<="+now+") THEN 1 ELSE 0 END AS available FROM timed), scoped AS (SELECT * FROM catalog WHERE COALESCE(source_kind,'')<>'system_anchor'";
        var args=new ArrayList<Object>();
        if(!q.isBlank()) { cte+=" AND instr(lower(COALESCE(title,'')||' '||body),lower(?))>0"; args.add(q.strip()); }
        if(!agentId.isBlank()) { cte+=" AND agent_id=?"; args.add(agentId); }
        if(!projectId.isBlank()) { cte+=" AND project_id=?"; args.add(projectId); }
        cte+=") ";
        String soon="available=1 AND (stop_at<="+horizon+" OR archive_next=1)";
        String recent="available=1 AND created_at>="+(now-week);
        var counts=jdbc.queryForMap(cte+"SELECT COALESCE(sum(available),0) AS remembered,COALESCE(sum(CASE WHEN "+recent+" THEN 1 ELSE 0 END),0) AS recent,"
                +"COALESCE(sum(CASE WHEN "+soon+" THEN 1 ELSE 0 END),0) AS attention,COALESCE(sum(CASE WHEN available=0 THEN 1 ELSE 0 END),0) AS history FROM scoped",args.toArray());
        var totals=new LinkedHashMap<String,Long>(); counts.forEach((k,v)->totals.put(k,((Number)v).longValue()));
        long total=totals.get(view); int cap=Math.max(1,Math.min(50,size));
        int pages=(int)Math.max(1,(total+cap-1)/cap), current=Math.min(Math.max(1,page),pages);
        String condition=switch(view) { case "recent" -> recent; case "attention" -> soon; case "history" -> "available=0"; default -> "available=1"; };
        String order=view.equals("attention")?"stop_at,id":"created_at DESC,id DESC";
        var pageArgs=new ArrayList<Object>(args);pageArgs.add(cap);pageArgs.add((current-1)*cap);
        var rows=jdbc.queryForList(cte+"SELECT id,stop_at,archive_next FROM scoped WHERE "+condition+" ORDER BY "+order+" LIMIT ? OFFSET ?",pageArgs.toArray());
        var attention=jdbc.queryForList(cte+"SELECT id,stop_at,archive_next FROM scoped WHERE "+soon+" ORDER BY stop_at,id LIMIT 3",args.toArray());
        long candidates=jdbc.queryForObject("SELECT count(*) FROM memory_candidate WHERE status='pending'",Long.class);
        long extracting=jdbc.queryForObject("SELECT count(*) FROM graph_ingest_job WHERE state IN ('pending','running')",Long.class);
        return new Catalog(now,horizon,totals,total,current,pages,entries(rows),entries(attention),candidates,extracting);
    }
    private List<Entry> entries(List<Map<String,Object>> rows) {
        var byId=new HashMap<Long,MemoryItemStore.ItemRow>();
        items.byIds(rows.stream().map(r->((Number)r.get("id")).longValue()).toList()).forEach(i->byId.put(i.id(),i));
        var out=new ArrayList<Entry>();
        for(var row:rows) {
            var item=byId.get(((Number)row.get("id")).longValue()); if(item==null) continue;
            long stop=((Number)row.get("stop_at")).longValue();boolean archive=((Number)row.get("archive_next")).intValue()==1;
            String reason=archive?"情景数量超过保留上限，下次整理将归档；原记录保留。":"";
            if(stop!=Long.MAX_VALUE) {
                String why=Objects.equals(item.expiresSoft(),stop)?"软保留期到期后停止自动使用，原记录保留。"
                        :Objects.equals(item.validUntil(),stop)?"已设有效期，到期后停止自动使用。":"状态有效期结束后停止作为当前状态使用。";
                reason=reason.isBlank()?why:reason+" "+why;
            }
            out.add(new Entry(MemoryController.ItemView.of(item),stop==Long.MAX_VALUE?null:stop,archive,reason));
        }
        return out;
    }
}
