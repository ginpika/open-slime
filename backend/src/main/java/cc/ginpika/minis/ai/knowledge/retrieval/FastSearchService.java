package cc.ginpika.minis.ai.knowledge.retrieval;

import cc.ginpika.minis.config.FastProps;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.llm.RerankClient;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.ai.knowledge.web.WebRetrievalService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 搜索栏编排：两路召回（关键词/实体图谱展开）→ RRF 合并 → chunk 命中聚合为文档命中。
 * 返回范围只有文档与实体，不返回 chunk（散装 chunk 也不进结果）；
 * 向量召回（VectorRecaller）属 RAG 检索，不在搜索栏流程内。
 * 联网补充独立返回，不参与本地 chunk 的 RRF 与文档聚合。
 *
 * <p>测试暗门：rag=true 走 RAG 检索模式（前端由首页滑块「搜索」按钮双击进入）——
 * 全量召回（关键词+向量+图谱）且只返回 chunk 命中，供 RAG 检索链路联调验证。
 */
@Service
public class FastSearchService {

    private static final int TOP = 40;

    private final ChunkStore chunks;
    private final DocumentStore docs;
    private final GraphStore graph;
    private final EmbeddingClient embedding;
    private final FastProps props;
    private final KeywordRecaller kw;
    private final VectorRecaller vec;
    private final Reranker reranker;
    private final WebRetrievalService web;

    public FastSearchService(ChunkStore chunks, DocumentStore docs, GraphStore graph,
                             EmbeddingClient embedding, RerankClient rerank, FastProps props,
                             WebRetrievalService web) {
        this.chunks = chunks;
        this.docs = docs;
        this.graph = graph;
        this.embedding = embedding;
        this.props = props;
        this.kw = new KeywordRecaller(chunks, props.getKeywordTopK());
        this.vec = new VectorRecaller(chunks, props.getVectorTopK());
        this.reranker = new Reranker(rerank, props.getRerankCandidate());
        this.web = web;
    }

    public SearchDtos.SearchResponse search(String raw) {
        return search(raw, false);
    }

    public SearchDtos.SearchResponse search(String raw, boolean rag) {
        QueryTerms.Parsed q = QueryTerms.parse(raw);
        if (q.raw().isBlank()) {
            return new SearchDtos.SearchResponse(raw, List.of(),
                    SearchDtos.Graph.EMPTY, SearchDtos.Web.DISABLED);
        }

        EntityEnricher enricher = new EntityEnricher(graph, props.getGraphHops());
        EntityEnricher.Result enrich = enricher.enrich(q);
        List<List<Long>> sources = recallSources(q, enrich, rag);
        List<Long> merged = rrf(sources, props.getRrfK(), TOP);

        List<String> matched = matchedTerms(q);

        /* RAG 模式：只返回 chunk 命中（RRF 全量合并，含向量路；无实体卡片/文档聚合/图谱面板） */
        if (rag) {
            Map<Long, Double> scores = scoresOf(sources);
            List<SearchDtos.Hit> hits = new ArrayList<>();
            for (ChunkStore.Row row : chunks.rowsByIds(merged)) {
                hits.add(new SearchDtos.Hit("chunk", row.id(), null,
                        firstNonBlank(row.title(), "chunk " + row.id()),
                        snippet(row.content(), matched),
                        row.source(), row.sourceRef(), null,
                        scores.getOrDefault(row.id(), 0d), matched, row.status()));
            }
            return new SearchDtos.SearchResponse(raw, hits,
                    SearchDtos.Graph.EMPTY, web.search(raw, true));
        }

        List<SearchDtos.Hit> hits = new ArrayList<>();
        for (int i = 0; i < enrich.anchors().size(); i++) {
            GraphStore.Entity e = enrich.anchors().get(i);
            String snippet = firstNonBlank(e.summary(), "图谱锚点实体 · 已关联 " + related(e) + " 个实体");
            hits.add(new SearchDtos.Hit("entity", null, e.id(), e.name(),
                    snippet, "entity", "", null,
                    rrfScoreAt(props.getRrfK(), i), matched, null));
        }

        hits.addAll(docHits(merged, scoresOf(sources), matched));

        SearchDtos.Graph graphDto = buildGraph(enrich);
        return new SearchDtos.SearchResponse(raw, hits, graphDto, web.search(raw, false));
    }

