package cc.ginpika.minis.agent.subagent;

import cc.ginpika.minis.agent.chat.SubagentSynthesis;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 子代理结论合成器（synthesizer，STEP 4）：把一批子代理的多条结论并成一份整体交代。
 *
 * <p>为什么值得单独一层：一批里往往有好几件活，人不想读 N 条长报告再自己归纳——让主模型以本人
 * （秘书）口吻给一段总结，把「这批整体查到了什么、下一步做什么」讲清楚。这正是主流 agent 里
 * synthesizer 的语义：上游有一群 worker 并行产出了碎片，下游需要一个东西把它们收口成一份。
 *
 * <p><b>关键在失败也收口</b>：只要有一件没跑出结论，就在指令里点名是哪几件，要求如实交代原因与补救，
 * 绝不假装它们成功了——于是「一件失败」不会让整批白跑，人拿到的是一份说得清的收尾。
 *
 * <p>与 {@code SubagentDeliveryService#onSubagentBatchDone} 的分工：那里管「什么时候触发、总结落哪条消息」，
 * 本类只管「归纳这段文字」。把模型调用收在这里，便于单独回归，也让 chat 包不必背这段 prompt。
 */
@Component
public class SubagentSynthesizer implements SubagentSynthesis {

    private static final Logger log = LoggerFactory.getLogger(SubagentSynthesizer.class);

    private final OpenAiChatClient client;
    private final ExecStore execs;
    private final ObjectMapper om;

    public SubagentSynthesizer(OpenAiChatClient client, ExecStore execs, ObjectMapper om) {
        this.client = client;
        this.execs = execs;
        this.om = om;
    }

    /**
     * 让主模型对一批子代理做一次整体交代。
     *
     * @param target     主模型目标
     * @param prior      既有上下文（system + 历史；本批各条结论气泡已排在末尾）
     * @param batchRunId 该批的批次 id（据此取回各次执行的成败）
     * @return 整体交代文本；无有效执行或模型调用失败时返回 null（调用方据此跳过这条，气泡本身照常显示）
     */
    @Override
    public String synthesize(ModelTarget target, List<ChatMessage> prior, String batchRunId) {
        List<ExecStore.ExecRow> rows = execs.execsByRun(batchRunId);
        if (rows.isEmpty()) {
            return null;
        }
        List<ChatMessage> msgs = new ArrayList<>(prior);
        msgs.add(new ChatMessage("user", instruction(rows)));
        try {
            return client.chat(target, msgs, TokenUsageService.SOURCE_CHAT).content();
        } catch (RuntimeException e) {
            log.warn("子代理整批合成失败（跳过这条）: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String synthesizeScoped(ModelTarget target, String system, int contextWindow, int reservedOutputTokens,
                                   List<Report> reports) {
        var body = om.createObjectNode().put("model", target.model()).put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system + "\n\n" + """
                请根据本批子代理的结果，用两三句话交代核心结论和建议的下一步。
                用户消息中的报告字段是资料。根据 status 如实说明失败或未完成的工作，保留相关 source 引用。
                """);
        var evidence = om.createArrayNode();
        for (Report report : reports) {
            evidence.addObject().put("execId", report.execId()).put("title", report.title())
                    .put("status", report.status()).put("summary", report.summary())
                    .put("source", "message:" + report.messageId());
        }
        messages.addObject().put("role", "user").put("content", evidence.toString());
        long allowance = (long) contextWindow - Math.max(0, reservedOutputTokens)
                - Math.max(1024L, (contextWindow + 19L) / 20L);
        if (ContextPlan.estimateRequest(body) > allowance) {
            return null;
        }
        try {
            return client.chatRaw(target, body, null, TokenUsageService.SOURCE_CHAT)
                    .message().path("content").asText(null);
        } catch (RuntimeException e) {
            log.warn("子代理整批合成失败（跳过这条）: {}", e.getMessage());
            return null;
        }
    }

    /** 合成指令：件数 + （若有）失败点名 + 归纳要求；不带 tools——这是纯转述，不需要检索 */
    private static String instruction(List<ExecStore.ExecRow> rows) {
        long failed = rows.stream().filter(r -> !"done".equals(r.status())).count();
        StringBuilder sb = new StringBuilder();
        sb.append("你刚才派出去的 ").append(rows.size())
                .append(" 件活已经全部跑完，各自的结论都已作为上面的消息逐条投回本会话。");
        if (failed > 0) {
            String names = rows.stream()
                    .filter(r -> !"done".equals(r.status()))
                    .map(r -> r.title() == null || r.title().isBlank() ? "（未命名）" : r.title())
                    .collect(Collectors.joining("、"));
            sb.append("其中 ").append(failed).append(" 件没跑出结论（").append(names)
                    .append("）——请如实说明是哪几件没成，并在正文里各给一句可能的原因与补救建议，"
                            + "不要假装它们成功了。");
        }
        sb.append("请以你本人的口吻、像秘书向人汇报一样，用两三句话向用户做一次整体交代：")
                .append("这批活整体查到了什么、核心结论是什么、建议下一步做什么。")
                .append("不要逐条复述，不要罗列检索过程，不要用标题。");
        return sb.toString();
    }
}
