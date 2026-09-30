package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAI 兼容 LLM（prefix: minis.llm）。聊天与 Embedding 共用 base-url/api-key。 */
@ConfigurationProperties(prefix = "minis.llm")
public class LlmProps {

    /** 上下文窗口默认值（token 数）：模型 API 未返回时的兜底，按常见模型最大支持值取 256K */
    public static final int DEFAULT_CONTEXT_WINDOW = 262_144;

    private String baseUrl = "";

    private String apiKey = "";

    /** 聊天模型（OpenAI Compatible /chat/completions 的 model） */
    private String chatModel = "";

    /** 聊天默认 system prompt（空则不发送 system 消息） */
    private String systemPrompt = "";

    /** 固定供应商+模型；换模型需全库重嵌（chunk_embedding.model） */
    private String embeddingModel = "";

    /** 精排模型（复用 base-url/api-key）；空则不启用 rerank，检索保持 RRF 序 */
    private String rerankModel = "";

    /** 内置模型的上下文窗口（token 数）；供会话页上下文占比圆环估算，用户模型可在设置页单独填写 */
    private Integer contextWindow = DEFAULT_CONTEXT_WINDOW;

    /** 读超时：embedding 整次请求；聊天用 chat-timeout-ms（流式语义不同，故分开） */
    private long timeoutMs = 30_000;

    /**
     * 聊天读超时（毫秒）：聊天强制 stream=true，故语义为「相邻 chunk 的最大间隔」，
     * 且包含首包之前的等待（TTFT）。摘要会把整篇全文作为单条消息提交，预填充耗时较长，需显著大于 embedding 超时。
     */
    private long chatTimeoutMs = 180_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getRerankModel() {
        return rerankModel;
    }

    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getChatTimeoutMs() {
        return chatTimeoutMs;
    }

    public void setChatTimeoutMs(long chatTimeoutMs) {
        this.chatTimeoutMs = chatTimeoutMs;
    }
}
