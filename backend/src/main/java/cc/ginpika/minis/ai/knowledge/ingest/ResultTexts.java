package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.core.tool.doc.ReadDocumentTool;
import cc.ginpika.minis.ai.knowledge.retrieval.EntityEnricher;
import cc.ginpika.minis.ai.knowledge.retrieval.GraphExplorer;
import cc.ginpika.minis.ai.knowledge.retrieval.GraphPathFinder;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具结果 → 注入模型上下文的文本渲染：把知识库各工具的命中（chunk / 文档 / 图谱）拼成
 * 模型可读的文本，限量截断。无状态纯函数，供 {@link KnowledgeToolbox} 与检索回归测试共用；
 * 「渲染离数据近」——图谱/chunk 的展示格式与取数上限都在 knowledge 侧维护。
 */
public final class ResultTexts {

    // ---------- 渲染限额 ----------

    /** 单次工具注入的 chunk 上限与单块字符截断 */
    private static final int TOOL_TOP_CHUNKS = 6;
    private static final int CHUNK_CHAR_LIMIT = 1200;
    /** 单次文档查询注入的文档条数上限与摘要截断（只给标题/摘要，不给正文） */
    private static final int TOOL_TOP_DOCS = 10;
    private static final int DOC_SUMMARY_LIMIT = 200;
    /** Header/questions 前缀（语义定位信息）注入时的整体字符上限 */
    private static final int CHUNK_PREFIX_LIMIT = 600;
    /** 图谱查询结果注入上限：实体条数 / 关系条数（渲染与调用方截断共用） */
    public static final int GRAPH_ENTITY_LIMIT = 10;
    public static final int GRAPH_EDGE_LIMIT = 30;

    private ResultTexts() {
    }

    // ---------- chunk / 文档 ----------

