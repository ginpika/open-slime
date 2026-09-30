package cc.ginpika.minis.ai.knowledge.retrieval;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱子图探索（HippoRAG 2 的廉价迁移）：以若干 seed 实体做 Personalized PageRank 扩散，
 * 一次拿到「与 seed 最相关的周边实体 + 触及的边 + 关联 chunk」——把模型原本
 * 「找实体 → 展开 → 查路径 → 再找实体」的多轮工具往返压成一次调用（无 LLM 成本）。
 *
 * <p>实现：BFS 3 跳收集局部子图（节点上限防大图失控）→ 在子图上跑 PPR 幂迭代
 * （重启概率 ALPHA，边权 = 平行边重数 / sqrt(deg(u)·deg(v))，度数越大权重越低，
 * 避免公共枢纽淹没答案），共同邻居会自然浮到高分位（如「张伟」「李娜」→ 星尘科技）。
 */
public final class GraphExplorer {

    /** PPR 重启概率（回到 seed 的比重；越小扩散越远） */
    private static final double ALPHA = 0.3;
    /** 幂迭代次数（个人知识库规模下 24 轮足够收敛） */
    private static final int ITERATIONS = 24;
    /** BFS 收集的跳数上限 */
    private static final int HOPS = 3;

    private final GraphStore store;

    public GraphExplorer(GraphStore store) {
        this.store = store;
    }

    /** 带相关度分的实体（PPR 得分，仅用于排序/筛选，无绝对含义） */
    public record Scored(GraphStore.Entity entity, double score) {
    }

    /** 探索结果：seed 实体、按相关度降序的周边实体、其间的高分边、关联 chunk */
    public record Exploration(List<GraphStore.Entity> seeds, List<Scored> top,
                              List<GraphStore.Edge> edges, List<Long> chunkIds) {
    }

    /** 一条带转移概率的弧：v --p--> to */
    private record Transition(String to, double p) {
    }

    /**
     * 从 seedIds 出发探索：maxNodes 为 BFS 子图节点上限，topK 为返回的周边实体数上限，
     * edgeLimit 为返回边数上限（按两端得分之和降序截断）。
     */
    public Exploration explore(Set<String> seedIds, int maxNodes, int topK, int edgeLimit) {
        List<GraphStore.Entity> seedEntities = store.entitiesByIds(seedIds);
        if (seedEntities.isEmpty()) {
            return new Exploration(List.of(), List.of(), List.of(), List.of());
        }
        Set<String> seeds = new LinkedHashSet<>(seedEntities.stream().map(GraphStore.Entity::id).toList());

        /* BFS 骨架（同 EntityEnricher）：3 跳内收集局部子图，节点上限截断 */
        LinkedHashSet<String> visited = new LinkedHashSet<>(seeds);
        Set<String> frontier = new HashSet<>(seeds);
        for (int hop = 1; hop <= HOPS && !frontier.isEmpty(); hop++) {
            Set<String> next = store.neighborsOf(frontier);
            next.removeAll(visited);
            for (String id : next) {
                if (visited.size() >= maxNodes) {
                    break;
                }
                visited.add(id);
            }
            if (visited.size() >= maxNodes) {
                break;
            }
            frontier = next;
        }

        List<GraphStore.Edge> subEdges = store.edgesTouching(visited).stream()
                .filter(e -> visited.contains(e.subjectId()) && visited.contains(e.objectId()))
                .toList();
        if (subEdges.isEmpty()) {
            return new Exploration(seedEntities, List.of(), List.of(), List.of());
        }

        Map<String, Double> scores = ppr(seeds, subEdges);

        /* 周边实体：得分降序，剔除 seed 自身 */
        List<String> topIds = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (!seeds.contains(e.getKey()) && e.getValue() > 0) {
                topIds.add(e.getKey());
            }
        }
        topIds.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        if (topIds.size() > topK) {
            topIds = topIds.subList(0, topK);
        }
        Set<String> keep = new LinkedHashSet<>(seeds);
        keep.addAll(topIds);
        List<Scored> top = new ArrayList<>(topIds.size());
        for (GraphStore.Entity ent : store.entitiesByIds(topIds)) {
            top.add(new Scored(ent, scores.getOrDefault(ent.id(), 0d)));
        }

