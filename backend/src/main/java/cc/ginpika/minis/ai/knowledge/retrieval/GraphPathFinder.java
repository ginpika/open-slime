package cc.ginpika.minis.ai.knowledge.retrieval;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 图路径查询：两个实体之间的前 k 条最短路径（无权图按跳数，Yen 算法求 k 短路）。
 *
 * <p>边按无向处理——关系既可由主语走向宾语，也可反向行走，每步记录关系词与遍历方向，
 * 供上层还原「谁 --关系--> 谁」。两点不连通时返回空列表。
 */
@Component
public class GraphPathFinder {

    private final GraphStore graph;

    public GraphPathFinder(GraphStore graph) {
        this.graph = graph;
    }

    /** 路径中的一步：连接 nodeIds[i] 与 nodeIds[i+1]。forward=true 表示图中为「前者 --predicate--> 后者」，
     *  false 表示图中为「后者 --predicate--> 前者」（反向行走）。edgeId 供上层回查该边的属性与来源 chunk。 */
    public record Step(String predicate, boolean forward, String edgeId) {
    }

    /** 一条路径：节点 id 依次相连，steps 与相邻节点对一一对应 */
    public record Path(List<String> nodeIds, List<Step> steps) {
        public int hops() {
            return steps.size();
        }
    }

    /** 从某节点出发的一步 */
    private record Arc(String toId, String predicate, boolean forward, String edgeId) {
    }

    /** 最短路回溯用的前趋 */
    private record Prev(String fromId, String predicate, boolean forward, String edgeId) {
    }

    /**
     * 求 sourceId 到 targetId 的前 k 条最短路径（按跳数升序、互不重复）；不足 k 条时返回全部，
     * 不连通返回空列表。k<=0 或两端为同一实体返回空列表。
     */
    public List<Path> shortestPaths(String sourceId, String targetId, int k) {
        if (k <= 0 || sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return List.of();
        }
        /* 本次查询内的邻接缓存：Yen 迭代会反复展开同一节点，缓存避免重复查库 */
        Map<String, List<Arc>> adjacency = new HashMap<>();
        Path first = shortest(sourceId, targetId, Set.of(), Set.of(), adjacency);
        if (first == null) {
            return List.of();
        }
        List<Path> accepted = new ArrayList<>();
        accepted.add(first);
        List<Path> candidates = new ArrayList<>();
        while (accepted.size() < k) {
            Path prev = accepted.get(accepted.size() - 1);
            List<String> nodes = prev.nodeIds();
            for (int i = 0; i < nodes.size() - 1; i++) {
                List<String> root = new ArrayList<>(nodes.subList(0, i + 1));
                /* 与已选路径共享 root 前缀的一步禁用，保证候选与已选路径不重复 */
                Set<String> bannedArcs = new HashSet<>();
                for (Path p : accepted) {
                    if (p.nodeIds().size() > i + 1 && p.nodeIds().subList(0, i + 1).equals(root)) {
                        bannedArcs.add(key(p.nodeIds().get(i), p.nodeIds().get(i + 1)));
                    }
                }
                Set<String> bannedNodes = new HashSet<>(nodes.subList(0, i));
                Path spur = shortest(nodes.get(i), targetId, bannedArcs, bannedNodes, adjacency);
                if (spur == null) {
                    continue;
                }
                Path total = join(root, prev, i, spur);
                if (!contains(accepted, total) && !contains(candidates, total)) {
                    candidates.add(total);
                }
            }
            if (candidates.isEmpty()) {
                break;
            }
            candidates.sort(Comparator.comparingInt(Path::hops));
            accepted.add(candidates.remove(0));
        }
        return accepted;
    }

    /** 无权图最短路（BFS）：bannedArcs 为禁行的有向一步（from\0to），bannedNodes 为禁入节点 */
    private Path shortest(String sourceId, String targetId, Set<String> bannedArcs,
                          Set<String> bannedNodes, Map<String, List<Arc>> adjacency) {
        Map<String, Prev> prev = new HashMap<>();
        prev.put(sourceId, null);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(sourceId);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(targetId)) {
                break;
            }
            for (Arc arc : arcs(cur, adjacency)) {
                if (bannedNodes.contains(arc.toId())
                        || bannedArcs.contains(key(cur, arc.toId()))
                        || prev.containsKey(arc.toId())) {
                    continue;
                }
                prev.put(arc.toId(), new Prev(cur, arc.predicate(), arc.forward(), arc.edgeId()));
                queue.add(arc.toId());
            }
        }
        if (!prev.containsKey(targetId)) {
            return null;
        }
        List<String> nodes = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (String cur = targetId; cur != null; ) {
            nodes.add(cur);
            Prev p = prev.get(cur);
            if (p != null) {
                steps.add(new Step(p.predicate(), p.forward(), p.edgeId()));
            }
            cur = p == null ? null : p.fromId();
        }
        Collections.reverse(nodes);
        Collections.reverse(steps);
        return new Path(nodes, steps);
    }

    /** 某节点出发的全部一步（边的两端都可作为起点，方向记在 Arc.forward 上），带本次查询级缓存 */
    private List<Arc> arcs(String nodeId, Map<String, List<Arc>> adjacency) {
        return adjacency.computeIfAbsent(nodeId, id -> {
            List<Arc> out = new ArrayList<>();
            for (GraphStore.Edge e : graph.edgesTouching(List.of(id))) {
                if (id.equals(e.subjectId())) {
                    out.add(new Arc(e.objectId(), e.predicate(), true, e.id()));
                } else if (id.equals(e.objectId())) {
                    out.add(new Arc(e.subjectId(), e.predicate(), false, e.id()));
                }
            }
            return out;
        });
    }

    /** 拼接 root（0..i）+ spur 路径（去掉重复的 spur 起点）为一整条路径 */
    private static Path join(List<String> root, Path prev, int i, Path spur) {
        List<String> nodes = new ArrayList<>(root);
        List<Step> steps = new ArrayList<>(prev.steps().subList(0, i));
        nodes.addAll(spur.nodeIds().subList(1, spur.nodeIds().size()));
        steps.addAll(spur.steps());
        return new Path(nodes, steps);
    }

    /** 有向一步的去重键 */
    private static String key(String fromId, String toId) {
        return fromId + "\u0000" + toId;
    }

    /** 是否已收集同节点序列的路径 */
    private static boolean contains(List<Path> paths, Path path) {
        for (Path p : paths) {
            if (p.nodeIds().equals(path.nodeIds())) {
                return true;
            }
        }
        return false;
    }
}
