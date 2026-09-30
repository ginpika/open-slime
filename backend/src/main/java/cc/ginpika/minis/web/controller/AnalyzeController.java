package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.ai.knowledge.ingest.AnalyzeService;
import cc.ginpika.minis.ai.knowledge.ingest.DocSummaryService;
import cc.ginpika.minis.config.SsoProps;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.ai.knowledge.store.DocumentStore;
import cc.ginpika.minis.ai.knowledge.dto.AnalyzeDtos;
import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoService;
import cc.ginpika.minis.web.sso.SsoUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 文档分析：LLM 抽取三元组 + function calling 入库/向量化；文档-chunk 选择工作流。门禁同 AdminController（写操作）。 */
@RestController
@RequestMapping("/api/analyze")
public class AnalyzeController {

    private static final Set<String> CHUNK_STATUSES =
            Set.of(DocumentStore.STATUS_UNANALYZED, DocumentStore.STATUS_ENABLED,
                    DocumentStore.STATUS_DISABLED);

    private final AnalyzeService service;
    private final ChunkStore chunks;
    private final DocumentStore documents;
    private final GraphStore graph;
    private final DocSummaryService summaryService;
    private final SsoProps ssoProps;
    private final SsoService ssoService;

    public AnalyzeController(AnalyzeService service, ChunkStore chunks, DocumentStore documents,
                             GraphStore graph, DocSummaryService summaryService,
                             SsoProps ssoProps, SsoService ssoService) {
        this.service = service;
        this.chunks = chunks;
        this.documents = documents;
        this.graph = graph;
        this.summaryService = summaryService;
        this.ssoProps = ssoProps;
        this.ssoService = ssoService;
    }

