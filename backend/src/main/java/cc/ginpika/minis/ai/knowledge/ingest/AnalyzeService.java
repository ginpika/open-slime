package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.AnalyzeBlock;
import cc.ginpika.minis.ai.core.prompt.block.ChunkSummaryBlock;
import cc.ginpika.minis.ai.core.prompt.block.CommitBlock;
import cc.ginpika.minis.ai.core.prompt.block.QuestionsBlock;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.analyze.SubmitTriplesTool;
import cc.ginpika.minis.ai.core.tool.graph.UpsertEdgeTool;
import cc.ginpika.minis.ai.core.tool.graph.UpsertNodeTool;
import cc.ginpika.minis.ai.core.util.LlmJson;
import cc.ginpika.minis.ai.llm.EmbeddingClient;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.dto.AnalyzeDtos;
import cc.ginpika.minis.ai.knowledge.dto.AnalyzeDtos.Triple;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档分析：LLM 抽取三元组（analyze）+ function calling 多轮循环入库/向量化（commit）。
 * 入库执行在后端（upsert_node / upsert_edge），LLM 只负责编排工具调用。
 */
@Service
public class AnalyzeService {

    private final OpenAiChatClient chat;
    private final GraphStore graph;
    private final ChunkStore chunks;
    private final EmbeddingClient embedding;
    private final DocumentStore documents;
    private final DocSummaryService docSummary;
    private final ObjectMapper om;
    private final PromptManager prompts;
    private final ToolManager tools;

    public AnalyzeService(OpenAiChatClient chat, GraphStore graph, ChunkStore chunks,
                          EmbeddingClient embedding, DocumentStore documents,
                          DocSummaryService docSummary, ObjectMapper om,
                          PromptManager prompts, ToolManager tools) {
        this.chat = chat;
        this.graph = graph;
        this.chunks = chunks;
        this.embedding = embedding;
        this.documents = documents;
        this.docSummary = docSummary;
        this.om = om;
        this.prompts = prompts;
        this.tools = tools;
    }

    /** 分析文档：LLM 抽取三元组（未走工具时降级解析 content 文本中的 JSON） */
    public AnalyzeDtos.AnalyzeResponse analyze(String content) {
        requireChat();
        ObjectNode body = om.createObjectNode();
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompts.resolve(AnalyzeBlock.KEY));
        messages.addObject().put("role", "user").put("content", content);
        body.set("tools", tools.toolsOf(ToolSurface.EXTRACT));

        OpenAiChatClient.RawResult raw = chat.chatRaw(body, TokenUsageService.SOURCE_ANALYZE);
        JsonNode message = raw.message();

