package cc.ginpika.minis.ai.memory.recall;

import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.store.MemoryItemEmbeddingStore;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.ai.memory.store.MemoryVecMath;
import cc.ginpika.minis.config.MemoryProps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆条目的召回读侧（STEP M3）：候选收敛 → 向量余弦打分 → scope 加权 → 门控过滤 → 排序。
 *
 * <p>与 {@link MemoryRecall}（事件层读侧）的分工：本类读的是「记忆条目」（graph_claim），
 * 也就是记忆 v2 默认召回与注入的对象；事件层只作下钻 / 审计。
 *
 * <p>两轴各司其职，沿用事件读侧的取舍：
 * <ul>
 *   <li><b>候选收敛</b>——只取「仍有效」的条目（未取代、未过期），上限 {@link #CANDIDATE_LIMIT} 条；</li>
 *   <li><b>向量打分 + scope 加权</b>——profile / directive 的权重高于 episode；</li>
 *   <li><b>门控</b>——低于 {@link MemoryProps#getMinScore()} 且非固定（pinned）的一律不返回；
 *       查询向量拿不到（embedding 未配置 / 失败）时只返回固定条目——读路径永不空手，也绝不灌噪声。</li>
 * </ul>
 */
@Component
public class MemoryItemRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryItemRecall.class);

    /** 候选收敛上限：余弦只在这批候选内算 */
    private static final int CANDIDATE_LIMIT = 200;

    /** 单次召回条数上限 */
    private static final int LIMIT_MAX = 50;

    /** 一条召回结果：score 为与 query 的加权余弦（≥0）；固定条目恒在结果内 */
    public record Hit(MemoryItemStore.ItemRow item, double score) {
    }

    private final MemoryItemStore items;
    private final MemoryItemEmbeddingStore vectors;
    private final EmbeddingClient embedding;
    private final MemoryProps props;

    public MemoryItemRecall(MemoryItemStore items, MemoryItemEmbeddingStore vectors,
                            EmbeddingClient embedding, MemoryProps props) {
        this.items = items;
        this.vectors = vectors;
        this.embedding = embedding;
        this.props = props;
    }

    /** 按 query 召回仍有效的条目（**无作用域**，管理页/内部流程用）；scope 可空（=全部）；limit 上限 50 */
    public List<Hit> recall(String query, String scope, int limit) {
        return recall(MemoryExec.system(null),query,scope,limit);
    }

    /**
     * 按 query 召回**当前作用域可见**的条目（memory-v5 §5.2）：候选集由 {@code visible} 谓词在 SQL 里收窄，
     * 未经授权的条目根本不进候选池。注入与工具走这一条。
     */
    public List<Hit> recall(MemoryExec exec, String query, String scope, int limit) {
        return recall(exec,query,scope,limit,null,null,null);
    }

    public List<Hit> recall(MemoryExec exec, String query, String scope, int limit, Long from, Long to, String kind) {
        java.util.LinkedHashMap<Long, MemoryItemStore.ItemRow> all = new java.util.LinkedHashMap<>();
        boolean timed=from!=null || to!=null || (kind!=null && !kind.isBlank());
        float[] qvec=queryVector(query);
        if(!timed) items.protectedVisible(exec).stream().filter(it -> scope == null || scope.isBlank()
                || scope.equals(it.scope())).forEach(it -> all.put(it.id(), it));
        var lexical=items.keywordCandidates(exec,scope,query,CANDIDATE_LIMIT,from,to,kind);
        var semantic=vectors.nearest(exec,scope,embedding.model(),qvec,CANDIDATE_LIMIT,from,to,kind);
        lexical.forEach(it->all.putIfAbsent(it.id(),it));semantic.forEach(it->all.putIfAbsent(it.id(),it));
        var seeds=java.util.stream.Stream.concat(lexical.stream(),semantic.stream()).map(MemoryItemStore.ItemRow::id).distinct().limit(8).toList();
        var neighbors=timed?List.<MemoryItemStore.ItemRow>of():items.related(exec,seeds,40);
        neighbors.forEach(it->all.putIfAbsent(it.id(),it));
        java.util.Map<Long,Double> lexicalScores=new java.util.HashMap<>();
        for(int i=0;i<lexical.size();i++) lexicalScores.put(lexical.get(i).id(),0.7/(1+i*0.05));
        for(var item:neighbors) lexicalScores.putIfAbsent(item.id(),0.4);
        if(timed && (query==null || query.isBlank())) {
            var timeline=items.temporalCandidates(exec,scope,from,to,kind,Math.min(50,Math.max(1,limit)));
            return timeline.stream().map(it->new Hit(it,1d)).toList();
        }
        return score(qvec,new ArrayList<>(all.values()),limit,lexicalScores);
    }

    /**
     * 无查询时的可见候选（memory-v6 §4.3）：不做相关性打分，交由快照构建器只保留
     * **受保护条目**（全局规则 / 正式画像 / pinned / 未完成事项）——这正是它们「恒定注入」的落点。
     */
    public List<MemoryItemStore.ItemRow> visible(MemoryExec exec, int limit) {
        return items.protectedVisible(exec);
    }
    public Object evidence(MemoryExec exec,long id,int offset,int limit) { return items.evidence(exec,id,offset,limit); }

    /** 候选 → 向量余弦打分 → 三轴加权 → 门控 → 排序（无作用域与有作用域两条读路径共用） */
    private List<Hit> score(String query, List<MemoryItemStore.ItemRow> candidates, int limit) {
        return score(queryVector(query),candidates,limit,Map.of());
    }

    private List<Hit> score(float[] qvec,List<MemoryItemStore.ItemRow> candidates,int limit,Map<Long,Double> lexical) {
        int cap = Math.max(1, Math.min(limit, LIMIT_MAX));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, float[]> vecs = qvec == null ? Map.of()
                : vectors.byItemIds(embedding.model(),
                        candidates.stream().map(MemoryItemStore.ItemRow::id).toList());
        double min = Math.max(0d, props.getMinScore());
        List<Hit> hits = new ArrayList<>();
        for (MemoryItemStore.ItemRow it : candidates) {
            float[] v = vecs.get(it.id());
            double score = Math.max(lexical.getOrDefault(it.id(),0d),qvec == null || v == null ? 0d
                    : Math.max(0d, MemoryVecMath.cosine(qvec, v))) * weight(it);
            /* 门控：非固定条目低于阈值不返回——宁可不注入，也不给 system 灌噪声 */
            if (!cc.ginpika.minis.ai.memory.MemoryVocabulary.isProtected(it.owner(), it.scope(), it.pinned(), it.retention()) && score < min) {
                continue;
            }
            hits.add(new Hit(it, it.pinned() ? Math.max(score, min) : score));
        }
        /* 排序：固定条目优先，其次按分降序，同分保持「新在前」 */
        hits.sort((a, b) -> {
            if (a.item().pinned() != b.item().pinned()) {
                return a.item().pinned() ? -1 : 1;
            }
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : Long.compare(b.item().id(), a.item().id());
        });
        List<Hit> selected = new ArrayList<>();
        int ordinary = 0;
        for (Hit h : hits) {
            if (cc.ginpika.minis.ai.memory.MemoryVocabulary.isProtected(h.item().owner(),
                    h.item().scope(), h.item().pinned(), h.item().retention()) || ordinary++ < cap) {
                selected.add(h);
            }
        }
        return selected;
    }

    /**
     * 三轴加权（memory-v6 §8.2）：{@code W_layer × W_domain × W_evidence}——替代 v5 的单维 scope 权重，
     * 让「层（可见范围）× 域（描述对象）× 证据等级」都体现在取舍上。未配置项回落到默认值。
     */
    private double weight(MemoryItemStore.ItemRow it) {
        MemoryProps.Weights w = props.getWeights();
        double layer = MemoryProps.Weights.of(w.getLayer(), it.layer(), 1.0d);
        double domain = MemoryProps.Weights.of(w.getDomain(), it.domain(), 1.0d);
        double evidence = MemoryProps.Weights.of(w.getEvidence(), it.evidenceLevel(), 1.0d);
        return layer * domain * evidence;
    }

    /** 查询向量：未配置 / 空 query / 调用失败一律 null（退化为「只返回固定条目」），不抛错 */
    private float[] queryVector(String query) {
        if (query == null || query.isBlank() || !embedding.configured()) {
            return null;
        }
        try {
            return embedding.embed(query).orElse(null);
        } catch (RuntimeException e) {
            log.warn("记忆条目查询向量化失败（退化为只返回固定条目）：{}", e.getMessage());
            return null;
        }
    }

    /** 单条渲染成一行（注入 / 工具 / token 估算共用）：{@code 【标签 · L2】标题：正文}。
     * 带上层级标签是 memory-v6 §8.4 的要求——模型据此区分「画像 / 项目 / 我的经验 / 共享知识」的可信度。
     *
     * @param maxChars 正文截断长度；≤0 = 不截断
     */
    public static String oneLine(MemoryItemStore.ItemRow it, int maxChars) {
        String body = it.body() == null ? "" : it.body().trim();
        if (maxChars > 0 && body.length() > maxChars) {
            body = body.substring(0, maxChars) + "…";
        }
        StringBuilder sb = new StringBuilder("- [id=").append(it.id()).append("]【").append(scopeLabel(it.scope()));
        String layer = layerLabel(it.layer());
        if (!layer.isBlank()) {
            sb.append(" · ").append(layer);
        }
        if ("FICTION".equals(it.identity())) { sb.append(" · 虚构项目"); }
        if ("inferred".equals(it.evidenceLevel())) { sb.append(" · 待验证"); }
        sb.append(" · ").append(cc.ginpika.minis.ai.memory.MemoryTime.label(it.temporalJson()));
        sb.append("】");
        if (it.title() != null && !it.title().isBlank()) {
            sb.append(it.title().trim()).append("：");
        }
        return sb.append(body).toString();
    }

    /** 层级标签（L1 共享 / L2 私有 / L3 任务 / L4 发布）；未知返回空串 */
    public static String layerLabel(String layer) {
        return switch (layer == null ? "" : layer) {
            case "L1" -> "L1 共享";
            case "L2" -> "L2 私有";
            case "L3" -> "L3 任务";
            case "L4" -> "L4 发布";
            default -> "";
        };
    }

    /** 召回结果 → 注入 / 工具共用的可读文本：一行一条；空结果空串 */
    public static String hitsText(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append(oneLine(h.item(), 0)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** scope → 人话标签 */
    public static String scopeLabel(String scope) {
        return switch (scope == null ? "" : scope) {
            case MemoryItemStore.SCOPE_DIRECTIVE -> "指令";
            case MemoryItemStore.SCOPE_PROFILE -> "画像";
            case MemoryItemStore.SCOPE_PROJECT -> "项目";
            case MemoryItemStore.SCOPE_EPISODE -> "情景";
            default -> scope == null ? "记忆" : scope;
        };
    }
}
