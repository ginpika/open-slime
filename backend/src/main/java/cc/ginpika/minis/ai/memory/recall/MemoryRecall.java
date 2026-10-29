package cc.ginpika.minis.ai.memory.recall;

import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.store.MemoryEventEmbeddingStore;
import cc.ginpika.minis.ai.memory.store.MemoryEventStore;
import cc.ginpika.minis.ai.memory.store.MemoryVecMath;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆召回的融合读侧：时间窗收敛候选 → 窗口内向量余弦打分 → 返回融合结果。
 *
 * <p>两轴各司其职，绝不混淆量纲：
 * <ul>
 *   <li><b>时序轴只过滤</b>——since/until/kind/actor 收窄候选集（上限 {@link #CANDIDATE_LIMIT} 条，
 *       不做全表暴力扫），时间序不进任何打分公式（把「最新」当「最相关」是 rank 融合的设计错误，
 *       见 FastSearchService.rrf 的注释）；</li>
 *   <li><b>向量轴只打分</b>——query 向量化后在窗口内算同模型余弦，按分降序、同分保持时间倒序。
 *       查询向量拿不到（embedding 未配置 / 调用失败 / query 为空）一律退化为纯时间序——
 *       读路径永不因向量服务抖动而空手而归（降级语义同 Reranker）。</li>
 * </ul>
 *
 * <p>消费方：recall_memory 工具（模型主动查记忆）与潜意识 "memory" 注入器（素材注入）。
 */
@Component
public class MemoryRecall {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecall.class);

    /** 时间窗收敛后的候选上限：余弦只在这批候选内算，事件流涨大也不许退化为全表扫 */
    private static final int CANDIDATE_LIMIT = 200;

    /** 单次召回条数上限（与注入器/工具面的 clamp 一致） */
    private static final int LIMIT_MAX = 50;

    /**
     * 后台产出（心跳主会话 / 职责被派出 / 子代理 的自动跑动）：默认召回一律挡掉。
     *
     * <p>记忆回答的是「你对我说过什么、我答应过什么」，后台自己跑出来的东西不该挤占候选池；
     * 它们的结论另有 {@code recent_results} 注入器专供，不需要从记忆里再捞一遍。
     */
    public static final List<String> BACKGROUND_ACTORS = List.of("heartbeat", "plan", "agent");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 一条召回结果：score 为与 query 的余弦（≥0）；无向量/无查询向量时为 0（此时序即结果序） */
    public record Hit(MemoryEventStore.EventRow event, float score) {
    }

    private final MemoryEventStore events;
    private final MemoryEventEmbeddingStore vectors;
    private final EmbeddingClient embedding;

    public MemoryRecall(MemoryEventStore events, MemoryEventEmbeddingStore vectors,
                        EmbeddingClient embedding) {
        this.events = events;
        this.vectors = vectors;
        this.embedding = embedding;
    }

    /**
     * 融合召回：时间窗（可单侧开放）+ kind/actor 过滤收敛候选，再在窗口内按与 query 的
     * 语义相关度排序（拿不到查询向量时就是纯时间倒序）。limit 上限 50。
     *
     * <p>没指定 {@code actor} 时按默认挡掉后台产出（{@link #BACKGROUND_ACTORS}）——
     * 混池会让「我上次跟你说过什么」变脏；要专看某一路就显式传 {@code actor}，
     * 那时以它为准、不再另加排除。
     */
    public List<Hit> recall(String query, Long sinceMs, Long untilMs, String kind, String actor,
                            int limit) {
        return recall(cc.ginpika.minis.ai.core.MemoryExec.system(null), query, sinceMs, untilMs,
                kind, actor, limit);
    }

    public List<Hit> recall(cc.ginpika.minis.ai.core.MemoryExec exec, String query, Long sinceMs,
                           Long untilMs, String kind, String actor, int limit) {
        int cap = Math.max(1, Math.min(limit, LIMIT_MAX));
        float[] qvec = queryVector(query);
        var order=java.util.Comparator.comparingDouble((Hit h)->h.score()).thenComparingLong(h->h.event().createdAt()).thenComparingLong(h->h.event().id());
        var best=new java.util.PriorityQueue<Hit>(order);
        long after=0;
        while(true) {
        List<MemoryEventStore.EventRow> candidates=events.page(exec,sinceMs,untilMs,kind,actor,
                actor==null||actor.isBlank()?BACKGROUND_ACTORS:List.of(),after,256);
        if(candidates.isEmpty()) break;
        Map<Long, float[]> vecs = qvec == null ? Map.of()
                : vectors.byEventIds(embedding.model(), candidates.stream()
                        .map(MemoryEventStore.EventRow::id).toList());
        for (MemoryEventStore.EventRow e : candidates) {
            float[] v = vecs.get(e.id());
            /* 候选没向量（欠账/旧模型）＝无分，跟在全分事件之后按时间序垫底 */
            float score = qvec == null || v == null ? 0f
                    : Math.max(0f, MemoryVecMath.cosine(qvec, v));
            if(qvec==null && query!=null && !query.isBlank() && (java.util.Objects.toString(e.title(),"")+" "+java.util.Objects.toString(e.payload(),"")).toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) score=0.7f;
            best.add(new Hit(e,score));if(best.size()>cap) best.poll();
            after=e.id();
        }
        }
        List<Hit> hits=new ArrayList<>(best);hits.sort(order.reversed());return hits;
    }

    /** 查询向量：未配置/空 query/调用失败一律 null（= 无向量轴，退化为时间序），不抛错 */
    private float[] queryVector(String query) {
        if (query == null || query.isBlank() || !embedding.configured()) {
            return null;
        }
        try {
            return embedding.embed(query).orElse(null);
        } catch (RuntimeException e) {
            log.warn("记忆查询向量化失败（退化为时间序）：{}", e.getMessage());
            return null;
        }
    }

    /** 召回结果 → 注入/工具共用的可读文本：一行一条「时间〔谁·在哪〕正文开头」；空结果空串 */
    public static String hitsText(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            MemoryEventStore.EventRow r = h.event();
            sb.append("- ").append(STAMP.format(Instant.ofEpochMilli(r.createdAt())))
                    .append("〔").append(actorText(r.actor()));
            if (r.title() != null && !r.title().isBlank()) {
                sb.append("·").append(r.title().trim());
            }
            sb.append("〕").append(head(r.payload(), 160)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 事件主角 → 人话（注入侧认得出「这条是谁干的」） */
    static String actorText(String actor) {
        return switch (actor == null ? "" : actor) {
            case "user" -> "用户";
            case "assistant" -> "我";
            case "heartbeat" -> "心跳";
            case "plan" -> "计划";
            case "agent" -> "子代理";
            default -> actor == null ? "?" : actor;
        };
    }

    /** 压平空白并截头（记忆事件行只要「认得出这件事」，不是全文） */
    private static String head(String s, int limit) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return t.length() > limit ? t.substring(0, limit) + "…" : t;
    }
}