    /** RAG 召回结果（chat 工具用）：RRF 序的 chunk 行 + 图谱结构证据（锚点实体/子图边，可能为空） */
    public record RagRecall(List<ChunkStore.Row> chunks, EntityEnricher.Result graph) {
    }

    /** RAG 召回（chat 工具用）：三路全量（关键词+向量+图谱）RRF 合并返回 chunk；
     *  同时携带图谱展开结果（锚点实体 + 子图边），供上层把「结构证据」一并注入上下文。
     *  RRF 后经 rerank 精排（未配置端点或调用失败则保持 RRF 原序）。 */
    public RagRecall ragRecall(String raw) {
        QueryTerms.Parsed q = QueryTerms.parse(raw);
        if (q.raw().isBlank()) {
            return new RagRecall(List.of(), null);
        }
        EntityEnricher.Result enrich = new EntityEnricher(graph, props.getGraphHops()).enrich(q);
        List<Long> merged = rrf(recallSources(q, enrich, true), props.getRrfK(), TOP);
        return new RagRecall(reranker.rerank(q.raw(), chunks.rowsByIds(merged)), enrich);
    }

    /** 召回源组装：关键词（unicode61+trigram）/ 向量（仅 RAG，失败降级跳过）/ 实体关联 chunk；
     *  各路均剔除已禁用（disabled）的块，保证「禁用」在检索侧生效 */
    private List<List<Long>> recallSources(QueryTerms.Parsed q, EntityEnricher.Result enrich,
                                           boolean withVector) {
        List<Long> kwIds = chunks.excludeDisabled(kw.recall(q));
        List<Long> vecIds = List.of();
        if (withVector) {
            try {
                vecIds = chunks.excludeDisabled(
                        vec.recall(embedding.model(), embedding.embed(q.raw()).orElse(null)));
            } catch (RuntimeException e) {
                vecIds = List.of();
            }
        }
        List<Long> graphIds = chunks.excludeDisabled(enrich.chunkIds());

        List<List<Long>> sources = new ArrayList<>();
        if (!kwIds.isEmpty()) {
            sources.add(kwIds);
        }
        if (!vecIds.isEmpty()) {
            sources.add(vecIds);
        }
        if (!graphIds.isEmpty()) {
            sources.add(graphIds);
        }
        return sources;
    }

    // ---------- 内部 ----------

