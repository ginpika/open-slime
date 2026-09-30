package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.DocSummaryBlock;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 文档全文摘要：一次 LLM 调用同时产出 100~200 字摘要（主题/核心内容/覆盖模块）与父文档类型判定，
 * 调用方负责落库（documents.contextualizing / documents.doc_type）。
 */
@Service
public class DocSummaryService {

    /** 文档类型值域：技术手册/规范/严谨知识 */
    public static final String TYPE_TECHNICAL = "TECHNICAL";
    /** 文档类型值域：主观类表达/叙述/个人博客/评论/发言 */
    public static final String TYPE_NARRATIVE = "NARRATIVE";
    /** 文档类型值域：对话/聊天记录 */
    public static final String TYPE_CONVERSATIONAL = "CONVERSATIONAL";

    private static final Set<String> DOC_TYPES =
            Set.of(TYPE_TECHNICAL, TYPE_NARRATIVE, TYPE_CONVERSATIONAL);

    private final OpenAiChatClient chat;
    private final PromptManager prompts;
    private final ObjectMapper om = new ObjectMapper();

    public DocSummaryService(OpenAiChatClient chat, PromptManager prompts) {
        this.chat = chat;
        this.prompts = prompts;
    }

    /** 摘要 + 类型判定；docType 无法判定时为 null（摘要仍可用，调用方不覆盖已有类型） */
    public record Summary(String contextualizing, String docType) {
    }

    /** 生成摘要并判定文档类型（失败抛 IllegalStateException，由上层映射 502） */
    public Summary summarize(String fullText) {
        if (!chat.configured()) {
            throw new IllegalStateException("LLM 未配置（minis.llm.base-url / api-key / chat-model）");
        }
        return parse(chat.chat(List.of(
                new ChatMessage("system", prompts.resolve(DocSummaryBlock.KEY)),
                new ChatMessage("user", fullText)), TokenUsageService.SOURCE_DOC_SUMMARY).content());
    }

    /**
     * 容错解析模型输出：markdown 围栏/前后噪声剥离后取 JSON 对象；
     * JSON 不可用时整段文本作摘要、类型置空（模型未按格式输出时不丢摘要能力）。
     */
    private Summary parse(String text) {
        String raw = text == null ? "" : text.strip();
        Matcher fence = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```").matcher(raw);
        String src = fence.find() ? fence.group(1) : raw;
        int l = src.indexOf('{');
        int r = src.lastIndexOf('}');
        if (l >= 0 && r > l) {
            try {
                JsonNode root = om.readTree(src.substring(l, r + 1));
                String summary = root.path("summary").asText("").strip();
                String type = root.path("docType").asText("").strip().toUpperCase();
                if (!summary.isEmpty()) {
                    return new Summary(summary, DOC_TYPES.contains(type) ? type : null);
                }
            } catch (Exception ignored) {
                // 落回纯文本摘要
            }
        }
        return new Summary(raw, null);
    }
}