        List<Triple> triples = new ArrayList<>();
        String summary = null;
        boolean viaFc = false;
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                if (!SubmitTriplesTool.NAME.equals(tc.path("function").path("name").asText())) {
                    continue;
                }
                JsonNode args = LlmJson.readTree(tc.path("function").path("arguments").asText(""));
                for (JsonNode t : args.path("triples")) {
                    addTriple(triples, t);
                }
                if (args.hasNonNull("summary")) {
                    summary = args.get("summary").asText();
                }
                viaFc = true;
            }
        }
        if (!viaFc) {
            ParsedText parsed = parseTriplesFromText(message.path("content").asText(""));
            triples.addAll(parsed.triples());
            summary = parsed.summary();
        }
        return new AnalyzeDtos.AnalyzeResponse(dedupe(triples), summary, viaFc,
                chat.model());
    }

    /** 确认入库：三元组经 LLM function calling（upsert_node/upsert_edge）写入图谱并尝试向量化 */
    public AnalyzeDtos.CommitResult commit(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            throw new IllegalArgumentException("triples 为空");
        }
        requireChat();

        List<AnalyzeDtos.ToolCallLog> logs = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Set<String> edgeIds = new LinkedHashSet<>();
        boolean[] vectorized = {false};

        ArrayNode messages = om.createArrayNode();
        messages.addObject().put("role", "system").put("content", prompts.resolve(CommitBlock.KEY));
        StringBuilder sb = new StringBuilder("请把以下三元组写入知识图谱：\n");
        for (int i = 0; i < triples.size(); i++) {
            Triple t = triples.get(i);
            sb.append(i + 1).append(". ").append(t.subject()).append(" | ")
                    .append(t.predicate()).append(" | ").append(t.object()).append('\n');
        }
        messages.addObject().put("role", "user").put("content", sb.toString());

        String finish = null;
        for (int round = 1; ; round++) {
            if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException("知识入库已停止"); }
            ObjectNode body = om.createObjectNode();
            body.set("messages", messages);
            body.set("tools", tools.toolsOf(ToolSurface.COMMIT));
            OpenAiChatClient.RawResult raw = chat.chatRaw(body, TokenUsageService.SOURCE_COMMIT);
            JsonNode message = raw.message();

            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                finish = message.path("content").asText(null);
                break;
            }
            // assistant 的 tool_calls 消息回填历史
            ObjectNode assistant = messages.addObject();
            assistant.put("role", "assistant");
            assistant.set("tool_calls", toolCalls.deepCopy());
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                assistant.put("content", content.asText());
            } else {
                assistant.putNull("content");
            }
            // 逐个执行工具并把结果回传
            for (JsonNode tc : toolCalls) {
                String name = tc.path("function").path("name").asText();
                String args = tc.path("function").path("arguments").asText("");
                boolean ok = true;
                String result;
                try {
                    result = executeTool(name, args, nodeIds, edgeIds, vectorized);
                } catch (Exception e) {
                    ok = false;
                    result = "失败: " + e.getMessage();
                }
                logs.add(new AnalyzeDtos.ToolCallLog(round, name, args, result, ok));
                messages.addObject().put("role", "tool")
                        .put("tool_call_id", tc.path("id").asText())
                        .put("content", result);
            }
        }
        return new AnalyzeDtos.CommitResult(nodeIds.size(), edgeIds.size(), vectorized[0],
                logs, finish);
    }

    // ---------- 私有 ----------

    /**
     * 三元组消歧评估（步骤 2）：每条三元组返回两端实体的存在性（精确命中 + LIKE 模糊候选），
     * 两端都存在时附带两实体间已有的关系（无向），供前端判重与图预览。
     */
    public AnalyzeDtos.DisambiguateResult disambiguate(List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            throw new IllegalArgumentException("triples 为空");
        }
        List<Triple> clean = triples.stream()
                .filter(t -> t != null && !isBlank(t.subject()) && !isBlank(t.predicate())
                        && !isBlank(t.object()))
                .toList();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("triples 为空");
        }
        // 去重实体名 → 精确命中（byName）
        Set<String> names = new LinkedHashSet<>();
        for (Triple t : clean) {
            names.add(t.subject().trim());
            names.add(t.object().trim());
        }
        Map<String, GraphStore.Entity> exact = new java.util.HashMap<>();
        for (String n : names) {
            GraphStore.Entity e = graph.byName(n);
            if (e != null) {
                exact.put(n, e);
            }
        }
        // LIKE 模糊候选（name/aliases），排除精确命中的同 id
        Set<String> exactIds = exact.values().stream().map(GraphStore.Entity::id)
                .collect(Collectors.toSet());
        Map<String, List<AnalyzeDtos.EntityLite>> candidates = new java.util.HashMap<>();
        for (GraphStore.Entity e : graph.lookup(List.copyOf(names), 50)) {
            if (exactIds.contains(e.id())) {
                continue;
            }
            candidates.computeIfAbsent(nearestName(names, e), k -> new ArrayList<>())
                    .add(new AnalyzeDtos.EntityLite(e.id(), e.name(), e.type()));
        }
        // 两端都存在的三元组 → 两实体间已有关系（无向）
        Map<String, List<AnalyzeDtos.EdgeLite>> edgesByPair = new java.util.HashMap<>();
        for (GraphStore.Edge edge : graph.edgesTouching(exactIds)) {
            edgesByPair.computeIfAbsent(pairKey(edge.subjectId(), edge.objectId()),
                            k -> new ArrayList<>())
                    .add(toEdgeLite(edge));
        }
        List<AnalyzeDtos.TripleEval> evals = new ArrayList<>();
        for (Triple t : clean) {
            String s = t.subject().trim();
            String o = t.object().trim();
            GraphStore.Entity se = exact.get(s);
            GraphStore.Entity oe = exact.get(o);
            List<AnalyzeDtos.EdgeLite> edges = se != null && oe != null
                    ? edgesByPair.getOrDefault(pairKey(se.id(), oe.id()), List.of())
                    : List.of();
            evals.add(new AnalyzeDtos.TripleEval(
                    toLookup(s, se, candidates.getOrDefault(s, List.of())),
                    toLookup(o, oe, candidates.getOrDefault(o, List.of())),
                    edges));
        }
        return new AnalyzeDtos.DisambiguateResult(evals);
    }

    /**
     * 提交勾选的三元组（步骤 2「提交」）：事务内直接写入——
     * 已有实体保留原属性（只建缺失的），实体两端 link 到 chunk（chunk_entity），
     * 关系带 source_chunk 落库（重复忽略并计数），chunk 状态置 enabled，新实体尝试向量化。
     */
    @Transactional
    public AnalyzeDtos.ChunkCommitResult commitChunk(long chunkId, List<Triple> triples) {
        if (triples == null || triples.isEmpty()) {
            throw new IllegalArgumentException("triples 为空");
        }
        if (chunks.rowsByIds(List.of(chunkId)).isEmpty()) {
            throw new IllegalArgumentException("chunk 不存在: " + chunkId);
        }
        int[] nodes = {0};
        int edges = 0;
        int duplicates = 0;
        int[] propsWritten = {0};
        boolean[] vectorized = {false};
        List<Triple> written = new ArrayList<>();
        for (Triple t : triples) {
            if (t == null || isBlank(t.subject()) || isBlank(t.predicate()) || isBlank(t.object())) {
                continue;
            }
            written.add(t);
            String sid = ensureEntity(t.subject().trim(), t.subjectType(), t.subjectAliases(),
                    nodes, vectorized);
            String oid = ensureEntity(t.object().trim(), t.objectType(), t.objectAliases(),
                    nodes, vectorized);
            propsWritten[0] += mergeProps(sid, t.subjectProps());
            propsWritten[0] += mergeProps(oid, t.objectProps());
            graph.linkEntityChunk(chunkId, sid);
            graph.linkEntityChunk(chunkId, oid);
            if (graph.upsertRelation(sid, t.predicate().trim(), oid, chunkId) > 0) {
                edges++;
            } else {
                duplicates++;
            }
        }
        // 留存提取记录：本次通过该 chunk 更新的实体/关系三元组清单（重复提交覆盖为最近一次）
        try {
            graph.saveChunkExtraction(chunkId, om.writeValueAsString(written));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("提取记录序列化失败", e);
        }
        return new AnalyzeDtos.ChunkCommitResult(nodes[0], edges, duplicates, propsWritten[0],
                vectorized[0]);
    }

    /** 读取 chunk 最近一次提交的知识提取记录（无记录或数据损坏时返回 null），供再次进入步骤 2 时回显 */
    public AnalyzeDtos.ChunkExtractionView chunkExtraction(long chunkId) {
        GraphStore.ChunkExtraction rec = graph.chunkExtraction(chunkId);
        if (rec == null) {
            return null;
        }
        try {
            Triple[] arr = om.readValue(rec.triples(), Triple[].class);
            return new AnalyzeDtos.ChunkExtractionView(chunkId, rec.createdAt(), List.of(arr));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 假设性问题生成（步骤 3 最终提交预案编辑用）：LLM 生成 5 个该段落能解答的问题。
     * 只读不落库（父文档概述缺失时先按全文补生成并落库复用）。
     */
    public AnalyzeDtos.QuestionsResult genQuestions(long chunkId) {
        ChunkStore.Row row = chunks.rowsByIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + chunkId));
        requireChat();
        String content = chat.chat(List.of(
                new ChatMessage("system", prompts.resolve(QuestionsBlock.KEY)),
                new ChatMessage("user", chunkContext(row))), TokenUsageService.SOURCE_CHUNK_QUESTIONS)
                .content();
        List<String> questions = parseQuestions(content);
        if (questions.isEmpty()) {
            throw new IllegalStateException("LLM 未返回有效的假设性问题（可重试）");
        }
        return new AnalyzeDtos.QuestionsResult(chunkId, questions);
    }

    /**
     * 段落摘要生成（步骤 3 Chunk Header 语义定位用）：LLM 针对 <chunk> 段落本身生成一句话摘要。
     * 只读不落库（父文档概述缺失时先按全文补生成并落库复用）。
     */
    public AnalyzeDtos.ChunkSummaryResult summarizeChunk(long chunkId) {
        ChunkStore.Row row = chunks.rowsByIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + chunkId));
        requireChat();
        String content = chat.chat(List.of(
                new ChatMessage("system", prompts.resolve(ChunkSummaryBlock.KEY)),
                new ChatMessage("user", chunkContext(row))), TokenUsageService.SOURCE_CHUNK_SUMMARY)
                .content();
        String summary = cleanSummary(content);
        if (summary.isEmpty()) {
            throw new IllegalStateException("LLM 未返回有效的段落摘要（可重试）");
        }
        return new AnalyzeDtos.ChunkSummaryResult(chunkId, summary);
    }

    /** 摘要清洗：压成单行、去首尾空白与成对引号（模型可能包裹引号或换行） */
    private static String cleanSummary(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replaceAll("\\s*\\n+\\s*", " ");
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("「") && t.endsWith("」")))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /**
     * 保存最终提交预案留档（步骤 3）：header/questions 覆盖式写入 chunks 行，供二次进入页面自动回显；
     * null = 不更新该字段，空白 = 清空。
     */
    public void savePlan(long chunkId, String header, String questions) {
        ChunkStore.Row row = chunks.rowsByIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + chunkId));
        String h = header == null ? row.header() : (header.isBlank() ? null : header);
        String q = questions == null ? row.questions() : (questions.isBlank() ? null : questions);
        chunks.updatePlan(chunkId, h, q);
    }

    /**
     * 提交 Chunk（步骤 3 收尾，知识入库流程终点）：
     * ① 留档用户改后的预案分段（header/questions，同 savePlan 语义）；
     * ② 最终提交预案全文做 embedding 写入 chunk_embedding（未配置 embedding 则跳过）；
     * ③ chunk 置 enabled（检索/召回启用，分析生命周期完成）。
     */
    public AnalyzeDtos.ChunkSubmitResult submitChunk(long chunkId, String text,
                                                     String header, String questions) {
        chunks.rowsByIds(List.of(chunkId)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + chunkId));
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 为空");
        }
        savePlan(chunkId, header, questions);
        boolean vectorized = false;
        String model = null;
        int dim = 0;
        if (embedding.configured()) {
            Optional<float[]> vec = embedding.embed(text);
            if (vec.isPresent()) {
                chunks.saveEmbedding(chunkId, embedding.model(), vec.get());
                vectorized = true;
                model = embedding.model();
                dim = vec.get().length;
            }
        }
        chunks.updateStatus(chunkId, DocumentStore.STATUS_ENABLED);
        return new AnalyzeDtos.ChunkSubmitResult(vectorized, model, dim);
    }

    /** 组装上下文补完串：父文档标题 + 类型 + 全文概述（<document> + <chunk> 包裹；概述缺失时 LLM 补生成并落库复用） */
    private String chunkContext(ChunkStore.Row row) {
        String docTitle = documents.title(row.docId());
        String abstractText = documents.contextualizing(row.docId());
        if (abstractText == null || abstractText.isBlank()) {
            DocSummaryService.Summary summary = docSummary.summarize(documents.fullText(row.docId()));
            abstractText = summary.contextualizing();
            documents.updateContextualizing(row.docId(), abstractText);
            if (summary.docType() != null) {
                documents.updateDocType(row.docId(), summary.docType());
            }
        }
        // 文档类型头串（摘要生成时 LLM 判定）：供提示词按类型给出差异化指引；未判定（旧数据）标 UNKNOWN
        String docType = documents.docType(row.docId());
        return chunkContextText(docTitle, docType, abstractText, row.content());
    }

    /** 组装 chunk 上下文补完串：父文档标题 + 类型 + 全文概述（<document> + <chunk> 包裹） */
    private static String chunkContextText(String docTitle, String docType, String abstractText,
                                           String content) {
        return "<document>\n<title>" + (docTitle == null ? "" : docTitle)
                + "</title>\n<docType>"
                + (docType == null || docType.isBlank() ? "UNKNOWN" : docType)
                + "</docType>\n<abstract>" + abstractText + "</abstract>\n</document>\n\n<chunk>\n"
                + content + "\n</chunk>";
    }

    /**
     * 合并实体 KV 属性：保留已有（同键以 LLM 新值覆盖），无变化则跳过写库。
     * 返回本次实际写入的属性条数。
     */
    private int mergeProps(String entityId, List<GraphStore.Prop> add) {
        if (add == null || add.isEmpty()) {
            return 0;
        }
        List<GraphStore.Prop> cur = graph.entitiesByIds(List.of(entityId)).stream()
                .findFirst().map(GraphStore.Entity::props).orElse(List.of());
        java.util.Map<String, GraphStore.Prop> merged = new java.util.LinkedHashMap<>();
        for (GraphStore.Prop p : cur) {
            merged.put(p.k(), p);
        }
        for (GraphStore.Prop p : add) {
            merged.put(p.k(), p);
        }
        if (merged.size() == cur.size() && add.stream().allMatch(cur::contains)) {
            return 0;
        }
        graph.updateEntityProps(entityId, List.copyOf(merged.values()));
        return merged.size();
    }

    /** 实体存在则复用（保留主类型与属性，新识别的类型/别名并入）；不存在则按 LLM 给的类型
     *  建节点（缺省 CONCEPT）并尝试向量化。返回实体 id。 */
    private String ensureEntity(String name, String type, List<String> aliases, int[] nodes,
                                boolean[] vectorized) {
        GraphStore.Entity e = graph.byName(name);
        if (e != null) {
            if (!isBlank(type)) {
                graph.mergeEntityTypes(name, List.of(type));
            }
            graph.mergeEntityAliases(name, aliases);
            return e.id();
        }
        graph.upsertEntity(isBlank(type) ? "CONCEPT" : type, name, aliases, null);
        nodes[0]++;
        String id = graph.byName(name).id();
        if (embedding.configured()) {
            Optional<float[]> vec = embedding.embed(name);
            if (vec.isPresent()) {
                graph.saveEntityEmbedding(id, embedding.model(), vec.get());
                vectorized[0] = true;
            }
        }
        return id;
    }

    /** 候选归属：LIKE 匹配到的实体归到哪个查询名（最长包含匹配优先） */
    private static String nearestName(Set<String> names, GraphStore.Entity e) {
        String best = null;
        for (String n : names) {
            if (e.name().contains(n) || containsAlias(e.aliases(), n)) {
                if (best == null || n.length() > best.length()) {
                    best = n;
                }
            }
        }
        return best != null ? best : names.iterator().next();
    }

    private static boolean containsAlias(String aliasesJson, String name) {
        return aliasesJson != null && aliasesJson.contains(name);
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "\u0000" + b : b + "\u0000" + a;
    }

    private static AnalyzeDtos.EntityLookup toLookup(String name, GraphStore.Entity exact,
                                                     List<AnalyzeDtos.EntityLite> candidates) {
        return new AnalyzeDtos.EntityLookup(name,
                exact == null ? null : new AnalyzeDtos.EntityLite(exact.id(), exact.name(), exact.type()),
                candidates);
    }

    private AnalyzeDtos.EdgeLite toEdgeLite(GraphStore.Edge edge) {
        // 边端点 id → 名（edgesTouching 已保证端点都在本次查询的实体集合中，但补个兜底）
        Map<String, String> nameById = graph.entitiesByIds(
                        List.of(edge.subjectId(), edge.objectId())).stream()
                .collect(Collectors.toMap(GraphStore.Entity::id, GraphStore.Entity::name));
        return new AnalyzeDtos.EdgeLite(edge.id(), edge.predicate(), edge.subjectId(), edge.objectId(),
                nameById.getOrDefault(edge.subjectId(), edge.subjectId()),
                nameById.getOrDefault(edge.objectId(), edge.objectId()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void requireChat() {
        if (!chat.configured()) {
            throw new IllegalStateException("LLM 未配置（minis.llm.base-url / api-key / chat-model）");
        }
    }

    private void addTriple(List<Triple> out, JsonNode n) {
        String s = firstText(n, "subject", "head", "from");
        String p = firstText(n, "predicate", "relation", "rel");
        String o = firstText(n, "object", "tail", "to");
        if (!s.isBlank() && !p.isBlank() && !o.isBlank()) {
            out.add(new Triple(s, p, o,
                    blankToNull(firstText(n, "subject_type", "subjectType")),
                    blankToNull(firstText(n, "object_type", "objectType")),
                    readProps(n, "subject_props"), readProps(n, "object_props"),
                    readStringList(n, "subject_aliases"), readStringList(n, "object_aliases")));
        }
    }

    /** 空串归一为 null（类型缺省由 ensureEntity 兜底 CONCEPT） */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 解析一端实体的 KV 属性数组：k/v 非空过滤、t 白名单（非法回退 text） */
    private List<GraphStore.Prop> readProps(JsonNode triple, String field) {
        JsonNode arr = triple.get(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<GraphStore.Prop> out = new ArrayList<>();
        for (JsonNode p : arr) {
            String k = p.path("k").asText("").trim();
            String v = p.path("v").asText("").trim();
            if (k.isEmpty() || v.isEmpty()) {
                continue;
            }
            String t = p.path("t").asText("text").trim();
            out.add(new GraphStore.Prop(k, v,
                    Set.of("text", "number", "url", "date", "bool").contains(t) ? t : "text"));
        }
        return out;
    }

    /** 解析一端实体的别名数组：去空去重（顺序保持模型给出顺序） */
    private List<String> readStringList(JsonNode triple, String field) {
        JsonNode arr = triple.get(field);
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode v : arr) {
            String s = v.asText("").trim();
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private String firstText(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return "";
    }

    private static List<Triple> dedupe(List<Triple> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<Triple> out = new ArrayList<>();
        for (Triple t : in) {
            if (seen.add(t.subject() + "\u0000" + t.predicate() + "\u0000" + t.object())) {
                out.add(t);
            }
        }
        return out;
    }

    /** 降级解析结果：三元组列表 + 模型附带的 summary */
    private record ParsedText(List<Triple> triples, String summary) {
    }

    /** 降级解析：从模型 content 文本（可能带 markdown 围栏）中找 JSON 数组 */
    private ParsedText parseTriplesFromText(String text) {
        if (text == null || text.isBlank()) {
            return new ParsedText(List.of(), null);
        }
        String src = LlmJson.stripFence(text);
        int l = src.indexOf('[');
        int r = src.lastIndexOf(']');
        if (l < 0 || r <= l) {
            return new ParsedText(List.of(), null);
        }
        try {
            JsonNode root = om.readTree(src.substring(l, r + 1));
            List<Triple> triples = new ArrayList<>();
            for (JsonNode n : root) {
                addTriple(triples, n);
            }
            String summary = null;
            Matcher sm = Pattern.compile("\"summary\"\\s*:\\s*\"([^\"]*)\"").matcher(src);
            if (sm.find()) {
                summary = sm.group(1);
            }
            return new ParsedText(triples, summary);
        } catch (Exception e) {
            return new ParsedText(List.of(), null);
        }
    }

    /** 解析问题 JSON：{"questions":[]}（容错同 parseTriplesFromText：markdown 围栏/前后噪声/坏数据回退空） */
    private List<String> parseQuestions(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String src = LlmJson.stripFence(text);
        int l = src.indexOf('{');
        int r = src.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return List.of();
        }
        try {
            return stringList(om.readTree(src.substring(l, r + 1)).get("questions"));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** JSON 字符串数组 → 去空去重保序列表（非数组/坏数据返回空） */
    private static List<String> stringList(JsonNode n) {
        if (n == null || !n.isArray()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode item : n) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().trim());
            }
        }
        return List.copyOf(out);
    }

    /** 后端执行一次工具调用：upsert_node / upsert_edge */
    private String executeTool(String name, String argsJson, Set<String> nodeIds,
                               Set<String> edgeIds, boolean[] vectorized) {
        JsonNode args = LlmJson.readTree(argsJson);
        switch (name) {
            case UpsertNodeTool.NAME -> {
                String nodeName = args.path("name").asText("");
                if (nodeName.isBlank()) {
                    return "失败: name 为空";
                }
                // type 兼容字符串（可逗号/顿号分隔多类型）与字符串数组
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
                String id = graph.upsertEntity(primary,types,nodeName,aliases,summary,args.path("identity_key").asText(null));
                nodeIds.add(id);
                if (embedding.configured()) {
                    Optional<float[]> vec = embedding.embed(
                            nodeName + (summary == null ? "" : "\n" + summary));
                    if (vec.isPresent()) {
                        graph.saveEntityEmbedding(id, embedding.model(), vec.get());
                        vectorized[0] = true;
                    }
                }
                return "节点已写入: " + nodeName;
            }
            case UpsertEdgeTool.NAME -> {
                String subj = args.path("subject").asText("");
                String obj = args.path("object").asText("");
                if (subj.isBlank() || obj.isBlank()) {
                    return "失败: subject/object 为空";
                }
                String pred = args.path("predicate").asText("relates_to");
                String sid = resolveEntity(subj);
                String oid = resolveEntity(obj);
                graph.upsertRelation(sid, pred, oid, null);
                edgeIds.add(GraphStore.relationIdFor(sid, pred, oid));
                return "关系已写入: " + subj + " -" + pred + "-> " + obj;
            }
            default -> {
                return "未知工具: " + name;
            }
        }
    }

    /** 实体按名解析：不存在则兜底建 CONCEPT 节点 */
    private String resolveEntity(String name) {
        GraphStore.Entity e = graph.byName(name);
        if (e == null) {
            graph.upsertEntity("CONCEPT", name, List.of(), null);
            e = graph.byName(name);
        }
        return e.id();
    }
}