        /* 高分边：两端均在保留集合内，按两端得分之和降序 */
        List<GraphStore.Edge> edges = subEdges.stream()
                .filter(e -> keep.contains(e.subjectId()) && keep.contains(e.objectId()))
                .sorted((a, b) -> Double.compare(
                        scores.getOrDefault(b.subjectId(), 0d) + scores.getOrDefault(b.objectId(), 0d),
                        scores.getOrDefault(a.subjectId(), 0d) + scores.getOrDefault(a.objectId(), 0d)))
                .limit(edgeLimit)
                .toList();

        List<Long> chunkIds = store.chunkIdsForEntities(keep, 12);
        return new Exploration(seedEntities, top, edges, chunkIds);
    }

    /** 子图上的 Personalized PageRank 幂迭代：r ← ALPHA·s + (1-ALPHA)·Pᵀr（s 为 seed 均匀分布） */
    private static Map<String, Double> ppr(Set<String> seeds, List<GraphStore.Edge> edges) {
        /* 度数（平行边计重数）与邻居重数表（无向：两端互记） */
        Map<String, Integer> degree = new HashMap<>();
        Map<String, Map<String, Integer>> multiplicity = new HashMap<>();
        for (GraphStore.Edge e : edges) {
            degree.merge(e.subjectId(), 1, Integer::sum);
            degree.merge(e.objectId(), 1, Integer::sum);
            multiplicity.computeIfAbsent(e.subjectId(), k -> new HashMap<>())
                    .merge(e.objectId(), 1, Integer::sum);
            multiplicity.computeIfAbsent(e.objectId(), k -> new HashMap<>())
                    .merge(e.subjectId(), 1, Integer::sum);
        }
        /* 行归一化的转移表：P(v→u) = w(v,u)/Σ_u w(v,·)，w = 重数/sqrt(deg(v)·deg(u)) */
        Map<String, List<Transition>> transitions = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> from : multiplicity.entrySet()) {
            String v = from.getKey();
            double dv = degree.getOrDefault(v, 1);
            List<Transition> arcs = new ArrayList<>(from.getValue().size());
            double total = 0;
            for (Map.Entry<String, Integer> to : from.getValue().entrySet()) {
                double w = to.getValue() / Math.sqrt(dv * degree.getOrDefault(to.getKey(), 1));
                arcs.add(new Transition(to.getKey(), w));
                total += w;
            }
            if (total > 0) {
                for (int i = 0; i < arcs.size(); i++) {
                    Transition t = arcs.get(i);
                    arcs.set(i, new Transition(t.to(), t.p() / total));
                }
                transitions.put(v, arcs);
            }
        }

        double base = 1d / seeds.size();
        Map<String, Double> r = new LinkedHashMap<>();
        for (String id : degree.keySet()) {
            r.put(id, seeds.contains(id) ? base : 0d);
        }
        for (int it = 0; it < ITERATIONS; it++) {
            Map<String, Double> next = new LinkedHashMap<>();
            for (String id : r.keySet()) {
                next.put(id, seeds.contains(id) ? ALPHA * base : 0d);
            }
            for (Map.Entry<String, Double> cur : r.entrySet()) {
                double mass = (1 - ALPHA) * cur.getValue();
                if (mass <= 0) {
                    continue;
                }
                List<Transition> arcs = transitions.get(cur.getKey());
                if (arcs == null) {
                    continue;
                }
                for (Transition t : arcs) {
                    next.merge(t.to(), mass * t.p(), Double::sum);
                }
            }
            r = next;
        }
        return r;
    }
}
