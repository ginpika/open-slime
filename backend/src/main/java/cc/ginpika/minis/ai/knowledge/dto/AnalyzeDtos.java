package cc.ginpika.minis.ai.knowledge.dto;

import cc.ginpika.minis.ai.knowledge.store.GraphStore;
import java.util.List;

/** 文档分析（LLM 抽取三元组 → function calling 入库/向量化）DTO */
public final class AnalyzeDtos {

    private AnalyzeDtos() {
    }

    /**
     * 一条 实体-关系-实体 三元组。
     * subjectType/objectType：LLM 自由判断的实体类型（大写英文，缺省 CONCEPT，仅新建实体生效）；
     * subjectProps/objectProps：LLM 为两端实体附带的 KV 属性（学号/血型等无归纳意义的琐碎事实，
     * 提交时合并写入对应实体的 meta，同键以新值覆盖）；
     * subjectAliases/objectAliases：LLM 识别出的两端实体别名/他称，提交时并入实体 aliases（检索用）。
     */
    public record Triple(String subject, String predicate, String object,
            String subjectType, String objectType,
            List<GraphStore.Prop> subjectProps, List<GraphStore.Prop> objectProps,
            List<String> subjectAliases, List<String> objectAliases) {
    }

    public record AnalyzeRequest(String content) {
    }

    /** triples + 模型附带的说明（可能为 null）；viaFunctionCalling 标识是否通过工具调用抽取 */
    public record AnalyzeResponse(List<Triple> triples, String summary,
            boolean viaFunctionCalling, String model) {
    }

    public record CommitRequest(List<Triple> triples) {
    }

    /** 一次工具调用的执行记录（供前端展示 function calling 过程） */
    public record ToolCallLog(int round, String tool, String arguments, String result, boolean ok) {
    }

    /** nodes/edges 为写入数（去重后）；vectorized 表示实体向量是否已写入 */
    public record CommitResult(int nodes, int edges, boolean vectorized,
            List<ToolCallLog> toolCalls, String finish) {
    }

    /* ---------- 文档分析工作流（步骤1：选择 Chunk） ---------- */

    /** 文档行（含子块聚合：chunkCount 总块数 / analyzedCount 已分析块数；contextualizing 全文概述，步骤 2 上下文补完用；docType 文档类型标签） */
    public record DocumentSummary(long id, String title, long createdAt,
            int chunkCount, int analyzedCount, String contextualizing, String docType) {
    }

    public record CreateDocumentRequest(String title, String content) {
    }

    public record CreateDocumentResult(long id, String title, int chunks) {
    }

    /** 全文概述（documents.contextualizing）+ 文档类型（documents.doc_type）；generated=true 表示本次由 LLM 新生成 */
    public record DocumentSummaryResult(String contextualizing, String docType, boolean generated) {
    }

    /* ---------- 步骤2：知识提取（三元组消歧评估 + 提交） ---------- */

    /** 消歧查询：LLM 抽出的三元组清单 */
    public record DisambiguateRequest(List<Triple> triples) {
    }

    /** 实体精简视图（存在性/候选/预览用） */
    public record EntityLite(String id, String name, String type) {
    }

    /** 一端实体的消歧信息：exact 非空=同名实体已存在；candidates=模糊命中（LIKE name/aliases，供改名参考） */
    public record EntityLookup(String name, EntityLite exact, List<EntityLite> candidates) {
    }

    /** 两端都存在时，两实体间已有的关系（无向：任一方向均列出） */
    public record EdgeLite(String id, String predicate, String subjectId, String objectId,
            String subjectName, String objectName) {
    }

    /** 单条三元组的消歧评估结果 */
    public record TripleEval(EntityLookup subject, EntityLookup object, List<EdgeLite> edges) {
    }

    public record DisambiguateResult(List<TripleEval> triples) {
    }

    /** 勾选后的三元组提交（写入图并关联 chunk） */
    public record ChunkCommitRequest(List<Triple> triples) {
    }

    /** nodes/edges=新写入数，duplicates=已存在被忽略的关系数，props=合并写入的实体属性数，vectorized=实体是否向量化 */
    public record ChunkCommitResult(int nodes, int edges, int duplicates, int props,
            boolean vectorized) {
    }

    /** chunk 最近一次知识提取提交的记录：createdAt=提交时间，triples=当时通过该 chunk 写入图的三元组清单 */
    public record ChunkExtractionView(long chunkId, long createdAt, List<Triple> triples) {
    }

    /* ---------- 步骤3：最终提交预案（假设性问题生成 + 分段留档 + 提交） ---------- */

    /** 假设性问题生成结果（步骤 3 预案编辑，只读不落库）：questions = 该段落能解答的问题清单 */
    public record QuestionsResult(long chunkId, List<String> questions) {
    }

    /** Chunk 段落摘要生成结果（步骤 3 Chunk Header 语义定位用，只读不落库）：summary = 该段落一句话摘要 */
    public record ChunkSummaryResult(long chunkId, String summary) {
    }

    /** 最终提交预案留档保存请求（header/questions：null = 不更新该字段，空白 = 清空） */
    public record PlanRequest(String header, String questions) {
    }

    /** Chunk 提交（步骤 3 收尾）：text = 最终提交预案全文；header/questions = 用户改后的分段留档（null = 不更新） */
    public record ChunkSubmitRequest(String text, String header, String questions) {
    }

    /** 提交结果：vectorized = 预案全文是否已向量化写入 chunk_embedding 及规格 */
    public record ChunkSubmitResult(boolean vectorized, String model, int dim) {
    }

    /** chunk 关联实体（chunk_entity 侧） */
    public record ChunkEntityView(String id, String name, String type) {
    }

    /** chunk 产出的关系（relations.source_chunk 侧），subject/object 为实体名 */
    public record ChunkRelationView(String id, String predicate, String subject, String object) {
    }

    /** chunk 行：双向父子关系（docId 回指父文档）+ 关联实体/关系；content 为原文（供查看）；
     *  header/questions 为最终提交预案留档（生成时落库，二次进入自动回显） */
    public record ChunkView(long id, long docId, String title, long createdAt, String status,
            String content, String header, String questions,
            List<ChunkEntityView> entities, List<ChunkRelationView> relations) {
    }

    public record ChunkStatusRequest(String status) {
    }
}
