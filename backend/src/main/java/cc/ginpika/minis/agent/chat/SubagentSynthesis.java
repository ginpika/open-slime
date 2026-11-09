package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.config.ModelTarget;
import java.util.List;

/**
 * 子代理结论合成口。{@link SubagentDeliveryService#onSubagentBatchDone} 经本口子把多条结论
 * 归纳成一份整体交代，投递侧负责鉴权和落库。
 */
public interface SubagentSynthesis {

    record Report(long execId, long messageId, String title, String status, String summary) {
    }

    /**
     * 对一批子代理做一次整体交代（含失败点名的可读收尾）。
     *
     * @param target     主模型目标
     * @param prior      既有上下文（system + 历史；本批各条结论气泡已排在末尾）
     * @param batchRunId 该批的批次 id（{@code heartbeat_run.id}）
     * @return 整体交代文本；无有效执行或模型调用失败时返回 null
     */
    String synthesize(ModelTarget target, List<ChatMessage> prior, String batchRunId);

    /** 作用域批次只交给模型已获授权的短结论和报告引用。 */
    String synthesizeScoped(ModelTarget target, String system, int contextWindow, int reservedOutputTokens,
                            List<Report> reports);
}
