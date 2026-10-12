package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.core.*;
import cc.ginpika.minis.ai.memory.MemoryEditService;
import cc.ginpika.minis.ai.memory.MemoryPolicy;
import cc.ginpika.minis.ai.memory.MemoryLiveContext;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.memory.store.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

/** 用户管理面的只读观察；Agent 视角使用实际 SQL 权限，不构建快照、不调用模型。 */
@RestController
@RequestMapping("/api/memory/observation")
public class MemoryObservationController {
    private final MemoryItemStore items;
    private final MemoryShareStore shares;
    private final MemorySnapshotStore snapshots;
    private final MemoryWorkStore works;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final WorkingSetService workingSet;
    public MemoryObservationController(MemoryItemStore items, MemoryShareStore shares,
            MemorySnapshotStore snapshots, MemoryWorkStore works, JdbcTemplate jdbc, ObjectMapper json, WorkingSetService workingSet) {
        this.items=items; this.shares=shares; this.snapshots=snapshots; this.works=works; this.jdbc=jdbc; this.json=json;
        this.workingSet=workingSet;
    }
    public record Access(long itemId, List<String> reasons, boolean editableByAgent) { }
    public record Visibility(MemoryExec context, long checkedAt, List<Access> entries, List<MemoryController.WorkingView> work) { }

    @GetMapping("/visibility")
    public Visibility visibility(@RequestParam String agentId, @RequestParam(required=false) String projectId,
            @RequestParam(required=false) String sessionId, @RequestParam(required=false) Long taskId) {
        if (agentId.isBlank()) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择 Agent"); }
        MemoryExec exec = new MemoryExec(null,agentId,blank(projectId),blank(sessionId),taskId,
                MemorySurface.SYSTEM,false,null,MemoryIdentity.INFERRED);
        Map<Long,List<MemoryShareStore.ShareRow>> grants = new HashMap<>();
        for (var share : shares.listPublished()) { grants.computeIfAbsent(share.itemId(),id->new ArrayList<>()).add(share); }
        List<Access> access = new ArrayList<>();
        for (var it : items.visible(exec,null,Integer.MAX_VALUE)) {
            List<String> reasons = new ArrayList<>();
            if (MemoryPolicy.canRead(exec,it.owner(),it.domain(),it.projectId(),it.agentId(),it.sessionId(),it.taskId(),it.layer())) {
                if ("USER".equals(it.owner())) { reasons.add("正式用户画像或规则，所有 Agent 可读"); }
                else if ("L1".equals(it.layer())) { reasons.add("当前项目的已确认共享记忆"); }
                else if ("L2".equals(it.layer())) { reasons.add(it.projectId()==null ? "归属当前 Agent 的私有记忆" : "当前项目内归属该 Agent 的私有记忆"); }
                else { reasons.add("命中当前会话或任务绑定" + ("AGENT".equals(it.owner()) ? "，且归属当前 Agent" : "")); }
            }
            for (var s : grants.getOrDefault(it.id(),List.of())) {
                boolean matches = switch(s.toKind()) {
                    case "user" -> true;
                    case "agent" -> agentId.equals(s.toRef());
                    case "project" -> exec.projectId()!=null && exec.projectId().equals(s.toRef());
                    case "session" -> exec.sessionId()!=null && exec.sessionId().equals(s.toRef());
                    default -> false;
                };
                if (matches) { reasons.add("显式发布授权：" + s.toKind() + (s.toRef()==null ? "" : ":" + s.toRef())); }
            }
            access.add(new Access(it.id(),List.copyOf(reasons),MemoryEditService.canEdit(exec,it)));
        }
        return new Visibility(exec,System.currentTimeMillis(),access,workingSet.open(exec,200).stream().map(MemoryController.WorkingView::of).toList());
    }

