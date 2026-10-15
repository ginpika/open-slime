package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.NowTool;
import cc.ginpika.minis.ai.core.util.LlmJson;
import cc.ginpika.minis.ai.core.tool.doc.CreateDocumentTool;
import cc.ginpika.minis.ai.core.tool.doc.ReadDocumentTool;
import cc.ginpika.minis.ai.core.tool.doc.SearchDocumentsTool;
import cc.ginpika.minis.ai.core.tool.doc.SearchVectorChunksTool;
import cc.ginpika.minis.ai.core.tool.memory.BackfillMemoryVectorsTool;
import cc.ginpika.minis.ai.core.tool.memory.RecallMemoryTool;
import cc.ginpika.minis.ai.core.tool.graph.DeleteGraphItemTool;
import cc.ginpika.minis.ai.core.tool.graph.EditEntityPropsTool;
import cc.ginpika.minis.ai.core.tool.graph.EditRelationPropsTool;
import cc.ginpika.minis.ai.core.tool.graph.ExploreSubgraphTool;
import cc.ginpika.minis.ai.core.tool.graph.QueryEntityPathTool;
import cc.ginpika.minis.ai.core.tool.graph.QueryEntityRelationsTool;
import cc.ginpika.minis.ai.core.tool.graph.SearchGraphEntitiesTool;
import cc.ginpika.minis.ai.core.tool.graph.UpsertEdgeTool;
import cc.ginpika.minis.ai.core.tool.graph.UpsertNodeTool;
import cc.ginpika.minis.ai.knowledge.anchor.GraphAnchors;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.tool.memory.ForgetMemoryTool;
import cc.ginpika.minis.ai.core.tool.memory.NoteWorkingTool;
import cc.ginpika.minis.ai.core.tool.memory.PublishKnowledgeTool;
import cc.ginpika.minis.ai.core.tool.memory.UpdateMemoryTool;
import cc.ginpika.minis.ai.knowledge.recall.RagRecallRecorder;
import cc.ginpika.minis.ai.knowledge.retrieval.FastSearchService;
import cc.ginpika.minis.ai.knowledge.retrieval.GraphExplorer;
import cc.ginpika.minis.ai.knowledge.retrieval.GraphPathFinder;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.ai.memory.MemoryEditService;
import cc.ginpika.minis.ai.memory.MemoryEmbeddingWriter;
import cc.ginpika.minis.ai.memory.MemoryItemEmbeddingWriter;
import cc.ginpika.minis.ai.memory.MemoryPolicy;
import cc.ginpika.minis.ai.memory.MemoryPublisher;
import cc.ginpika.minis.ai.memory.MemoryStage;
import cc.ginpika.minis.ai.memory.MemoryVocabulary;
import cc.ginpika.minis.ai.memory.ScopeGuard;
import cc.ginpika.minis.ai.memory.WorkingSetService;
import cc.ginpika.minis.ai.memory.recall.MemoryItemRecall;
import cc.ginpika.minis.ai.memory.store.MemoryItemStore;
import cc.ginpika.minis.config.MemoryProps;
import cc.ginpika.minis.ai.memory.recall.MemoryRecall;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 知识库工具执行器：按 OpenAI 风格的工具调用节点分发到具体的 RAG 检索 / 图谱查询 / 图谱写入 /
 * 文档工具实现，返回给模型的 tool 消息内容。
 *
 * <p>由 {@link cc.ginpika.minis.agent.chat.ChatToolDispatcher} 在工具轮中调用（{@link #run(JsonNode)}）；抽为独立组件以便写入侧
 * 复用与单独测试。工具名与参数 schema 见 {@link cc.ginpika.minis.ai.core.tool.AiTool} 各实现类，
 * 是否写库由 {@link ToolManager} 判定。记忆域工具（recall_memory / backfill_memory_vectors）
 * 也在此代发——所有工具面的默认分发都汇到本类，避免各调用点重复布线。
 */
@Component
public class KnowledgeToolbox {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolbox.class);

    /** 图谱实体查找工具单次返回的实体条数上限 */
    private static final int GRAPH_LOOKUP_LIMIT = 10;

    /** 标题查文档工具单次返回的文档条数上限 */
    private static final int DOC_TITLE_LOOKUP_LIMIT = 10;

    /** 图谱最短路径工具返回的路径条数上限 */
    private static final int PATH_TOP_K = 3;

    /** PPR 子图探索：seed 实体数 / BFS 子图节点数 / 返回周边实体数上限 */
    private static final int EXPLORE_SEED_LIMIT = 4;
    private static final int EXPLORE_NODE_LIMIT = 240;
    private static final int EXPLORE_TOP_K = 12;

    /** 实体消歧的 LIKE 候选条数上限 */
    private static final int ENTITY_CANDIDATE_LIMIT = 5;

    /** 实体语义定位阈值：相似度 >=RESOLVE 且领先次名 >=GAP 直接定位；>=CANDIDATE 作为候选交模型确认 */
    private static final float VECTOR_RESOLVE_MIN = 0.55f;
    private static final float VECTOR_GAP_MIN = 0.05f;
    private static final float VECTOR_CANDIDATE_MIN = 0.40f;

    /** now 工具的时间戳格式 */
    private static final DateTimeFormatter NOW_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FastSearchService fast;
    private final GraphStore graph;
    private final GraphPathFinder pathFinder;
    private final ChunkStore chunkStore;
    private final DocumentStore documents;
    private final EmbeddingClient embedding;
    private final ToolManager tools;
    /* 记忆域两件套（ai.memory 与本包平级）：所有工具面的默认分发都汇到这里，就近代发 */
    private final MemoryRecall memoryRecall;
    private final MemoryItemRecall memoryItems;
    private final MemoryEmbeddingWriter memoryVectors;
    /* 记忆写入侧（memory-v6 §9.2）：Agent 主动发布（L4）——落条目 + 共享授权，走轮内缓冲 */
    private final MemoryItemStore memoryItemStore;
    private final MemoryItemEmbeddingWriter memoryItemVectors;
    private final MemoryPublisher memoryPublisher;
    /** 工作集（memory-v6 §13）：L3 工作状态的写入点（note_working 工具） */
    private final WorkingSetService workingSet;
    /** 记忆改写 / 遗忘（memory-v6 §9.1）：update_memory / forget_memory 的执行落点（权限边界在它里面） */
    private final MemoryEditService memoryEdit;
    private final MemoryProps memoryProps;
    /* RAG 召回留痕：三路召回工具调用记一行（查询 + 回给模型的原始数据），供潜意识页回看效果 */
    private final RagRecallRecorder ragRecalls;
    private final cc.ginpika.minis.ai.knowledge.web.WebToolbox web;

    public KnowledgeToolbox(FastSearchService fast, GraphStore graph, GraphPathFinder pathFinder,
                            ChunkStore chunkStore, DocumentStore documents, EmbeddingClient embedding,
                            ToolManager tools, MemoryRecall memoryRecall,
                            MemoryItemRecall memoryItems,
                            MemoryEmbeddingWriter memoryVectors, RagRecallRecorder ragRecalls,
                            MemoryItemStore memoryItemStore, MemoryItemEmbeddingWriter memoryItemVectors,
                            MemoryPublisher memoryPublisher, WorkingSetService workingSet,
                            MemoryEditService memoryEdit, MemoryProps memoryProps,
                            cc.ginpika.minis.ai.knowledge.web.WebToolbox web) {
        this.fast = fast;
        this.graph = graph;
        this.pathFinder = pathFinder;
        this.chunkStore = chunkStore;
        this.documents = documents;
        this.embedding = embedding;
        this.tools = tools;
        this.memoryRecall = memoryRecall;
        this.memoryItems = memoryItems;
        this.memoryVectors = memoryVectors;
        this.ragRecalls = ragRecalls;
        this.memoryItemStore = memoryItemStore;
        this.memoryItemVectors = memoryItemVectors;
        this.memoryPublisher = memoryPublisher;
        this.workingSet = workingSet;
        this.memoryEdit = memoryEdit;
        this.memoryProps = memoryProps;
        this.web = web;
    }

    /** 执行一次 OpenAI 风格的工具调用节点；source 标明来自哪条流（chat / multiagent / heartbeat / plan / agent） */
    public ToolResult run(JsonNode call, String source) {
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("");
        return run(name, args, source);
    }

    /** 执行一次工具调用，返回给模型的 tool 消息内容（按工具名分发）；三路召回工具顺手留痕 */
    public ToolResult run(String name, String argsJson, String source) {
        JsonNode args = LlmJson.readTree(argsJson);
        if (cc.ginpika.minis.ai.knowledge.web.WebToolbox.handles(name)) {
            return new ToolResult(name, argsJson, web.run(name, args), tools.isWriteTool(name));
        }
        String content = switch (name) {
            case SearchVectorChunksTool.NAME -> ragTool(args);
            case SearchGraphEntitiesTool.NAME -> graphSearchTool(args);
            case QueryEntityRelationsTool.NAME -> entityRelationsTool(args);
            case QueryEntityPathTool.NAME -> entityPathTool(args);
            case ExploreSubgraphTool.NAME -> exploreSubgraphTool(args);
            case EditEntityPropsTool.NAME -> editEntityPropsTool(args);
            case EditRelationPropsTool.NAME -> editRelationPropsTool(args);
            case UpsertNodeTool.NAME -> upsertNodeTool(args);
            case UpsertEdgeTool.NAME -> upsertEdgeTool(args);
            case DeleteGraphItemTool.NAME -> deleteGraphTool(args);
            case CreateDocumentTool.NAME -> createDocumentTool(args);
            case SearchDocumentsTool.NAME -> searchDocumentsTool(args);
            case ReadDocumentTool.NAME -> readDocumentTool(args);
            case RecallMemoryTool.NAME -> recallMemoryTool(args);
            case PublishKnowledgeTool.NAME -> publishKnowledgeTool(args);
            case "manage_handoff" -> manageHandoff(args);
            case NoteWorkingTool.NAME -> noteWorkingTool(args);
            case UpdateMemoryTool.NAME -> updateMemoryTool(args);
            case ForgetMemoryTool.NAME -> forgetMemoryTool(args);
            case BackfillMemoryVectorsTool.NAME -> backfillMemoryVectorsTool(args);
            case NowTool.NAME -> nowText();
            default -> "未知工具: " + name;
        };
        /* 三路召回（向量 / 图 / 时序事件）的工具调用留一行：查询与回给模型的原始数据原样记下 */
        ragRecalls.record(name, argsJson, content, source);
        return new ToolResult(name, argsJson, content, tools.isWriteTool(name));
    }

    /** search_vector_chunks：关键词检索知识库 chunk（三路召回后按 RRF 取前若干条），
     *  命中同时附带图谱结构证据（锚点实体 + 子图三元组，纯字符串拼接无 LLM 成本） */
    private String ragTool(JsonNode args) {
        String keywords = args.path("keywords").asText("");
        if (keywords.isBlank()) {
            return "参数缺少 keywords";
        }
        FastSearchService.RagRecall recall = fast.ragRecall(keywords);
        String chunksText = ResultTexts.ragChunksText(recall.chunks());
        String triples = ResultTexts.graphTriplesText(recall.graph());
        return triples.isEmpty() ? chunksText : chunksText + "\n\n" + triples;
    }

    /** search_graph_entities：按实体名/别名关键词模糊查找图谱实体；
     *  字面命中不足时用实体向量语义补足（如「我们公司」→ 星尘科技） */
    private String graphSearchTool(JsonNode args) {
        String keywords = args.path("keywords").asText("");
        if (keywords.isBlank()) {
            return "参数缺少 keywords";
        }
        List<GraphStore.Entity> hits =
                new ArrayList<>(graph.lookup(List.of(keywords.strip().split("\\s+")), GRAPH_LOOKUP_LIMIT));
        Set<String> seen = new HashSet<>();
        for (GraphStore.Entity e : hits) {
            seen.add(e.id());
        }
        List<String> extra = new ArrayList<>();
        for (GraphStore.EntitySimilarity sim : entityVectorRecall(keywords, GRAPH_LOOKUP_LIMIT)) {
            if (seen.add(sim.entityId())) {
                extra.add(sim.entityId());
                if (hits.size() + extra.size() >= GRAPH_LOOKUP_LIMIT) {
                    break;
                }
            }
        }
        if (!extra.isEmpty()) {
            hits.addAll(graph.entitiesByIds(extra));
        }
        return ResultTexts.graphEntitiesText(hits);
    }

    /** query_entity_relations：定位实体并展开其一跳关系（含边的 props 与来源 chunk）；
     *  多候选时返回候选清单让模型消歧，不再静默取首条 */
    private String entityRelationsTool(JsonNode args) {
        String name = args.path("entity").asText("");
        if (name.isBlank()) {
            return "参数缺少 entity";
        }
        Resolution r = resolveEntity(name);
        if (r.candidates() != null) {
            return candidatesText(name, r.candidates());
        }
        if (r.entity() == null) {
            return "（知识图谱中没有找到实体：" + name + "）";
        }
        GraphStore.Entity center = r.entity();
        List<GraphStore.Edge> edges = graph.edgesTouching(List.of(center.id()));
        List<GraphStore.Entity> related = graph.entitiesByIds(graph.neighborsOf(List.of(center.id())));
        return ResultTexts.graphRelationsText(center, edges, related, chunkLabels(edgeChunkIds(edges)));
    }

    /** query_entity_path：定位两端实体并求其间最短路径（最多 PATH_TOP_K 条，按跳数升序），
     *  每步附带该边的 props 与来源 */
    private String entityPathTool(JsonNode args) {
        String source = args.path("source").asText("");
        String target = args.path("target").asText("");
        if (source.isBlank() || target.isBlank()) {
            return "参数缺少 source / target";
        }
        Resolution fromR = resolveEntity(source);
        if (fromR.candidates() != null) {
            return candidatesText(source, fromR.candidates());
        }
        Resolution toR = resolveEntity(target);
        if (toR.candidates() != null) {
            return candidatesText(target, toR.candidates());
        }
        GraphStore.Entity from = fromR.entity();
        GraphStore.Entity to = toR.entity();
        if (from == null || to == null) {
            return "（知识图谱中没有找到实体：" + (from == null ? source : target) + "）";
        }
        if (from.id().equals(to.id())) {
            return "（起点与终点为同一实体：" + from.name() + "）";
        }
        List<GraphPathFinder.Path> paths = pathFinder.shortestPaths(from.id(), to.id(), PATH_TOP_K);
        /* 路径上全部节点 id → 名称，供结果文本渲染出可读的实体名（含未在路径端点出现的中转实体） */
        Map<String, String> names = new LinkedHashMap<>();
        names.put(from.id(), from.name());
        names.put(to.id(), to.name());
        List<String> ids = new ArrayList<>();
        for (GraphPathFinder.Path p : paths) {
            ids.addAll(p.nodeIds());
        }
        for (GraphStore.Entity e : graph.entitiesByIds(ids)) {
            names.put(e.id(), e.name());
        }
        /* 路径触及的边（Step.edgeId 回查）：渲染 props 与来源 */
        Map<String, GraphStore.Edge> edgesById = new LinkedHashMap<>();
        for (GraphStore.Edge e : graph.edgesTouching(ids)) {
            edgesById.put(e.id(), e);
        }
        return ResultTexts.graphPathsText(from, to, paths, names, edgesById,
                chunkLabels(edgeChunkIds(edgesById.values())));
    }

    /** explore_subgraph：seed 实体做 PPR 扩散，一次拿到多跳相关子图 + 证据 chunk
     *  （「张伟和李娜怎么认识的」→ 共同邻居自然浮到高分位，替代多轮工具往返） */
    private String exploreSubgraphTool(JsonNode args) {
        List<String> names = new ArrayList<>();
        for (JsonNode n : args.path("entities")) {
            String s = n.asText("").strip();
            if (!s.isBlank() && !names.contains(s)) {
                names.add(s);
            }
        }
        if (names.isEmpty()) {
            return "参数缺少 entities";
        }
        if (names.size() > EXPLORE_SEED_LIMIT) {
            names = names.subList(0, EXPLORE_SEED_LIMIT);
        }
        Set<String> seedIds = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (String n : names) {
            Resolution r = resolveEntity(n);
            if (r.entity() != null) {
                seedIds.add(r.entity().id());
            } else if (r.candidates() != null) {
                return candidatesText(n, r.candidates());
            } else {
                missing.add(n);
            }
        }
        if (seedIds.isEmpty()) {
            return "（知识图谱中没有找到实体：" + String.join("、", names) + "）";
        }
        GraphExplorer.Exploration exploration = new GraphExplorer(graph)
                .explore(seedIds, EXPLORE_NODE_LIMIT, EXPLORE_TOP_K, ResultTexts.GRAPH_EDGE_LIMIT);
        String text = ResultTexts.subgraphText(exploration, chunkLabels(exploration.chunkIds()));
        if (!missing.isEmpty()) {
            text = "（未找到实体：" + String.join("、", missing) + "，已忽略）\n" + text;
        }
        return text;
    }

    /** edit_entity_props：新增/修改/删除某实体的 KV 属性（底层为覆盖式写入，故先读现有属性再合并写回） */
    private String editEntityPropsTool(JsonNode args) {
        String name = args.path("entity").asText("");
        if (name.isBlank()) {
            return "参数缺少 entity";
        }
        Resolution r = resolveEntity(name);
        if (r.candidates() != null) {
            return candidatesText(name, r.candidates()) + "\n未做任何修改";
        }
        GraphStore.Entity entity = r.entity();
        if (entity == null) {
            return "（知识图谱中没有找到实体：" + name + "，未做任何修改）";
        }
        List<GraphStore.Prop> merged = mergeProps(entity.props(), args.path("props"),
                args.path("remove_keys"));
        if (merged == null) {
            return "参数中未提供 props 或 remove_keys，未做任何修改";
        }
        graph.updateEntityProps(entity.id(), merged);
        return "已更新实体「" + entity.name() + "」的属性：" + propsText(merged);
    }

    /** edit_relation_props：新增/修改/删除某条关系的 KV 属性（关系由 subject + predicate + object 定位） */
    private String editRelationPropsTool(JsonNode args) {
        String subject = args.path("subject").asText("");
        String predicate = args.path("predicate").asText("");
        String object = args.path("object").asText("");
        if (subject.isBlank() || predicate.isBlank() || object.isBlank()) {
            return "参数缺少 subject / predicate / object";
        }
        Resolution sR = resolveEntity(subject);
        if (sR.candidates() != null) {
            return candidatesText(subject, sR.candidates()) + "\n未做任何修改";
        }
        Resolution oR = resolveEntity(object);
        if (oR.candidates() != null) {
            return candidatesText(object, oR.candidates()) + "\n未做任何修改";
        }
        GraphStore.Entity s = sR.entity();
        GraphStore.Entity o = oR.entity();
        if (s == null || o == null) {
            return "（知识图谱中没有找到实体：" + (s == null ? subject : object) + "，未做任何修改）";
        }
        String relationId = GraphStore.relationIdFor(s.id(), predicate, o.id());
        /* 无按三元组直接取边的方法：借两端 id 触及的边过滤出目标关系，以读取其现有 props */
        GraphStore.Edge edge = graph.edgesTouching(List.of(s.id())).stream()
                .filter(e -> relationId.equals(e.id()))
                .findFirst()
                .orElse(null);
        if (edge == null) {
            return "（知识图谱中没有找到关系：" + s.name() + " --" + predicate + "--> " + o.name()
                    + "，未做任何修改）";
        }
        List<GraphStore.Prop> merged = mergeProps(edge.props(), args.path("props"),
                args.path("remove_keys"));
        if (merged == null) {
            return "参数中未提供 props 或 remove_keys，未做任何修改";
        }
        graph.updateRelationProps(relationId, merged);
        /* 关系属性变动同样算两端实体上的知识变动：刷新 updated_at（不动 created_at） */
        graph.touchEntities(List.of(s.id(), o.id()));
        return "已更新关系「" + s.name() + " --" + predicate + "--> " + o.name() + "」的属性："
                + propsText(merged);
    }

    /** upsert_node：写入/更新实体节点。按精确名 upsert——不存在则新建（并尝试向量化），
     *  同名则合并类型/覆盖别名与摘要；另有名称相近的既有实体时附提示，交模型自查是否本应更新而非新建。 */
    private String upsertNodeTool(JsonNode args) {
        String name = args.path("name").asText("").trim();
        if (name.isEmpty()) {
            return "参数缺少 name";
        }
        GraphStore.Entity exist = graph.byName(name);
        /* type 兼容字符串（可逗号/顿号分隔多类型）与字符串数组（同 AnalyzeService 写图） */
        List<String> types = new ArrayList<>();
        JsonNode tnode = args.path("type");
        if (tnode.isArray()) {
            tnode.forEach(t -> types.add(t.asText()));
        } else if (tnode.isTextual()) {
            for (String s : tnode.asText().split("[,，、]")) {
                types.add(s);
            }
        }
        String primary = types.stream().filter(s -> !s.isBlank()).findFirst().orElse("CONCEPT");
        List<String> aliases = new ArrayList<>();
        if (args.path("aliases").isArray()) {
            args.path("aliases").forEach(a -> aliases.add(a.asText()));
        }
        String summary = args.path("summary").asText(null);
        graph.upsertEntity(primary, types, name, aliases, summary);
        embedEntity(GraphStore.entityIdFor(name), name, summary);
        String msg = exist == null
                ? "已新建实体「" + name + "」（type: " + primary + "）"
                : "已更新实体「" + name + "」（类型已并入，主类型保持 " + exist.type()
                        + "；别名/摘要按本次提交覆盖）";
        /* 写入按精确名，不据此拦截；提示相近实体让模型自查撞名/应否合并 */
        List<String> near = graph.lookup(List.of(name), ENTITY_CANDIDATE_LIMIT).stream()
                .filter(e -> !e.name().equals(name))
                .map(GraphStore.Entity::name)
                .toList();
        if (!near.isEmpty()) {
            msg += "\n注意：图谱中已有名称相近的实体（" + String.join("、", near)
                    + "），若本应更新它们而非新建，请改用其完整名称重新调用并向用户说明。";
        }
        return msg;
    }

    /** upsert_edge：写入 实体-关系-实体 边。两端按写入侧规则定位（不做语义猜测），
     *  多候选要求消歧后再写；同一条关系已存在则幂等跳过。 */
    private String upsertEdgeTool(JsonNode args) {
        String subj = args.path("subject").asText("").trim();
        String pred = args.path("predicate").asText("").trim();
        String obj = args.path("object").asText("").trim();
        if (subj.isEmpty() || pred.isEmpty() || obj.isEmpty()) {
            return "参数缺少 subject / predicate / object";
        }
        Resolution sr = resolveEntityForWrite(subj);
        if (sr.candidates() != null) {
            return candidatesText(subj, sr.candidates()) + "\n未做任何修改";
        }
        Resolution or = resolveEntityForWrite(obj);
        if (or.candidates() != null) {
            return candidatesText(obj, or.candidates()) + "\n未做任何修改";
        }
        GraphStore.Entity s = sr.entity();
        GraphStore.Entity o = or.entity();
        int inserted = graph.upsertRelation(s.id(), pred, o.id(), null);
        /* 新挂关系算实体上的知识变动：刷新两端 updated_at（幂等跳过则不动；
         *  自动新建的端点在 resolveEntityForWrite 的 upsert 里已带上 created_at/updated_at） */
        if (inserted == 1) {
            graph.touchEntities(List.of(s.id(), o.id()));
        }
        return (inserted == 1 ? "已新增关系: " : "关系已存在（幂等跳过）: ")
                + s.name() + " --" + pred + "--> " + o.name();
    }

    /** delete_graph_item：删除图谱中的实体（连带其全部关系）或一条关系。按 target 分派；
     *  实体/两端均按读侧规则定位，多候选先消歧、未命中一律「未做任何修改」，不静默删错。 */
    private String deleteGraphTool(JsonNode args) {
        String target = args.path("target").asText("").trim();
        if (target.isEmpty()) {
            return "参数缺少 target（entity=删实体，relation=删关系）";
        }
        return switch (target) {
            case "entity" -> deleteEntityTool(args);
            case "relation" -> deleteRelationTool(args);
            default -> "target 只能是 entity 或 relation，未做任何修改";
        };
    }

    /** 删除实体及其全部关联（GraphStore.deleteEntity 先清关系再清 chunk 关联/向量，事务内完成） */
    private String deleteEntityTool(JsonNode args) {
        String name = args.path("entity").asText("").trim();
        if (name.isEmpty()) {
            return "参数缺少 entity";
        }
        Resolution r = resolveEntity(name);
        if (r.candidates() != null) {
            return candidatesText(name, r.candidates()) + "\n未做任何修改";
        }
        GraphStore.Entity entity = r.entity();
        if (entity == null) {
            return "（知识图谱中没有找到实体：" + name + "，未做任何修改）";
        }
        if (GraphAnchors.isAnchor(entity)) {
            return "「" + entity.name() + "」是系统预置的锚点实体，不可删除（未做任何修改）。"
                    + "锚点承载对世界/用户的长期认知坐标，其上的关系照常可增删改。";
        }
        int relations = graph.deleteEntity(entity.id());
        return "已删除实体「" + entity.name() + "」（连带删除 " + relations + " 条关系，及其文档关联与实体向量）";
    }

    /** 删除一条关系：按 subject + predicate + object 定位（同 edit_relation_props），命中后按稳定 id 删 */
    private String deleteRelationTool(JsonNode args) {
        String subject = args.path("subject").asText("").trim();
        String predicate = args.path("predicate").asText("").trim();
        String object = args.path("object").asText("").trim();
        if (subject.isEmpty() || predicate.isEmpty() || object.isEmpty()) {
            return "参数缺少 subject / predicate / object";
        }
        Resolution sR = resolveEntity(subject);
        if (sR.candidates() != null) {
            return candidatesText(subject, sR.candidates()) + "\n未做任何修改";
        }
        Resolution oR = resolveEntity(object);
        if (oR.candidates() != null) {
            return candidatesText(object, oR.candidates()) + "\n未做任何修改";
        }
        GraphStore.Entity s = sR.entity();
        GraphStore.Entity o = oR.entity();
        if (s == null || o == null) {
            return "（知识图谱中没有找到实体：" + (s == null ? subject : object) + "，未做任何修改）";
        }
        String relationId = GraphStore.relationIdFor(s.id(), predicate, o.id());
        int removed = graph.deleteRelation(relationId);
        if (removed == 0) {
            return "（知识图谱中没有找到关系：" + s.name() + " --" + predicate + "--> " + o.name()
                    + "，未做任何修改）";
        }
        return "已删除关系: " + s.name() + " --" + predicate + "--> " + o.name();
    }

    /** create_document：把一段 markdown 正文新建为知识库文档。走 {@link DocumentStore#create}
     *  （内部文档：source=doc、url 为空，不发布为文章），由 Chunker 按标题/段落自动分块；
     *  新块状态为未分析，但 FTS 已同步，随即可被关键词检索命中。 */
    private String createDocumentTool(JsonNode args) {
        String title = args.path("title").asText("").trim();
        if (title.isEmpty()) {
            return "参数缺少 title";
        }
        String content = args.path("content").asText("").trim();
        if (content.isEmpty()) {
            return "参数缺少 content";
        }
        DocumentStore.CreatedDoc doc = documents.create(title, content);
        return "已新建文档《" + doc.title() + "》（id: " + doc.id() + "，共 " + doc.chunks()
                + " 个分块，已入库可被检索；尚未做知识提取与图谱写入）";
    }

    /** search_documents_by_title：按关键词模糊命中标题查文档（多词 AND，短标题优先），
     *  只回文档清单（标题/来源/摘要），正文片段由 RAG 检索工具负责 */
    private String searchDocumentsTool(JsonNode args) {
        String keywords = args.path("keywords").asText("");
        if (keywords.isBlank()) {
            return "参数缺少 keywords";
        }
        return ResultTexts.documentsText(
                documents.searchByTitle(keywords, DOC_TITLE_LOOKUP_LIMIT));
    }

    /** read_document：按 id 读文档全文后实时分页（page / page_size 由模型给，越界只在渲染层纠正，
     *  不落库也不改文档）；文章文档读 md 原文，内部文档由 chunk 按序拼接 */
    private String readDocumentTool(JsonNode args) {
        long docId = args.path("doc_id").asLong(0);
        if (docId <= 0) {
            return "参数缺少 doc_id";
        }
        String content = documents.fullText(docId);
        if (content == null) {
            return "文档不存在: " + docId;
        }
        return ResultTexts.documentPageText(docId, documents.title(docId), content,
                args.path("page").asInt(1), args.path("page_size").asInt(0));
    }

    /** recall_memory：查记忆事件流（说过/做过的时间线）。query 与窗口内事件向量做余弦
     *  （向量不可用退化为时间倒序）；days 收敛时间窗，kind/actor 过滤类型与主角 */
    private String recallMemoryTool(JsonNode args) {
        String query = args.path("query").asText("").trim();
        if (query.isEmpty()) {
            return "参数缺少 query";
        }
        /* mode=event：下钻原始事件流（证据层，见 MemoryRecall）；缺省读记忆条目（默认面） */
        if ("event".equalsIgnoreCase(args.path("mode").asText("").trim())) {
            return recallMemoryEvents(args, query);
        }
        String scope = blankToNull(args.path("scope").asText(null));
        int limit = clamp(args.path("limit").asInt(8), 1, 30);
        /* memory-v6 §8.6：工具面召回必须走作用域谓词——作用域取执行入口圈定的 MemoryScopeCtx，
           没有作用域时退化为「只读正式画像」，绝不退化为读全量（v5 的 G2 类漏洞修复） */
        MemoryExec exec = ScopeGuard.resolveRead(null);
        String text = MemoryItemRecall.hitsText(memoryItems.recall(exec, query, scope, limit));
        return text.isEmpty() ? "（没有相关的记忆）" : text;
    }

    /**
     * note_working（memory-v6 §13 · L3）：把**临时工作状态**记进工作集，而不是长期记忆。
     *
     * <p>这是「三概念分离」在工具面的落点：临时的结论留在工作状态里，本轮之后照常注入，
     * 会话收尾由 {@link WorkingSetService#distill} 决定有没有值得长期留的部分。
     */
    private String noteWorkingTool(JsonNode args) {
        String body = args.path("body").asText("").trim();
        if (body.isEmpty()) {
            return "参数缺少 body";
        }
        String kind = args.path("kind").asText("").trim();
        String title = args.path("title").asText("").trim();
        MemoryExec exec = ScopeGuard.resolve(null);
        if (exec == null) {
            return "当前执行没有记忆作用域，无法记录工作状态（memory-v6 I1）";
        }
        String[] out = new String[1];
        boolean deferred = MemoryStage.defer(() -> {
            long id = workingSet.record(exec, kind, title, body, null, null);
            out[0] = id > 0
                    ? "已记入本会话工作集（work=" + id + "）"
                    : "未记录：当前执行没有会话 / 任务绑定（工作状态必须可归属）";
        }, memoryProps);
        if (out[0] != null) {
            return out[0];
        }
        return deferred ? "已记入本会话工作集（本轮末提交，下一轮起可见）" : "已记入本会话工作集";
    }

    /**
     * update_memory（memory-v6 §9.1）：改写一条**自己有权**的记忆。
     *
     * <p>权限边界与「按描述定位命中多条时不猜」都在 {@link MemoryEditService} 里；写入同样走轮内缓冲，
     * 于是改写不影响本轮注入、下一轮才生效。
     */
    private String updateMemoryTool(JsonNode args) {
        String body = args.path("new_content").asText("").trim();
        Long itemId = args.path("item_id").isNumber() ? args.path("item_id").asLong() : null;
        String match = blankToNull(args.path("match").asText(null));
        String title = blankToNull(args.path("title").asText(null));
        MemoryExec exec = ScopeGuard.resolve(null);
        if (exec == null) {
            return "当前执行没有记忆作用域，无法改写（memory-v6 I1）";
        }
        String[] out = new String[1];
        boolean deferred = MemoryStage.defer(
                () -> out[0] = memoryEdit.update(exec, itemId, match, title, body).message(),
                memoryProps);
        return out[0] != null ? out[0] : (deferred ? "已登记改写（本轮末提交，下一轮生效）" : "已处理");
    }

    /** forget_memory（memory-v6 §12.5 · Agent 侧）：撤销 + 墓碑，同样受「只能忘自己有权条目」约束 */
    private String forgetMemoryTool(JsonNode args) {
        Long itemId = args.path("item_id").isNumber() ? args.path("item_id").asLong() : null;
        String match = blankToNull(args.path("match").asText(null));
        String reason = blankToNull(args.path("reason").asText(null));
        MemoryExec exec = ScopeGuard.resolve(null);
        if (exec == null) {
            return "当前执行没有记忆作用域，无法遗忘（memory-v6 I1）";
        }
        String[] out = new String[1];
        boolean deferred = MemoryStage.defer(
                () -> out[0] = memoryEdit.forget(exec, itemId, match, reason).message(),
                memoryProps);
        return out[0] != null ? out[0] : (deferred ? "已登记遗忘（本轮末提交）" : "已处理");
    }

    /**
     * publish_knowledge（memory-v6 §9.2 · L4）：把一条结论发布出去。
     *
     * <p>落点两步：① 新建一条 {@code layer=L4} 的条目（归属由当前作用域派生：有项目归项目，否则归本 Agent）；
     * ② 写一条共享授权（发布动作即是来源凭据，满足 I6）。带 {@code handoff=true} 时再记一条
     * {@code offered} 交接，对方在收件箱里 accept / done 跟进。
     *
     * <p>写入走 {@link MemoryStage}：本轮发布不影响本轮注入，下一轮才被对方发现（§4.4）。
     */
    private String publishKnowledgeTool(JsonNode args) {
        String content = args.path("content").asText("").trim();
        if (content.isEmpty()) {
            return "参数缺少 content";
        }
        String toKind = args.path("to_kind").asText("").trim().toLowerCase();
        switch (toKind) {
            case "agent", "project", "session", "user" -> {
            }
            default -> {
                return "参数 to_kind 非法（agent|project|session|user）：" + toKind;
            }
        }
        MemoryExec exec = ScopeGuard.resolve(null);
        if (exec == null) {
            return "当前执行没有记忆作用域，无法发布（memory-v6 I1）";
        }
        String toRef = publishTarget(toKind, args.path("to_ref").asText("").trim(), exec);
        if (toRef == null && !"user".equals(toKind)) {
            return "缺少接收方：" + toKind + "（当前执行也没有可用的默认目标）";
        }
        String title = args.path("title").asText("").trim();
        String note = args.path("note").asText("");
        boolean handoff = args.path("handoff").asBoolean(false);
        String[] result = new String[1];
        boolean deferred = MemoryStage.defer(() -> {
            long[] ids = memoryPublisher.publishNew(exec, content, title, toKind, toRef, note, handoff);
            result[0] = "已发布到 " + toKind + ":" + toRef + "（share=" + ids[0] + ")";

        }, memoryProps);
        if (result[0] != null) {
            return result[0];
        }
        return deferred ? "已登记发布（本轮末提交；接收方下一轮可见）：" + content
                : "已发布：" + content;
    }

    /** 接收方缺省目标：agent=自己、project/session=当前、user=不填（全用户可见） */
    private static String publishTarget(String toKind, String toRef, MemoryExec exec) {
        if (toRef != null && !toRef.isBlank()) {
            return toRef;
        }
        return switch (toKind) {
            case "agent" -> exec.agentOrBuiltin();
            case "project" -> exec.projectId();
            case "session" -> exec.sessionId();
            default -> null;
        };
    }

    private String manageHandoff(JsonNode args) {
        MemoryExec exec = ScopeGuard.resolve(null);
        if (exec == null) { return "无记忆执行作用域"; }
        String action = args.path("action").asText("list");
        if ("list".equals(action)) {
            return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(java.util.stream.Stream.concat(memoryPublisher.inbox(exec, 50).stream(),
                    memoryPublisher.inbox(exec, 50, "accepted").stream()).toList()).toString();
        }
        memoryPublisher.move(exec, args.path("handoff_id").asLong(), action);
        return "交接状态已更新为 " + action;
    }

    /** mode=event：原始事件流召回（证据层下钻 / 审计用，见 {@link MemoryRecall}） */
    private String recallMemoryEvents(JsonNode args, String query) {
        Long since = null;
        if (args.path("days").isNumber()) {
            since = System.currentTimeMillis()
                    - (long) (args.path("days").asDouble() * 86_400_000L);
        }
        List<MemoryRecall.Hit> hits = memoryRecall.recall(ScopeGuard.resolveRead(null), query, since, null,
                blankToNull(args.path("kind").asText(null)),
                blankToNull(args.path("actor").asText(null)),
                clamp(args.path("limit").asInt(8), 1, 30));
        String text = MemoryRecall.hitsText(hits);
        return text.isEmpty() ? "（记忆事件流中没有匹配的记录）" : text;
    }

    /** backfill_memory_vectors：为缺向量的记忆事件补 embedding（向量轴维护，见 MemoryEmbeddingWriter） */
    private String backfillMemoryVectorsTool(JsonNode args) {
        if (!embedding.configured()) {
            return "embedding 未配置，无法补齐记忆向量（请先在设置页配置 embedding 端点）";
        }
        int done = memoryVectors.backfill(clamp(args.path("limit").asInt(16), 1,
                MemoryEmbeddingWriter.BACKFILL_MAX));
        int itemDone = memoryItemVectors.backfill(clamp(args.path("limit").asInt(16), 1, 64));
        return "已补齐事件向量 " + done + " 条、记忆条目向量 " + itemDone + " 条";
    }

    /** now：当前本地日期时间（yyyy-MM-dd HH:mm:ss）——运行时素材不再注入，由模型按需现取 */
    private static String nowText() {
        return LocalDateTime.now().format(NOW_STAMP);
    }

    /** 空白串归一为 null（过滤条件的「缺省不限」）；数值夹取到 [min, max] */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 写入侧实体定位（比读取侧 {@link #resolveEntity} 更保守，不做向量语义猜测）：
     *  精确名 → 别名精确 → 唯一 LIKE 命中则复用；多条 LIKE → 候选交模型消歧；全不中 → 按字面名新建 CONCEPT 节点。 */
    private Resolution resolveEntityForWrite(String name) {
        GraphStore.Entity hit = graph.byName(name);
        if (hit != null) {
            return Resolution.of(hit);
        }
        List<GraphStore.Entity> hits = graph.lookup(List.of(name), ENTITY_CANDIDATE_LIMIT);
        for (GraphStore.Entity e : hits) {
            if (aliasExact(e.aliases(), name)) {
                return Resolution.of(e);
            }
        }
        if (hits.size() == 1) {
            return Resolution.of(hits.get(0));
        }
        if (hits.size() > 1) {
            return Resolution.ambiguous(hits);
        }
        graph.upsertEntity("CONCEPT", name, List.of(), null);
        embedEntity(GraphStore.entityIdFor(name), name, null);
        return Resolution.of(graph.byName(name));
    }

    /** 实体向量化入库（供后续语义召回）；embedding 未配置/失败静默跳过——写图不因向量服务抖动而失败 */
    private void embedEntity(String id, String name, String summary) {
        if (!embedding.configured()) {
            return;
        }
        try {
            embedding.embed(summary == null || summary.isBlank() ? name : name + "\n" + summary)
                    .ifPresent(vec -> graph.saveEntityEmbedding(id, embedding.model(), vec));
        } catch (RuntimeException e) {
            log.warn("实体向量化失败（跳过）: {}", e.getMessage());
        }
    }

    /** 实体定位结果：entity=唯一命中；candidates=多个候选（交模型/用户消歧）；两者皆空=未找到 */
    private record Resolution(GraphStore.Entity entity, List<GraphStore.Entity> candidates) {
        static Resolution of(GraphStore.Entity e) {
            return new Resolution(e, null);
        }

        static Resolution ambiguous(List<GraphStore.Entity> candidates) {
            return new Resolution(null, candidates);
        }
    }

    /**
     * 定位实体（不静默猜错）：
     * ① 精确名 → ② 别名精确命中 → ③ LIKE 唯一命中 → ④ LIKE 多条 → 返回候选清单交模型消歧；
     * ⑤ 字面全不中 → 实体向量语义回退（相似度过线直接定位，弱命中作为候选确认，否则未找到）。
     */
    private Resolution resolveEntity(String name) {
        GraphStore.Entity hit = graph.byName(name);
        if (hit != null) {
            return Resolution.of(hit);
        }
        List<GraphStore.Entity> hits = graph.lookup(List.of(name), ENTITY_CANDIDATE_LIMIT);
        for (GraphStore.Entity e : hits) {
            if (aliasExact(e.aliases(), name)) {
                return Resolution.of(e);
            }
        }
        if (hits.size() == 1) {
            return Resolution.of(hits.get(0));
        }
        if (hits.size() > 1) {
            return Resolution.ambiguous(hits);
        }
        /* 字面不匹配（如「我们公司」）→ 语义回退 */
        List<GraphStore.EntitySimilarity> sims = entityVectorRecall(name, 3);
        if (sims.isEmpty() || sims.get(0).sim() < VECTOR_CANDIDATE_MIN) {
            return new Resolution(null, null);
        }
        /* 高分且与次名拉开差距才直接定位；否则作为候选清单交模型确认 */
        float top1 = sims.get(0).sim();
        float top2 = sims.size() > 1 ? sims.get(1).sim() : 0f;
        if (top1 >= VECTOR_RESOLVE_MIN && top1 - top2 >= VECTOR_GAP_MIN) {
            GraphStore.Entity e = graph.entitiesByIds(List.of(sims.get(0).entityId()))
                    .stream().findFirst().orElse(null);
            return e == null ? new Resolution(null, null) : Resolution.of(e);
        }
        List<GraphStore.Entity> candidates = graph.entitiesByIds(sims.stream()
                .filter(s -> s.sim() >= VECTOR_CANDIDATE_MIN)
                .map(GraphStore.EntitySimilarity::entityId)
                .toList());
        return candidates.isEmpty() ? new Resolution(null, null) : Resolution.ambiguous(candidates);
    }

    /** 别名 JSON 数组中是否精确含 name */
    private boolean aliasExact(String aliasesJson, String name) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return false;
        }
        for (JsonNode a : LlmJson.readTree(aliasesJson)) {
            if (name.equals(a.asText())) {
                return true;
            }
        }
        return false;
    }

    /** 实体向量召回（读 entity_embedding 余弦 top-k）；embedding 失败/未配置降级为空，
     *  消歧与查找不因向量服务抖动而硬失败 */
    private List<GraphStore.EntitySimilarity> entityVectorRecall(String text, int limit) {
        if (!embedding.configured()) {
            return List.of();
        }
        try {
            return graph.entitiesByCosine(embedding.model(), embedding.embed(text).orElse(null), limit);
        } catch (RuntimeException e) {
            log.warn("实体向量召回失败（降级为字面匹配）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 多候选消歧文本：列候选让模型确认——一次反问胜过基于错误实体推理三轮 */
    private String candidatesText(String name, List<GraphStore.Entity> candidates) {
        return "实体「" + name + "」匹配到多个候选，请结合上下文确认想要的是哪一个"
                + "（用完整实体名重新调用，或向用户澄清）:\n"
                + ResultTexts.graphEntitiesText(candidates);
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

    /** chunkId → 可读溯源标签《标题》（ref: …），图谱关系/路径渲染来源用 */
    private Map<Long, String> chunkLabels(Collection<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (ChunkStore.Row r : chunkStore.rowsByIds(ids)) {
            String title = r.title() == null || r.title().isBlank() ? "chunk " + r.id() : r.title();
            String label = "《" + title + "》";
            if (r.sourceRef() != null && !r.sourceRef().isBlank()) {
                label += "（ref: " + r.sourceRef() + "）";
            }
            out.put(r.id(), label);
        }
        return out;
    }

    /**
     * 按「props 新增/覆盖 + remove_keys 删除」合并现有 KV 属性（保持原顺序，新属性追加在末尾）；
     * 两者均未提供时返回 null，调用方据此判定无需写库。props 项缺 t 时沿用原属性类型（新属性默认 text）。
     */
    private static List<GraphStore.Prop> mergeProps(List<GraphStore.Prop> current, JsonNode props,
                                                    JsonNode removeKeys) {
        boolean hasProps = props.isArray() && !props.isEmpty();
        boolean hasRemovals = removeKeys.isArray() && !removeKeys.isEmpty();
        if (!hasProps && !hasRemovals) {
            return null;
        }
        Map<String, GraphStore.Prop> merged = new LinkedHashMap<>();
        for (GraphStore.Prop p : current) {
            merged.put(p.k(), p);
        }
        if (hasProps) {
            for (JsonNode p : props) {
                String k = p.path("k").asText("");
                if (k.isBlank()) {
                    continue;
                }
                String t = p.path("t").asText("");
                if (t.isBlank()) {
                    GraphStore.Prop old = merged.get(k);
                    t = old != null && old.t() != null ? old.t() : "text";
                }
                merged.put(k, new GraphStore.Prop(k, p.path("v").asText(""), t));
            }
        }
        if (hasRemovals) {
            for (JsonNode k : removeKeys) {
                merged.remove(k.asText(""));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** KV 属性 → 单行文本（k=v 以「; 」连接）；无属性返回「（无）」 */
    private static String propsText(List<GraphStore.Prop> props) {
        if (props == null || props.isEmpty()) {
            return "（无）";
        }
        return props.stream().map(p -> p.k() + "=" + p.v()).collect(Collectors.joining("; "));
    }
}
