package cc.ginpika.minis.ai.knowledge.retrieval;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 实体别名命中 + 图谱 BFS 1~N 跳展开（返回锚点实体、邻居、边、关联 chunk） */
public final class EntityEnricher {

    private final GraphStore store;
    private final int maxHops;

    public EntityEnricher(GraphStore store, int maxHops) {
        this.store = store;
        this.maxHops = maxHops;
    }

    public record Result(List<GraphStore.Entity> anchors, List<GraphStore.Entity> allEntities,
                         List<GraphStore.Edge> edges, List<Long> chunkIds) {
        public boolean isEmpty() {
            return anchors.isEmpty();
        }
    }

    public Result enrich(QueryTerms.Parsed q) {
        List<String> terms = new ArrayList<>();
        terms.addAll(q.enTokens());
        terms.addAll(q.cnRuns());
        if (q.raw().length() >= 2 && q.raw().length() <= 40) {
            terms.add(q.raw().trim());
        }
        List<GraphStore.Entity> anchors = store.lookup(terms, 6);
        if (anchors.isEmpty()) {
            return new Result(List.of(), List.of(), List.of(), List.of());
        }

        LinkedHashSet<String> visited = new LinkedHashSet<>();
        anchors.forEach(e -> visited.add(e.id()));
        Set<String> frontier = new HashSet<>(anchors.stream().map(GraphStore.Entity::id).toList());

        for (int hop = 1; hop <= maxHops && !frontier.isEmpty(); hop++) {
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

        List<GraphStore.Edge> edges = store.edgesTouching(visited).stream()
                .filter(e -> visited.contains(e.subjectId()) && visited.contains(e.objectId()))
                .distinct()
                .toList();
        List<GraphStore.Entity> all =
                store.entitiesByIds(visited);
        List<Long> chunkIds = store.chunkIdsForEntities(anchors.stream().map(GraphStore.Entity::id).toList(), 12);
        return new Result(anchors, all, edges, chunkIds);
    }
}