    /** RAG 命中 chunk → 注入上下文的文本（带标题/来源，限量截断）；空命中返回提示语 */
    public static String ragChunksText(List<ChunkStore.Row> rows) {
        if (rows.isEmpty()) {
            return "（知识库中没有与关键词相符的 chunk）";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(rows.size(), TOOL_TOP_CHUNKS);
        for (int i = 0; i < n; i++) {
            ChunkStore.Row r = rows.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(r.title() == null || r.title().isBlank() ? "chunk " + r.id() : r.title())
                    .append("（source: ").append(r.source()).append(", ref: ").append(r.sourceRef()).append("）\n")
                    .append(indexText(r)).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 命中 chunk → 注入模型的分段文本：还原提交预案的三分段（Header & Metadata / 假设性问题 / 正文），
     * 由库里已有的 header/questions/content 三列直接拼接（纯字符串操作，不再调用 LLM）；
     * 未生成过的分段整体跳过；正文沿用 CHUNK_CHAR_LIMIT，前缀（header+questions）限 CHUNK_PREFIX_LIMIT。
     */
    private static String indexText(ChunkStore.Row r) {
        String content = r.content() == null ? "" : r.content();
        if (content.length() > CHUNK_CHAR_LIMIT) {
            content = content.substring(0, CHUNK_CHAR_LIMIT) + "…";
        }
        StringBuilder sb = new StringBuilder();
        String prefix = planPrefix(r);
        if (!prefix.isEmpty()) {
            sb.append(prefix).append('\n');
        }
        return sb.append("[PRIMARY CONTENT]\n").append(content).toString();
    }

    /** 语义定位前缀：header / 假设性问题（缺失或空白则跳过），整体超限截断 */
    private static String planPrefix(ChunkStore.Row r) {
        StringBuilder sb = new StringBuilder();
        if (r.header() != null && !r.header().isBlank()) {
            sb.append("[HEADER & METADATA]\n").append(r.header().strip()).append('\n');
        }
        if (r.questions() != null && !r.questions().isBlank()) {
            sb.append("[HYPOTHETICAL QUESTIONS]\n").append(r.questions().strip()).append('\n');
        }
        String prefix = sb.toString().stripTrailing();
        return prefix.length() > CHUNK_PREFIX_LIMIT
                ? prefix.substring(0, CHUNK_PREFIX_LIMIT) + "…"
                : prefix;
    }

    /** 文档标题命中 → 注入上下文的文本（标题/来源/摘要，限量截断）；空命中返回提示语 */
    public static String documentsText(List<DocumentStore.DocHit> docs) {
        if (docs.isEmpty()) {
            return "（知识库中没有标题命中该关键词的文档）";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(docs.size(), TOOL_TOP_DOCS);
        for (int i = 0; i < n; i++) {
            DocumentStore.DocHit d = docs.get(i);
            sb.append('[').append(i + 1).append("] ").append(d.title())
                    .append("（doc_id: ").append(d.id()).append(", source: ").append(d.source())
                    .append(d.url() != null ? ", url: " + d.url() : "").append("）\n");
            String summary = d.summary() == null ? "" : d.summary().replaceAll("\\s+", " ").strip();
            if (!summary.isEmpty()) {
                sb.append("  摘要: ")
                        .append(summary.length() > DOC_SUMMARY_LIMIT
                                ? summary.substring(0, DOC_SUMMARY_LIMIT) + "…" : summary)
                        .append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 文档正文按页切片 → 注入上下文的文本（read_document 的实时分页）：每页字符数钳在
     * (0, {@link ReadDocumentTool#PAGE_MAX}]、非正数取缺省，页码下限 1；越界时只回页信息与可用页码区间，
     * 让模型自己改页码重读；未读完的一页末尾提示下一页页码。
     */
    public static String documentPageText(long docId, String title, String content, int page, int pageSize) {
        if (content.isEmpty()) {
            return "《" + title + "》（doc_id: " + docId + "）没有正文内容";
        }
        int size = pageSize <= 0 ? ReadDocumentTool.PAGE_DEFAULT
                : Math.min(pageSize, ReadDocumentTool.PAGE_MAX);
        int pages = (content.length() + size - 1) / size;
        int p = Math.max(1, page);
        String head = "《" + title + "》（doc_id: " + docId + "，共 " + content.length()
                + " 字，每页 " + size + " 字，共 " + pages + " 页）";
        if (p > pages) {
            return head + "页码 " + p + " 超出范围，请读 1~" + pages + " 页";
        }
        int start = (p - 1) * size;
        int end = Math.min(content.length(), start + size);
        StringBuilder sb = new StringBuilder(head).append('\n');
        sb.append("[第 ").append(p).append('/').append(pages).append(" 页，第 ")
                .append(start + 1).append('-').append(end).append(" 字]\n")
                .append(content, start, end);
        if (p < pages) {
            sb.append("\n（本页未读完，下一页 page=").append(p + 1).append("）");
        }
        return sb.toString();
    }

    // ---------- 知识图谱 ----------

    /** 图谱实体命中 → 注入上下文的文本（名称/类型/属性/摘要，限量）；空命中返回提示语 */
    public static String graphEntitiesText(List<GraphStore.Entity> entities) {
        if (entities.isEmpty()) {
            return "（知识图谱中没有匹配的实体）";
        }
        StringBuilder sb = new StringBuilder();
        int n = Math.min(entities.size(), GRAPH_ENTITY_LIMIT);
        for (int i = 0; i < n; i++) {
            GraphStore.Entity e = entities.get(i);
            sb.append('[').append(i + 1).append("] ").append(e.name())
                    .append("（type: ").append(entityType(e)).append("）\n");
            String props = propsText(e.props());
            if (!props.isEmpty()) {
                sb.append("  属性: ").append(props).append('\n');
            }
            if (e.summary() != null && !e.summary().isBlank()) {
                sb.append("  摘要: ").append(e.summary().strip()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 中心实体 + 其一跳关系 → 注入上下文的文本：先给中心实体卡片（类型/属性/摘要），
     * 再按「主语 --谓词--> 宾语」逐条列出触及该实体的边（限量），每条附带关键 props 与来源 chunk
     * （chunkLabels：chunkId → 可读标签；命中不到标签时退回 chunk id）；边际实体名从 related 中查得。
     * 无关系时只给实体卡片与提示语。
     */
    public static String graphRelationsText(GraphStore.Entity center, List<GraphStore.Edge> edges,
                                             List<GraphStore.Entity> related, Map<Long, String> chunkLabels) {
        Map<String, GraphStore.Entity> byId = new HashMap<>();
        byId.put(center.id(), center);
        for (GraphStore.Entity e : related) {
            byId.putIfAbsent(e.id(), e);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(center.name()).append("（type: ").append(entityType(center)).append("）");
        String props = propsText(center.props());
        if (!props.isEmpty()) {
            sb.append("\n属性: ").append(props);
        }
        if (center.summary() != null && !center.summary().isBlank()) {
            sb.append("\n摘要: ").append(center.summary().strip());
        }
        if (edges.isEmpty()) {
            return sb.append("\n（该实体在知识图谱中没有已记录的关系）").toString();
        }
        sb.append("\n关系:\n");
        int n = Math.min(edges.size(), GRAPH_EDGE_LIMIT);
        for (int i = 0; i < n; i++) {
            GraphStore.Edge edge = edges.get(i);
            sb.append("- ").append(nameOf(byId, edge.subjectId()))
                    .append(" --").append(edge.predicate()).append("--> ")
                    .append(nameOf(byId, edge.objectId()))
                    .append(edgeSuffix(edge, chunkLabels)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 两实体间的最短路径 → 注入上下文的文本：先给两端实体卡片，再按跳数列出各条路径
     * （「谁 --关系--> 谁」，反向行走标为 &lt;--），每步附带该边的 props 与来源
     * （edgesById：edgeId → 边，由 Step.edgeId 回查）；无路径时给出提示语。
     */
    public static String graphPathsText(GraphStore.Entity source, GraphStore.Entity target,
                                        List<GraphPathFinder.Path> paths, Map<String, String> namesById,
                                        Map<String, GraphStore.Edge> edgesById, Map<Long, String> chunkLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append(source.name()).append("（type: ").append(entityType(source)).append("）")
                .append(" → ").append(target.name())
                .append("（type: ").append(entityType(target)).append("）");
        if (paths.isEmpty()) {
            return sb.append("\n（两个实体在知识图谱中没有找到可连通的路径）").toString();
        }
        sb.append("\n最短路径（按跳数升序，共 ").append(paths.size()).append(" 条）:");
        for (int p = 0; p < paths.size(); p++) {
            GraphPathFinder.Path path = paths.get(p);
            List<String> nodes = path.nodeIds();
            sb.append("\n\n[").append(p + 1).append("] ").append(path.hops()).append(" 跳\n");
            for (int i = 0; i < path.steps().size(); i++) {
                GraphPathFinder.Step step = path.steps().get(i);
                String from = nodeName(namesById, nodes.get(i));
                String to = nodeName(namesById, nodes.get(i + 1));
                sb.append("- ").append(from)
                        .append(step.forward()
                                ? " --" + step.predicate() + "--> "
                                : " <--" + step.predicate() + "-- ")
                        .append(to)
                        .append(edgeSuffix(edgesById.get(step.edgeId()), chunkLabels))
                        .append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * PPR 子图探索结果 → 注入上下文的文本：seed 实体卡片、按相关度降序的周边实体
     * （限量）、其间的关系（带 props/来源）、关联文档片段清单。
     */
    public static String subgraphText(GraphExplorer.Exploration exploration, Map<Long, String> chunkLabels) {
        Map<String, GraphStore.Entity> byId = new HashMap<>();
        for (GraphStore.Entity e : exploration.seeds()) {
            byId.put(e.id(), e);
        }
        for (GraphExplorer.Scored s : exploration.top()) {
            byId.put(s.entity().id(), s.entity());
        }
        StringBuilder sb = new StringBuilder("种子实体:\n");
        for (GraphStore.Entity seed : exploration.seeds()) {
            sb.append("- ").append(seed.name()).append("（type: ").append(entityType(seed)).append("）\n");
        }
        if (exploration.top().isEmpty()) {
            return sb.append("（这些实体在知识图谱中没有扩散出相关实体）").toString();
        }
        sb.append("\n相关实体（按相关度降序）:\n");
        int n = Math.min(exploration.top().size(), GRAPH_ENTITY_LIMIT);
        for (int i = 0; i < n; i++) {
            GraphStore.Entity e = exploration.top().get(i).entity();
            sb.append('[').append(i + 1).append("] ").append(e.name())
                    .append("（type: ").append(entityType(e)).append("）\n");
        }
        if (!exploration.edges().isEmpty()) {
            sb.append("\n关系:\n");
            int m = Math.min(exploration.edges().size(), GRAPH_EDGE_LIMIT);
            for (int i = 0; i < m; i++) {
                GraphStore.Edge edge = exploration.edges().get(i);
                sb.append("- ").append(nameOf(byId, edge.subjectId()))
                        .append(" --").append(edge.predicate()).append("--> ")
                        .append(nameOf(byId, edge.objectId()))
                        .append(edgeSuffix(edge, chunkLabels)).append('\n');
            }
        }
        if (!exploration.chunkIds().isEmpty()) {
            sb.append("\n关联文档片段: ");
            List<String> labels = new ArrayList<>();
            for (Long id : exploration.chunkIds()) {
                labels.add(chunkLabels.getOrDefault(id, "chunk " + id));
            }
            sb.append(String.join("、", labels)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * ragRecall 附带的图谱结构证据 → 注入上下文的文本（LightRAG 双层的廉价版，无 LLM 摘要）：
     * 锚点实体清单 + 子图内的三元组行（限量），让模型一次拿到「文本证据 + 结构证据」。
     */
    public static String graphTriplesText(EntityEnricher.Result graph) {
        if (graph == null || graph.isEmpty()) {
            return "";
        }
        Map<String, GraphStore.Entity> byId = new HashMap<>();
        for (GraphStore.Entity e : graph.allEntities()) {
            byId.put(e.id(), e);
        }
        StringBuilder sb = new StringBuilder("[知识图谱结构证据]\n锚点实体: ");
        List<String> anchors = new ArrayList<>();
        for (GraphStore.Entity e : graph.anchors()) {
            anchors.add(e.name() + "（" + entityType(e) + "）");
        }
        sb.append(String.join("、", anchors)).append('\n');
        List<GraphStore.Edge> edges = graph.edges();
        if (!edges.isEmpty()) {
            sb.append("相关关系:\n");
            int n = Math.min(edges.size(), GRAPH_EDGE_LIMIT);
            for (int i = 0; i < n; i++) {
                GraphStore.Edge edge = edges.get(i);
                sb.append("- ").append(nameOf(byId, edge.subjectId()))
                        .append(" --").append(edge.predicate()).append("--> ")
                        .append(nameOf(byId, edge.objectId())).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    // ---------- 渲染助手 ----------

    /** 边的溯源后缀：（属性: k=v; …; 来源: 《标题》）；两者皆无返回空串（edge 可为 null） */
    private static String edgeSuffix(GraphStore.Edge edge, Map<Long, String> chunkLabels) {
        if (edge == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String props = propsText(edge.props());
        if (!props.isEmpty()) {
            sb.append("属性: ").append(props);
        }
        if (edge.sourceChunk() != null) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("来源: ").append(chunkLabels.getOrDefault(edge.sourceChunk(),
                    "chunk " + edge.sourceChunk()));
        }
        return sb.length() == 0 ? "" : "（" + sb + "）";
    }

    /** 实体类型（types 全量清单优先，| 连接；缺失回退 type，再回退 CONCEPT） */
    private static String entityType(GraphStore.Entity e) {
        if (e.types() != null && !e.types().isEmpty()) {
            return String.join("|", e.types());
        }
        return e.type() == null || e.type().isBlank() ? "CONCEPT" : e.type();
    }

    /** id → 实体名（查不到时回退 id，保证路径行可读） */
    private static String nodeName(Map<String, String> namesById, String id) {
        String name = namesById.get(id);
        return name == null || name.isBlank() ? id : name;
    }

    /** id → 实体名（查不到时回退 id，保证关系行可读） */
    private static String nameOf(Map<String, GraphStore.Entity> byId, String id) {
        GraphStore.Entity e = byId.get(id);
        return e == null || e.name() == null || e.name().isBlank() ? id : e.name();
    }

    /** KV 属性 → 单行文本（k=v 以「; 」连接）；无属性返回空串 */
    private static String propsText(List<GraphStore.Prop> props) {
        if (props == null || props.isEmpty()) {
            return "";
        }
        return props.stream().map(p -> p.k() + "=" + p.v()).collect(Collectors.joining("; "));
    }
}
