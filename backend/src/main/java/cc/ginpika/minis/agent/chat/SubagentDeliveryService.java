package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.chat.turn.TurnTrigger;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.SubagentSummaryBlock;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 子代理结论的投递侧：把跑完的子代理回执渲染进它派出去的那个会话。
 *
 * <p>从 {@link ChatTurnRunner} 搬出来（行为零变化）。它监听两个事件、各做一件事：
 * <ul>
 *   <li>{@link #onSubagentReport}——一件跑完：把长报告压成「标题 + 摘要」，落成一条
 *       {@code kind=subagent} 气泡（前端只显示卡片，点开看全文）；</li>
 *   <li>{@link #onSubagentBatchDone}——整批跑完：让主模型以秘书口吻补一条整体交代，并打未读点。</li>
 * </ul>
 *
 * <p>为什么独立成件：这是「子代理模块 → 会话」的一条单向投递链路，与「一次生成怎么跑」无关；
 * 留在会话循环里只会让它继续膨胀。搬出来之后它可在没有循环的情况下单独回归。
 *
 * <p><b>与派活方的关系</b>：{@code SubagentService} 只发事件（不引用会话侧），本类只收事件——
 * 依赖方向单向，两边不互相成环。
 */
@Component
public class SubagentDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(SubagentDeliveryService.class);

    private final SettingsService settings;
    private final OpenAiChatClient client;
    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    /** Turn 正本：子代理投回也要落一条 Turn，历史配对才完整 */
    private final TurnStore turnStore;
    private final ObjectMapper om;
    private final PromptManager prompts;
    private final SessionEventBus events;
    private final SubagentSynthesis synthesizer;

    public SubagentDeliveryService(SettingsService settings, OpenAiChatClient client,
                                   HubSessionStore sessions, ChatMessageStore messages,
                                   TurnStore turnStore,
                                   ObjectMapper om, PromptManager prompts, SessionEventBus events,
                                   SubagentSynthesis synthesizer) {
        this.settings = settings;
        this.client = client;
        this.sessions = sessions;
        this.messages = messages;
        this.turnStore = turnStore;
        this.om = om;
        this.prompts = prompts;
        this.events = events;
        this.synthesizer = synthesizer;
    }

    /**
     * 一个子代理跑完，把它的结论落成一条气泡消息：先把长报告压成「标题 + 摘要」（一次模型调用），
     * 再以 {@code kind=subagent} 落库——前端只显示标题与摘要，点开才看 {@code content} 里的全文。
     *
     * <p>这里不打未读点：一次输入可能派出多个子代理，要等整批都回来才算「有结果可看」，
     * 未读点与总结统一由 {@link #onSubagentBatchDone} 在收批时做。
     */
    @EventListener
    public void onSubagentReport(SubagentReportEvent event) {
        HubRow row = sessions.findById(event.hubId()).orElse(null);
        if (row == null) {
            log.warn("子代理「{}」结论投回失败：会话已不存在 {}", event.title(), event.hubId());
            return;
        }
        boolean done = "done".equals(event.status());
        String report = event.output() == null ? "" : event.output();
        ModelTarget target = settings.resolveChatModel(row.modelId());
        ChatDtos.SubagentBrief brief = brief(target, event, report);
        String body = done ? report : failedText(event.error());
        long messageId = messages.insert(row.id(), "assistant", body, null, null, null,
                ChatMessageStore.KIND_SUBAGENT, writeBrief(brief));
        /* 子代理投回不是人问的：落一条「子代理回报」的 Turn（没有触发端），历史配对才完整 */
        turnStore.record(row.id(), TurnTrigger.Source.SUBAGENT_REPORT, messageId);
        /* 结论刚到：即时推一条 delivered 事件，前端据此立刻把这条气泡刷出来（不必等整批、也不必轮询） */
        events.publish(new SessionEventBus.Event(row.id(), SessionEventBus.KIND_DELIVERED,
                false, brief.title()));
    }

    /**
     * 一批子代理全部跑完（收批事件）：让主模型以本人（秘书）口吻补一条整体总结，接在这批气泡之后，
     * 并打未读点——首页「最近会话」提示，人正停在该会话里时前端收到未读即把这一组刷出来。
     *
     * <p>一次输入派出多个子代理时，总结只做这一次；各件结论已作为上面的气泡消息逐条投回，
     * 因此总结指令不重复报告全文（历史里就带着），只交代要覆盖几件、该怎么归纳。
     * 模型调用失败就跳过总结，未读点照打（气泡本身已可见）。
     */
    @EventListener
    public void onSubagentBatchDone(SubagentBatchDoneEvent event) {
        HubRow row = sessions.findById(event.hubId()).orElse(null);
        if (row == null) {
            log.warn("子代理整批总结投回失败：会话已不存在 {}", event.hubId());
            return;
        }
        ModelTarget target = settings.resolveChatModel(row.modelId());
        /* 合成器收口：以主模型口吻给一段整体交代（含失败点名）；它从批次里取回各次执行的成败 */
        String summary = synthesizer.synthesize(target, synthesisContext(row), event.runId());
        if (summary != null && !summary.isBlank()) {
            long summaryId = messages.insert(row.id(), "assistant", summary, null, null, null, null, null);
            turnStore.record(row.id(), TurnTrigger.Source.SUBAGENT_REPORT, summaryId);
        }
        sessions.touch(row.id(), null);
        sessions.markUnread(row.id());
        /* 整批收口：unread 已置位，推一条 delivered（unread=true）让列表打点、通知气泡弹出 */
        events.publish(new SessionEventBus.Event(row.id(), SessionEventBus.KIND_DELIVERED,
                true, row.title()));
    }

    /** 合成器的上下文基底：system + 本会话历史（本批各条结论气泡已排在末尾） */
    private List<OpenAiChatClient.ChatMessage> synthesisContext(HubRow row) {
        List<OpenAiChatClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new OpenAiChatClient.ChatMessage("system", prompts.chatSystem(row.promptId())));
        for (MessageRow m : messages.listBySession(row.id())) {
            msgs.add(new OpenAiChatClient.ChatMessage(m.role(), m.content()));
        }
        return msgs;
    }

    /** 气泡的标题与摘要：让模型把长报告压成一张卡片；未完成或摘要生成失败时本地兜底，绝不让投回失败 */
    private ChatDtos.SubagentBrief brief(ModelTarget target, SubagentReportEvent event, String report) {
        if (!"done".equals(event.status())) {
            return new ChatDtos.SubagentBrief(event.title(), failedText(event.error()),
                    event.status(), event.execId());
        }
        try {
            String cardJson = client.chat(target, List.of(
                    new OpenAiChatClient.ChatMessage("system",
                            prompts.resolve(SubagentSummaryBlock.KEY)),
                    new OpenAiChatClient.ChatMessage("user", report)),
                    TokenUsageService.SOURCE_CHAT).content();
            JsonNode card = om.readTree(cardJson);
            String title = card == null ? "" : card.path("title").asText("").trim();
            String summary = card == null ? "" : card.path("summary").asText("").trim();
            if (summary.isEmpty()) {
                throw new IllegalStateException("模型未给出摘要");
            }
            return new ChatDtos.SubagentBrief(title.isEmpty() ? event.title() : title, summary,
                    event.status(), event.execId());
        } catch (Exception e) {
            log.warn("子代理「{}」摘要生成失败（退化为本地截取）: {}", event.title(), e.getMessage());
            return new ChatDtos.SubagentBrief(event.title(), fallbackSummary(report),
                    event.status(), event.execId());
        }
    }

    /** 未跑出结论时的说明文案（气泡摘要与正文共用同一句） */
    private static String failedText(String error) {
        return "它没有跑出结论：" + (error == null ? "未知原因" : error);
    }

    /** 本地兜底摘要：去掉 markdown 记号、压平空白后截一段（模型摘要生成失败时用） */
    private static String fallbackSummary(String report) {
        String flat = report.replaceAll("[#*>`\\[\\]()!_-]", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }

    /** 气泡元信息序列化为 JSON；序列化失败返回 null（前端据此只显示标题，不影响全文可见） */
    private String writeBrief(ChatDtos.SubagentBrief brief) {
        try {
            return om.writeValueAsString(brief);
        } catch (Exception e) {
            log.warn("子代理气泡元信息序列化失败（前端只显示标题）: {}", e.getMessage());
            return null;
        }
    }
}
