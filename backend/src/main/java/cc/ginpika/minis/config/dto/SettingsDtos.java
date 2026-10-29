package cc.ginpika.minis.config.dto;

import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;
import java.util.List;

/** 设置页请求/响应对（config/SettingsService ↔ 前端）；提示词见 {@link PromptDtos} */
public final class SettingsDtos {

    /** 模型项：builtin=true 为 .env 内置项（id="env"），不可编辑/删除；
     *  contextWindow=上下文窗口（token 数）；保存时留空会尝试从模型 API 获取，空值仅见于历史数据、
     *  前端按默认值兜底 */
    public record ModelView(String id, String name, String baseUrl, String apiKey, String model,
                            Integer contextWindow, boolean builtin,Integer maxOutputTokens) {
        public ModelView(String id,String name,String baseUrl,String apiKey,String model,Integer contextWindow,boolean builtin){this(id,name,baseUrl,apiKey,model,contextWindow,builtin,null);}
    }

    /** contextWindow 留空（null/非正数）→ 后端从模型 API 尽力获取，取不到用默认值 */
    public record ModelRequest(String name, String baseUrl, String apiKey, String model,
                               Integer contextWindow,Integer maxOutputTokens) {
        public ModelRequest(String name,String baseUrl,String apiKey,String model,Integer contextWindow){this(name,baseUrl,apiKey,model,contextWindow,null);}
    }

    /** 按 baseUrl + apiKey + model 单独试取上下文窗口（设置页编辑模型时自动回填用） */
    public record ContextWindowRequest(String baseUrl, String apiKey, String model) {
    }

    /** contextWindow=从模型 API 取到的上下文窗口（token 数）；null=接口未返回或调用失败 */
    public record ContextWindowView(Integer contextWindow) {
    }

    /** 模型连通性测试结果（设置页）：ok=基准对话是否正常返回；error=失败原因 */
    public record ChatTestView(boolean ok, long elapsedMs, String reply, String error) {
    }

    /** Embedding / Rerank 连通性测试结果：ok=探测调用是否正常返回；detail=成功摘要（维度 / 打分数） */
    public record TestView(boolean ok, long elapsedMs, String detail, String error) {
    }

    /** Embedding 配置：mode=llm（复用某个模型端点的 base-url/api-key）| custom（独立端点） */
    public record EmbeddingView(String mode, String llmModelId, String baseUrl, String apiKey,
                                String model) {
    }

    public record EmbeddingRequest(String mode, String llmModelId, String baseUrl, String apiKey,
                                   String model) {
    }

    /** Rerank 配置：mode=llm（复用某个模型端点的 base-url/api-key）| custom（独立端点）| off（关闭精排） */
    public record RerankView(String mode, String llmModelId, String baseUrl, String apiKey,
                             String model) {
    }

    public record RerankRequest(String mode, String llmModelId, String baseUrl, String apiKey,
                                String model) {
    }

    /** 默认模型选择：空串/缺省视为未设置（回退内置 env 项） */
    public record DefaultsRequest(String modelId) {
    }

    public record SettingsView(List<ModelView> models, EmbeddingView embedding, RerankView rerank,
                               String defaultModelId) {
    }

    /**
     * 重置知识库结果：删除的 chunk / 实体 / 关系 / 记忆条数。
     *   memories      = 记忆总数（时序事件 + 记忆条目）
     *   memoryEvents  = 其中时序事件（memory_event）
     *   memoryItems   = 其中记忆条目（graph_claim）
     *   candidates / shares = 一并清除的画像候选 / 共享记录
     */
    public record ResetResult(int chunks, int entities, int relations, int memories, int memoryEvents,
                              int memoryItems, int candidates, int shares, int episodes, int ingestJobs,
                              int checkpoints, int recallTraces, int deletedProjectDocuments, int retainedProjectDocuments) {
    }

    private SettingsDtos() {
    }
}