    @PostMapping
    public AnalyzeDtos.AnalyzeResponse analyze(HttpServletRequest request,
                                               @RequestBody AnalyzeDtos.AnalyzeRequest req) {
        requireAdmin(request);
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 为空");
        }
        try {
            return service.analyze(content);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    @PostMapping("/commit")
    public AnalyzeDtos.CommitResult commit(HttpServletRequest request,
                                           @RequestBody AnalyzeDtos.CommitRequest req) {
        requireAdmin(request);
        try {
            return service.commit(req.triples());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    // ---------- 步骤1：选择 Chunk（文档 ↔ chunk 双向父子） ----------

    /** 文档列表（读端点，不设门禁；标题 + 子块统计 + 全文概述 + 文档类型） */
    @GetMapping("/documents")
    public List<AnalyzeDtos.DocumentSummary> documents() {
        return documents.list().stream()
                .map(d -> new AnalyzeDtos.DocumentSummary(
                        d.id(), d.title(), d.createdAt(), d.chunkCount(), d.analyzedCount(),
                        d.contextualizing(), d.docType()))
                .toList();
    }

    /** 新建文档：分块入库（doc_id 关联、状态未分析） */
    @PostMapping("/documents")
    public AnalyzeDtos.CreateDocumentResult createDocument(HttpServletRequest request,
                                                           @RequestBody AnalyzeDtos.CreateDocumentRequest req) {
        requireAdmin(request);
        String title = req.title() == null ? "" : req.title().trim();
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title 为空");
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 为空");
        }
        DocumentStore.CreatedDoc doc = documents.create(title, content);
        return new AnalyzeDtos.CreateDocumentResult(doc.id(), doc.title(), doc.chunks());
    }

    /** 触发文档（重新）分块：已启用旧块先禁用并从向量库移除，再清旧 chunk（连带其承载的关系/向量/实体链接）后重切，状态归零 */
    @PostMapping("/documents/{id}/rechunk")
    public AnalyzeDtos.CreateDocumentResult rechunkDocument(HttpServletRequest request,
                                                            @PathVariable long id) {
        requireAdmin(request);
        try {
            DocumentStore.CreatedDoc doc = documents.rechunkDocument(id);
            return new AnalyzeDtos.CreateDocumentResult(doc.id(), doc.title(), doc.chunks());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * 全文概述 + 文档类型：摘要与类型均已生成则直接返回；
     * 否则 LLM 一次调用同时生成概述（contextualizing）与类型（doc_type）并落库后返回。
     * 旧文档仅有概述缺类型时会一并重生成（覆盖旧的概述），使类型标签可用。
     */
    @PostMapping("/documents/{id}/summary")
    public AnalyzeDtos.DocumentSummaryResult documentSummary(HttpServletRequest request,
                                                             @PathVariable long id) {
        requireAdmin(request);
        String existing = documents.contextualizing(id);
        String existingType = documents.docType(id);
        if (existing != null && !existing.isBlank()
                && existingType != null && !existingType.isBlank()) {
            return new AnalyzeDtos.DocumentSummaryResult(existing, existingType, false);
        }
        String full = documents.fullText(id);
        if (full == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        if (full.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "文档没有可总结的内容");
        }
        try {
            DocSummaryService.Summary summary = summaryService.summarize(full);
            documents.updateContextualizing(id, summary.contextualizing());
            if (summary.docType() != null) {
                documents.updateDocType(id, summary.docType());
            }
            return new AnalyzeDtos.DocumentSummaryResult(
                    summary.contextualizing(), summary.docType(), true);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /** 三元组消歧评估（步骤 2）：实体存在性/模糊候选/两实体间已有关系（读操作，不设门禁） */
    @PostMapping("/disambiguate")
    public AnalyzeDtos.DisambiguateResult disambiguate(
            @RequestBody AnalyzeDtos.DisambiguateRequest req) {
        try {
            return service.disambiguate(req.triples());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /** 提交勾选的三元组：写入图（关联 chunk：chunk_entity + source_chunk），chunk 置已分析 */
    @PostMapping("/chunks/{id}/commit")
    public AnalyzeDtos.ChunkCommitResult commitChunk(HttpServletRequest request,
                                                    @PathVariable long id,
                                                    @RequestBody AnalyzeDtos.ChunkCommitRequest req) {
        requireAdmin(request);
        try {
            return service.commitChunk(id, req.triples());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /** chunk 最近一次提交的知识提取记录（提交时间 + 通过该 chunk 更新的三元组清单；无记录 404） */
    @GetMapping("/chunks/{id}/extraction")
    public AnalyzeDtos.ChunkExtractionView chunkExtraction(@PathVariable long id) {
        AnalyzeDtos.ChunkExtractionView view = service.chunkExtraction(id);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该 chunk 尚无知识提取记录");
        }
        return view;
    }

    /** 假设性问题生成（步骤 3 预案编辑用）：LLM 生成 5 个该段落能解答的问题（只读，不落库） */
    @PostMapping("/chunks/{id}/questions")
    public AnalyzeDtos.QuestionsResult genQuestions(HttpServletRequest request,
                                                    @PathVariable long id) {
        requireAdmin(request);
        try {
            return service.genQuestions(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /** 段落摘要生成（步骤 3 Chunk Header 语义定位用）：LLM 生成一句话段落摘要（只读，不落库） */
    @PostMapping("/chunks/{id}/summary")
    public AnalyzeDtos.ChunkSummaryResult chunkSummary(HttpServletRequest request,
                                                       @PathVariable long id) {
        requireAdmin(request);
        try {
            return service.summarizeChunk(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /** 保存最终提交预案留档（Chunk Header / 假设性问题生成后落库，二次进入自动回显）；
     *  header/questions：null = 不更新该字段，空白 = 清空 */
    @PutMapping("/chunks/{id}/plan")
    public Map<String, Boolean> savePlan(HttpServletRequest request, @PathVariable long id,
                                         @RequestBody AnalyzeDtos.PlanRequest req) {
        requireAdmin(request);
        try {
            service.savePlan(id, req.header(), req.questions());
            return Map.of("success", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    /** 提交 Chunk（步骤 3 收尾）：预案全文向量化写 chunk_embedding + 分段留档 + chunk 启用，结束知识入库流程 */
    @PostMapping("/chunks/{id}/submit")
    public AnalyzeDtos.ChunkSubmitResult submitChunk(HttpServletRequest request, @PathVariable long id,
                                                     @RequestBody AnalyzeDtos.ChunkSubmitRequest req) {
        requireAdmin(request);
        try {
            return service.submitChunk(id, req.text(), req.header(), req.questions());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /** 删除 chunk：连带其承载的关系与向量/实体链接/FTS 一并清除 */
    @DeleteMapping("/chunks/{id}")
    public Map<String, Boolean> deleteChunk(HttpServletRequest request, @PathVariable long id) {
        requireAdmin(request);
        try {
            documents.deleteChunk(id);
            return Map.of("success", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    /** 文档下的全部 chunk（id/时间/状态 + 关联实体/关系） */
    @GetMapping("/documents/{id}/chunks")
    public List<AnalyzeDtos.ChunkView> documentChunks(@PathVariable long id) {
        List<ChunkStore.Row> rows = chunks.rowsByDoc(id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在或没有 chunk");
        }
        return assembleChunks(rows);
    }

    /** 切换 chunk 状态（启用/禁用/未分析），返回更新后的 chunk 视图 */
    @PutMapping("/chunks/{id}/status")
    public AnalyzeDtos.ChunkView updateChunkStatus(HttpServletRequest request,
                                                   @PathVariable long id,
                                                   @RequestBody AnalyzeDtos.ChunkStatusRequest req) {
        requireAdmin(request);
        String status = req.status() == null ? "" : req.status().trim();
        if (!CHUNK_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status 需为 unanalyzed | enabled | disabled");
        }
        if (chunks.updateStatus(id, status) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chunk 不存在");
        }
        return assembleChunks(chunks.rowsByIds(List.of(id))).get(0);
    }

    /** ChunkStore 行 → ChunkView（批量取关联实体/关系，按 chunkId 分组挂载） */
    private List<AnalyzeDtos.ChunkView> assembleChunks(List<ChunkStore.Row> rows) {
        List<Long> ids = rows.stream().map(ChunkStore.Row::id).toList();
        Map<Long, List<AnalyzeDtos.ChunkEntityView>> entities = graph.entitiesByChunks(ids).stream()
                .collect(Collectors.groupingBy(GraphStore.ChunkEntityLink::chunkId,
                        Collectors.mapping(
                                l -> new AnalyzeDtos.ChunkEntityView(l.entityId(), l.name(), l.type()),
                                Collectors.toList())));
        Map<Long, List<AnalyzeDtos.ChunkRelationView>> relations = graph.relationsByChunks(ids).stream()
                .collect(Collectors.groupingBy(GraphStore.ChunkRelationLink::chunkId,
                        Collectors.mapping(
                                l -> new AnalyzeDtos.ChunkRelationView(
                                        l.relationId(), l.predicate(), l.subjectName(), l.objectName()),
                                Collectors.toList())));
        return rows.stream()
                .map(r -> new AnalyzeDtos.ChunkView(r.id(), r.docId() == null ? 0 : r.docId(),
                        r.title(), r.createdAt(), r.status(), r.content(),
                        r.header(), r.questions(),
                        entities.getOrDefault(r.id(), List.of()),
                        relations.getOrDefault(r.id(), List.of())))
                .toList();
    }

    private void requireAdmin(HttpServletRequest request) {
        if (!ssoService.isEnabled()) {
            return; // dev 直通
        }
        SsoUser user = (SsoUser) request.getAttribute(SsoInterceptor.ATTR);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        boolean allowed = "admin".equalsIgnoreCase(user.getRole())
                || ssoProps.getAdminUsernames().contains(user.getUsername());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
    }
}
