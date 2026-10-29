package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.anchor.GraphAnchors;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.config.FastProps;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 图谱预览：以某实体为中心 1~N 跳；实体 / 关系新增 + KV 附加属性读写 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {

    private final GraphStore store;
    private final FastProps props;

    public GraphController(GraphStore store, FastProps props) {
        this.store = store.administrativeView();
        this.props = props;
    }

    /** 实体搜索：q 空则返回关系最多的实体作为入口建议 */
    @GetMapping("/entities")
    public List<SearchDtos.Node> entities(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit) {
        int cap = limit == null ? 8 : Math.min(Math.max(limit, 1), 24);
        List<GraphStore.Entity> found = (q == null || q.isBlank())
                ? store.hubs(cap)
                : store.lookup(List.of(q.trim().split("\\s+")), cap);
        List<SearchDtos.Node> nodes = new ArrayList<>();
        for (GraphStore.Entity e : found) {
            String type = e.type() == null || e.type().isBlank() ? "CONCEPT" : e.type();
            nodes.add(new SearchDtos.Node(e.id(), e.name(), type, e.props(),
                    e.createdAt(), e.updatedAt()));
        }
        return nodes;
    }

    /** 全部锚点：系统预置的特殊实体（entities.types 含 ANCHOR 标记）及锚点之间的关系子图 */
    @GetMapping("/anchors")
    public SearchDtos.Graph anchors() {
        List<GraphStore.Entity> found = store.entitiesByType(GraphAnchors.TYPE, 64);
        Set<String> ids = new LinkedHashSet<>();
        List<SearchDtos.Node> nodes = new ArrayList<>();
        for (GraphStore.Entity e : found) {
            ids.add(e.id());
            String type = e.type() == null || e.type().isBlank() ? "CONCEPT" : e.type();
            nodes.add(new SearchDtos.Node(e.id(), e.name(), type, e.props(),
                    e.createdAt(), e.updatedAt()));
        }
        List<SearchDtos.Edge> edges = new ArrayList<>();
        for (GraphStore.Edge e : store.edgesTouching(ids)) {
            if (ids.contains(e.subjectId()) && ids.contains(e.objectId())) {
                edges.add(new SearchDtos.Edge(e.id(), e.subjectId(), e.objectId(), e.predicate(),
                        e.props()));
            }
        }
        return new SearchDtos.Graph(nodes, edges);
    }

    @GetMapping("/neighbors")
    public SearchDtos.Graph neighbors(
            @RequestParam String entityId,
            @RequestParam(required = false) Integer depth) {
        int hops = depth == null ? props.getGraphHops() : Math.min(depth, props.getGraphHops());
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        visited.add(entityId);
        Set<String> frontier = new HashSet<>(List.of(entityId));
        for (int h = 1; h <= hops && !frontier.isEmpty(); h++) {
            Set<String> next = store.neighborsOf(frontier);
            next.removeAll(visited);
            for (String id : next) {
                if (visited.size() >= 64) {
                    break;
                }
                visited.add(id);
            }
            if (next.isEmpty() || visited.size() >= 64) {
                break;
            }
            frontier = next;
        }
        List<GraphStore.Edge> allEdges = store.edgesTouching(visited);
        List<SearchDtos.Edge> edges = new ArrayList<>();
        for (GraphStore.Edge e : allEdges) {
            if (visited.contains(e.subjectId()) && visited.contains(e.objectId()) && edges.size() < 48) {
                edges.add(new SearchDtos.Edge(e.id(), e.subjectId(), e.objectId(), e.predicate(),
                        e.props()));
            }
        }
        List<GraphStore.Entity> entities = store.entitiesByIds(visited);
        List<SearchDtos.Node> nodes = new ArrayList<>();
        for (GraphStore.Entity e : entities) {
            nodes.add(new SearchDtos.Node(e.id(), e.name(), e.type(), e.props(),
                    e.createdAt(), e.updatedAt()));
        }
        return new SearchDtos.Graph(nodes, edges);
    }

    /** 新增实体：name 必填，type 缺省 CONCEPT；同名实体不重复创建（按 name upsert，类型并入
     *  types 全量清单，主类型首写定不覆盖） */
    @PostMapping("/entities")
    @org.springframework.transaction.annotation.Transactional
    public SearchDtos.Node createEntity(@RequestBody CreateEntityRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 为空");
        }
        String type = req.type() == null || req.type().isBlank() ? "CONCEPT" : req.type().trim();
        store.upsertEntity(type, name, List.of(), null);
        GraphStore.Entity e = store.byName(name);
        return new SearchDtos.Node(e.id(), e.name(), e.type(), e.props(),
                e.createdAt(), e.updatedAt());
    }

    /** 新增实体请求体 */
    public record CreateEntityRequest(String name, String type) {
    }

    /** 新增关系：subjectId 为起点实体，name 为目标实体（不存在则按 CONCEPT 自动创建）；返回落库后的关系 */
    @PostMapping("/relations")
    @org.springframework.transaction.annotation.Transactional
    public SearchDtos.Edge createRelation(@RequestBody CreateRelationRequest req) {
        String subjectId = req.subjectId() == null ? "" : req.subjectId().trim();
        String targetName = req.name() == null ? "" : req.name().trim();
        if (subjectId.isEmpty() || targetName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subjectId / name 为空");
        }
        if (store.entitiesByIds(List.of(subjectId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "entity not found");
        }
        GraphStore.Entity object = store.byName(targetName);
        if (object == null) {
            store.upsertEntity("CONCEPT", targetName, List.of(), null);
            object = store.byName(targetName);
        }
        String predicate = req.predicate() == null || req.predicate().isBlank()
                ? "relates_to" : req.predicate().trim();
        store.upsertRelation(subjectId, predicate, object.id(), null);
        return new SearchDtos.Edge(GraphStore.relationIdFor(subjectId, predicate, object.id()),
                subjectId, object.id(), predicate, List.of());
    }

    /** 新增关系请求体 */
    public record CreateRelationRequest(String subjectId, String name, String predicate) {
    }

    /** 覆盖式更新实体 KV 属性（entities.meta），返回规范化后的列表 */
    @PutMapping("/entities/{id}/props")
    @org.springframework.transaction.annotation.Transactional
    public List<GraphStore.Prop> putEntityProps(
            @PathVariable String id,
            @RequestBody List<GraphStore.Prop> props) {
        List<GraphStore.Prop> clean = sanitizeProps(props);
        if (store.updateEntityProps(id, clean) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "entity not found");
        }
        return clean;
    }

    /** 覆盖式更新关系 KV 属性（relations.props），返回规范化后的列表 */
    @PutMapping("/relations/{id}/props")
    @org.springframework.transaction.annotation.Transactional
    public List<GraphStore.Prop> putRelationProps(
            @PathVariable String id,
            @RequestBody List<GraphStore.Prop> props) {
        List<GraphStore.Prop> clean = sanitizeProps(props);
        if (store.updateRelationProps(id, clean) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "relation not found");
        }
        return clean;
    }

    private static final Set<String> PROP_TYPES = Set.of("text", "number", "url", "date", "bool");
    private static final int MAX_PROPS = 32;
    private static final int MAX_KEY = 64;
    private static final int MAX_VALUE = 1000;

    /** 校验并规范化 KV 属性：key 去空白且唯一、类型白名单、长度限制 */
    private static List<GraphStore.Prop> sanitizeProps(List<GraphStore.Prop> props) {
        if (props == null) {
            return List.of();
        }
        if (props.size() > MAX_PROPS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "too many props (max " + MAX_PROPS + ")");
        }
        List<GraphStore.Prop> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (GraphStore.Prop p : props) {
            if (p == null || p.k() == null || p.k().isBlank()) {
                continue;
            }
            String k = p.k().trim();
            if (k.length() > MAX_KEY) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "prop key too long (max " + MAX_KEY + "): " + k);
            }
            if (!keys.add(k)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "duplicate prop key: " + k);
            }
            String v = p.v() == null ? "" : p.v();
            if (v.length() > MAX_VALUE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "prop value too long (max " + MAX_VALUE + "): " + k);
            }
            String t = p.t() == null || p.t().isBlank() ? "text" : p.t().trim().toLowerCase();
            if (!PROP_TYPES.contains(t)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown prop type: " + t);
            }
            out.add(new GraphStore.Prop(k, v, t));
        }
        return out;
    }
}
