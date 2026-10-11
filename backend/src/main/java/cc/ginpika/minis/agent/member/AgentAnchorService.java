package cc.ginpika.minis.agent.member;

import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.ai.knowledge.anchor.GraphAnchors;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import cc.ginpika.minis.ai.knowledge.ingest.ResultTexts;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 成员的<b>图谱人格锚点</b>：把一位智能体钉到知识图谱里的一个锚点实体（types 含 ANCHOR）上。
 *
 * <p>与 {@link GraphAnchors#USER} 是同一套机制、同一张表：锚点就是一个普通实体，只是带上 ANCHOR
 * 标记，模型照样能用 upsert_edge 往它身上挂关系。区别只在用途——「用户」锚点承载用户画像，
 * 成员锚点承载这位智能体自己的第一人称坐标（它的人设、知识、立场都挂在这个圆心周围）。
 *
 * <p>新建时按成员名落一个锚点实体：同名实体已存在则只并入 ANCHOR 标记，不动它的主类型 / 别名 /
 * 摘要——与 {@code AnchorSeeder} 对预置锚点的处理同一条规则。
 *
 * <p>解除绑定只断开成员与锚点的关联（{@code agent.anchor_id}），锚点实体及其关系仍留在图中。
 */
@Service
public class AgentAnchorService {

    /** 一跳子图节点上限（含锚点自身）：够看清画像，又不至于把页面撑爆 */
    private static final int MAX_NODES = 48;

    /** 一跳子图边上限 */
    private static final int MAX_EDGES = 64;

    private final AgentStore store;
    private final GraphStore graph;
    private final ChunkStore chunks;

    public AgentAnchorService(AgentStore store, GraphStore graph, ChunkStore chunks) {
        this.store = store;
        this.graph = graph;
        this.chunks = chunks;
    }

    /** 这位成员的锚点视图：未绑定、或锚点实体已不在图中，都返回空视图（前端据此显示「新建」） */
    public AgentDtos.AnchorView view(String peerId) {
        AgentRow peer = require(peerId);
        String anchorId = peer.anchorId();
        if (anchorId == null || anchorId.isBlank()) {
            return empty(peerId);
        }
        List<GraphStore.Entity> found = graph.entitiesByIds(List.of(anchorId));
        if (found.isEmpty()) {
            return empty(peerId);
        }
        GraphStore.Entity anchor = found.get(0);
        return new AgentDtos.AnchorView(peerId, anchor.id(), toNode(anchor), oneHop(anchor.id()));
    }

    /**
     * 新建并绑定（已绑定且锚点实体健在时幂等返回当前视图）：以成员名在图中落一个锚点实体。
     *
     * <p>原绑定指针可能悬空——锚点实体已被删（如设置页「重置知识库」清空图谱）而
     * {@code agent.anchor_id} 仍留着旧 id。此时视作未绑定照样重建并重落指针，
     * 避免「界面点『新建』却毫无反应」的死角。
     */
    public AgentDtos.AnchorView create(String peerId) {
        AgentRow peer = require(peerId);
        AgentDtos.AnchorView current = view(peerId);
        if (current.anchor() != null) {
            return current;
        }
        String name = peer.name();
        if (graph.byName(name) == null) {
            graph.upsertEntity(GraphAnchors.TYPE, name, List.of(), semantics(name));
        } else {
            graph.mergeEntityTypes(name, List.of(GraphAnchors.TYPE));
        }
        GraphStore.Entity anchor = graph.byName(name);
        store.setAnchor(peerId, anchor.id());
        return new AgentDtos.AnchorView(peerId, anchor.id(), toNode(anchor), oneHop(anchor.id()));
    }

    /** 解除绑定：只断开关联，锚点实体与其关系保留在图中 */
    public AgentDtos.AnchorView unbind(String peerId) {
        require(peerId);
        store.setAnchor(peerId, null);
        return empty(peerId);
    }

    /**
     * 这位成员锚点的可读文本（成员自己的 {@code read_my_anchor} 工具用）：锚点实体卡片 + 其一跳关系
     * （含属性与来源），与 {@code query_entity_relations} 同一套渲染。未绑定、或锚点实体已不在图中
     * 时给一句提示语。
     */
    public String text(String peerId) {
        AgentRow peer = require(peerId);
        String anchorId = peer.anchorId();
        if (anchorId == null || anchorId.isBlank()) {
            return "（尚未为你建立图谱人格锚点）";
        }
        List<GraphStore.Entity> found = graph.entitiesByIds(List.of(anchorId));
        if (found.isEmpty()) {
            return "（尚未为你建立图谱人格锚点）";
        }
        GraphStore.Entity anchor = found.get(0);
        List<GraphStore.Edge> edges = graph.edgesTouching(List.of(anchor.id()));
        List<GraphStore.Entity> related = graph.entitiesByIds(graph.neighborsOf(List.of(anchor.id())));
        return ResultTexts.graphRelationsText(anchor, edges, related, chunkLabels(edgeChunkIds(edges)));
    }

    /** 边集合中的非空 source_chunk id（去重） */
    private static Set<Long> edgeChunkIds(Collection<GraphStore.Edge> edges) {
        Set<Long> ids = new LinkedHashSet<>();
        for (GraphStore.Edge e : edges) {
            if (e.sourceChunk() != null) {
                ids.add(e.sourceChunk());
            }
        }
        return ids;
    }

    /** chunkId → 可读溯源标签《标题》（ref: …），图谱关系渲染来源用 */
    private Map<Long, String> chunkLabels(Collection<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (ChunkStore.Row r : chunks.rowsByIds(ids)) {
            String title = r.title() == null || r.title().isBlank() ? "chunk " + r.id() : r.title();
            String label = "《" + title + "》";
            if (r.sourceRef() != null && !r.sourceRef().isBlank()) {
                label += "（ref: " + r.sourceRef() + "）";
            }
            out.put(r.id(), label);
        }
        return out;
    }

    /** 以锚点为圆心的 1 跳子图（锚点 + 邻居 + 落在这些节点间的边） */
    private SearchDtos.Graph oneHop(String entityId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(entityId);
        for (String neighbor : graph.neighborsOf(List.of(entityId))) {
            if (ids.size() >= MAX_NODES) {
                break;
            }
            ids.add(neighbor);
        }
        List<SearchDtos.Edge> edges = new ArrayList<>();
        for (GraphStore.Edge e : graph.edgesTouching(ids)) {
            if (ids.contains(e.subjectId()) && ids.contains(e.objectId()) && edges.size() < MAX_EDGES) {
                edges.add(new SearchDtos.Edge(e.id(), e.subjectId(), e.objectId(), e.predicate(),
                        e.props()));
            }
        }
        List<SearchDtos.Node> nodes = new ArrayList<>();
        for (GraphStore.Entity e : graph.entitiesByIds(ids)) {
            nodes.add(toNode(e));
        }
        return new SearchDtos.Graph(nodes, edges);
    }

    /** 锚点实体的语义说明（落库为 summary，只在新建实体时写入） */
    private static String semantics(String name) {
        return "图谱人格锚点：「" + name + "」这位智能体的第一人称坐标。"
                + "模型通过维护本实体与其它实体的关系，在图谱上构建并持续修正它的人设与知识。";
    }

    private static SearchDtos.Node toNode(GraphStore.Entity e) {
        String type = e.type() == null || e.type().isBlank() ? "CONCEPT" : e.type();
        return new SearchDtos.Node(e.id(), e.name(), type, e.props(), e.createdAt(), e.updatedAt());
    }

    private static AgentDtos.AnchorView empty(String peerId) {
        return new AgentDtos.AnchorView(peerId, null, null, SearchDtos.Graph.EMPTY);
    }

    private AgentRow require(String peerId) {
        return store.peer(peerId)
                .orElseThrow(() -> new IllegalArgumentException("成员不存在: " + peerId));
    }
}
