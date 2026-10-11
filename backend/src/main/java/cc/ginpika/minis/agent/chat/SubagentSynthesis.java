package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.config.ModelTarget;
import java.util.List;

/**
 * 子代理结论合成口（STEP 4）：会话侧只认识这个口子，不认识子代理模块本身。
 *
 * <p>与 {@link SubagentTool} 同一手法——把端口定义在 chat 包、实现放在 {@code agent.subagent}，
 * 于是依赖方向保持单向（子代理模块依赖 chat 包，chat 包不反向依赖子代理）。整批子代理跑完时，
 * {@link ChatService#onSubagentBatchDone} 经本口子把多条结论收口成一份整体交代。
 */
public interface SubagentSynthesis {

    /**
     * 对一批子代理做一次整体交代（含失败点名的可读收尾）。
     *
     * @param target     主模型目标
     * @param prior      既有上下文（system + 历史；本批各条结论气泡已排在末尾）
     * @param batchRunId 该批的批次 id（{@code heartbeat_run.id}）
     * @return 整体交代文本；无有效执行或模型调用失败时返回 null
     */
    String synthesize(ModelTarget target, List<ChatMessage> prior, String batchRunId);
}