    /** chunk 级 RRF 分（各源内首个出现位置） */
    private Map<Long, Double> scoresOf(List<List<Long>> sources) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (List<Long> source : sources) {
            for (int pos = 0; pos < source.size(); pos++) {
                long id = source.get(pos);
                scores.putIfAbsent(id, rrfScoreAt(props.getRrfK(), pos));
            }
        }
        return scores;
    }

    /**
     * chunk 命中聚合为文档命中：文档分 = Σ 其 chunk 的 RRF 分；
     * 摘要取文档内最高分 chunk 的命中片段（无命中片段再用文档 summary）；
     * 散装 chunk（无父文档）不进搜索栏结果。
     */
    private List<SearchDtos.Hit> docHits(List<Long> merged, Map<Long, Double> scores,
                                         List<String> matched) {
        Map<Long, Double> docScores = new LinkedHashMap<>();
        Map<Long, ChunkStore.Row> docBest = new HashMap<>();
        for (ChunkStore.Row row : chunks.rowsByIds(merged)) {
            if (row.docId() == null) {
                continue;
            }
            docScores.merge(row.docId(), scores.getOrDefault(row.id(), 0d), Double::sum);
            docBest.putIfAbsent(row.docId(), row);
        }
        if (docScores.isEmpty()) {
            return List.of();
        }
        Map<Long, DocumentStore.DocHit> byId = new HashMap<>();
        for (DocumentStore.DocHit d : docs.docsByIds(docScores.keySet())) {
            byId.put(d.id(), d);
        }
        return docScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(TOP)
                .map(e -> {
                    DocumentStore.DocHit d = byId.get(e.getKey());
                    if (d == null) {
                        return null;
                    }
                    ChunkStore.Row best = docBest.get(e.getKey());
                    boolean snapshot = "snapshot".equals(d.source());
                    String content = best == null ? "" : best.content();
                    if (snapshot && content.contains("\n\n")) content = content.substring(content.indexOf("\n\n") + 2);
                    String snip = best == null ? null : snippet(content, matched);
                    return new SearchDtos.Hit(snapshot ? "snapshot" : "doc", null, null, d.title(),
                            firstNonBlank(snip, d.summary()), d.source(),
                            snapshot && best != null ? best.sourceRef() : d.url() != null ? d.url() : "doc:" + d.id(), d.url(),
                            e.getValue(), matched, null);
                })
                .filter(h -> h != null)
                .toList();
    }

    /** RRF：score(chunk) = Σ_source 1/(k + rank+1)，rank 取每个源内的首个出现位置 */
    private static List<Long> rrf(List<List<Long>> sources, int k, int top) {
        Map<Long, Double> acc = new HashMap<>();
        Map<Long, Integer> seen = new HashMap<>();
        for (List<Long> source : sources) {
            Map<Long, Boolean> local = new HashMap<>();
            for (int pos = 0; pos < source.size(); pos++) {
                long id = source.get(pos);
                if (local.put(id, Boolean.TRUE) == null) {
                    acc.merge(id, 1d / (k + pos + 1d), Double::sum);
                    seen.putIfAbsent(id, pos);
                }
            }
        }
        return acc.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(top)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double rrfScoreAt(int k, int pos) {
        return 1d / (k + pos + 1d);
    }

    private List<String> matchedTerms(QueryTerms.Parsed q) {
        List<String> m = new ArrayList<>(q.enTokens());
        for (String run : q.cnRuns()) {
            if (!m.contains(run)) {
                m.add(run);
            }
        }
        return m;
    }

    private static String snippet(String content, List<String> matched) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int best = -1;
        String hit = null;
        for (String m : matched) {
            int idx = lower.indexOf(m);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
                hit = m;
            }
        }
        if (best < 0) {
            return text.length() > 200 ? text.substring(0, 200) + "…" : text;
        }
        int start = Math.max(0, best - 60);
        int end = Math.min(text.length(), best + (hit == null ? 60 : Math.max(60, hit.length() + 60)));
        String s = text.substring(start, end);
        if (start > 0) {
            s = "…" + s;
        }
        if (end < text.length()) {
            s = s + "…";
        }
        return s;
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private int related(GraphStore.Entity e) {
        try {
            return graph.neighborsOf(List.of(e.id())).size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static SearchDtos.Graph buildGraph(EntityEnricher.Result enrich) {
        if (enrich.isEmpty()) {
            return SearchDtos.Graph.EMPTY;
        }
        List<GraphStore.Entity> anchors = enrich.anchors();
        List<GraphStore.Entity> rest = new ArrayList<>(enrich.allEntities());
        rest.removeAll(anchors);
        List<GraphStore.Entity> ordered = new ArrayList<>(anchors);
        for (GraphStore.Entity e : rest) {
            if (ordered.size() >= 24) {
                break;
            }
            ordered.add(e);
        }
        HashSet<String> nodeIds = new HashSet<>();
        List<SearchDtos.Node> nodes = new ArrayList<>();
        for (GraphStore.Entity e : ordered) {
            nodeIds.add(e.id());
            nodes.add(new SearchDtos.Node(e.id(), e.name(), firstNonBlank(e.type(), "CONCEPT"),
                    e.props(), e.createdAt(), e.updatedAt()));
        }
        List<SearchDtos.Edge> edges = new ArrayList<>();
        for (GraphStore.Edge edge : enrich.edges()) {
            if (!nodeIds.contains(edge.subjectId()) || !nodeIds.contains(edge.objectId())) {
                continue;
            }
            if (edges.size() >= 48) {
                break;
            }
            edges.add(new SearchDtos.Edge(edge.id(), edge.subjectId(), edge.objectId(),
                    edge.predicate(), edge.props()));
        }
        return new SearchDtos.Graph(nodes, edges);
    }
}