    public record SnapshotSummary(String turnId, String sessionId, String agentId, String projectId,
            long watermark, int itemCount, List<Long> itemIds, Integer workCount, int budgetTokens, long builtAt, boolean complete) { }
    @GetMapping("/snapshots")
    public List<SnapshotSummary> recent(@RequestParam(required=false) String agentId,
            @RequestParam(required=false) String projectId, @RequestParam(required=false) String sessionId,
            @RequestParam(defaultValue="100") int limit) {
        return snapshots.recent(agentId,projectId,sessionId,limit).stream().map(row -> {
            JsonNode content = parse(row.contentJson());
            boolean complete = complete(content);
            return new SnapshotSummary(row.turnId(),row.sessionId(),row.agentId(),row.projectId(),row.watermark(),
                    row.itemIds().size(), row.itemIds(), complete ? content.path("work").size() : null,row.tokens(),row.builtAt(),complete);
        }).toList();
    }
    public record RequestMemory(int round, boolean requestRecorded, boolean memorySectionPresent,
            String memoryText, boolean responseRecorded) { }
    public record SnapshotDetail(SnapshotSummary summary, JsonNode content, List<Long> itemIds,
            List<RequestMemory> requests, String requestNote) { }
    @GetMapping("/snapshots/{turnId}")
    public SnapshotDetail detail(@PathVariable String turnId) {
        var row = snapshots.find(turnId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"快照不存在"));
        JsonNode content = parse(row.contentJson());
        boolean complete = complete(content);
        var summary = new SnapshotSummary(row.turnId(),row.sessionId(),row.agentId(),row.projectId(),row.watermark(),
                row.itemIds().size(),row.itemIds(),complete ? content.path("work").size() : null,row.tokens(),row.builtAt(),complete);
        List<RequestMemory> requests = new ArrayList<>();
        String note = "该执行没有可关联的逐次模型请求留痕；启动快照不能证明最终发送内容。";
        if (turnId.matches("[0-9]+") && row.sessionId()!=null && !row.sessionId().isBlank()) {
            List<String> records = jdbc.query("SELECT m.context FROM chat_turn t JOIN chat_message m ON m.id=t.reply_message_id "
                    + "WHERE t.id=? AND t.session_id=?", (rs,n)->rs.getString(1), turnId,row.sessionId());
            JsonNode calls = records.isEmpty() ? null : parse(records.get(0));
            if (calls!=null && calls.isArray()) {
                for (JsonNode call : calls) {
                    JsonNode request = parse(call.path("request").asText(null));
                    if (request!=null && (!request.isObject() || !request.path("messages").isArray())) { request=null; }
                    String memory = memorySection(request);
                    requests.add(new RequestMemory(call.path("round").asInt(),request!=null,
                            memory!=null,memory,call.hasNonNull("response")));
                }
                if (!requests.isEmpty()) { note="以下来自已保存的实际请求。响应是否留存不等于回答正确使用了记忆。"; }
            }
        }
        return new SnapshotDetail(summary,complete ? content : null,row.itemIds(),requests,note);
    }
    @GetMapping("/working")
    public List<MemoryController.WorkingView> observedWork(@RequestParam(defaultValue="200") int limit) {
        return works.recentObserved(limit).stream().map(MemoryController.WorkingView::of).toList();
    }
    private String memorySection(JsonNode request) {
        if (request==null) { return null; }
        List<String> sections=new ArrayList<>();
        for (JsonNode message : request.path("messages")) {
            if (!"system".equals(message.path("role").asText())) { continue; }
            String text=message.path("content").asText("");
            int from=text.indexOf(MemoryLiveContext.START), to=text.indexOf(MemoryLiveContext.END);
            if (from>=0 && to>from) { sections.add(text.substring(from+MemoryLiveContext.START.length(),to).trim()); }
        }
        return sections.isEmpty() ? null : String.join("\n\n",sections);
    }
    private JsonNode parse(String value) {
        if (value==null || value.isBlank()) { return null; }
        try { return json.readTree(value); } catch(Exception ignored) { return null; }
    }
    private static boolean complete(JsonNode content) {
        return content!=null && content.path("items").isArray() && content.path("work").isArray();
    }
    private static String blank(String value) { return value==null || value.isBlank() ? null : value; }
}
