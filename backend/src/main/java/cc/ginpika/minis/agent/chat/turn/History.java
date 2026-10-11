package cc.ginpika.minis.agent.chat.turn;

import cc.ginpika.minis.agent.chat.ChatLiveStore;
import cc.ginpika.minis.agent.chat.ChatTraceCodec;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.context.ChatProtocol;
import cc.ginpika.minis.agent.context.ContextProvider;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 会话历史（History）：某会话的 <b>Turn 序列</b>——按序读取、回放、以及在途那条尾巴。
 *
 * <p><b>为什么单独抽出来</b>：会话（{@code ChatSession}）是「身份」——小、稳定；History 是「内容」——
 * 会一直增长、要按序读、要回放、还有一条尚未落定的尾巴。两者生命周期不同，故分开。History 只管四件事：
 * <ul>
 *   <li><b>顺序</b>：{@link #turns} 按落库顺序把行配成 Turn（用户行 = 触发，紧随的 assistant 行 = 输出）；</li>
 *   <li><b>回放</b>：{@link #messages} 把 Turn 展开成一次对话补全的 messages 序列；</li>
 *   <li><b>逼近窗口才降级</b>：总估算超过预算先丢最早的轮次、仍超再截断——不到临界一行不动，
 *       所以普通会话与从前逐字一致（见 {@link #messages}）；</li>
 *   <li><b>在途尾巴</b>：正在生成的那条 Turn 由 {@link ChatLiveStore} 现取现挂，标记 {@code inFlight}；</li>
 *   <li>不调模型、不跑工具、不拼 system——拼装是上下文平面（{@code ContextBuilder}）的事。</li>
 * </ul>
 *
 * <p><b>回放规则</b>（与原 {@code SessionHistoryProvider} 逐字一致）：默认只回放 {@code role + content}；
 * 会话开启 {@code context_trace}（「…」菜单开关）时，assistant 的历史按原生协议回放——该轮的工具往返
 * （落库于 {@code chat_message.tool_rounds}）连同 {@code reasoning_content} 一并回放，超限的思考 /
 * tool 结果 / 调用参数截断留痕，协议闭合、不向 content 注入任何标记文本。
 *
 * <p>子代理结论不是主模型自己写的（子代理跑完投回的）：回放时正文前拼一行出处（见 {@link #attributed}），
 * 归因跨轮不丢。
 */
@Component
@Order(10)
public class History implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(History.class);

    /** context_trace 开启时单段历史思考回灌上下文的字符上限（避免思维链过长挤爆上下文窗口） */
    private static final int CONTEXT_REASONING_LIMIT = 2000;

    /** context_trace 开启时单条历史 tool 结果 / 调用参数回灌上下文的字符上限：当轮已被模型消费过，
     *  跨请求回放只是留痕（记得调过什么、拿到了什么），超长正文（整页文档 / 新建文档的全文参数）
     *  截断即可——与 Claude Code 等主流 agent 压缩历史工具往返（microcompaction）同一思路 */
    private static final int CONTEXT_TOOL_LIMIT = 1200;

    private final ChatMessageStore messages;
    /** Turn 正本：显式配对（哪条提问配哪条回答）与执行信封的挂点 */
    private final TurnStore turnStore;
    private final ChatLiveStore live;
    private final ObjectMapper om;
    private final ContextProps props;

    public History(ChatMessageStore messages, TurnStore turnStore, ChatLiveStore live,
                   ObjectMapper om, ContextProps props) {
        this.messages = messages;
        this.turnStore = turnStore;
        this.live = live;
        this.om = om;
        this.props = props;
    }

    // ---------- 读：Turn 序列 ----------

    /**
     * 本会话的 Turn 序列，按落库顺序。
     *
     * <p>以 {@code chat_turn} 为骨架：哪条提问配哪条回答是<b>引用</b>，不再靠扫描消息行去猜。
     * 没有被任何 Turn 引用的消息行（旧库的历史、或某条旁路写入）按老规则兜底配对——任何写入者
     * 漏记都不会让历史凭空少一段。末尾若有一次进行中的生成，以 {@code inFlight=true} 的尾巴补上。
     */
    public List<Turn> turns(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<MessageRow> rows = messages.listBySession(sessionId);
        Map<Long, MessageRow> byId = new HashMap<>();
        for (MessageRow r : rows) {
            byId.put(r.id(), r);
        }
        List<Turn> turns = new ArrayList<>();
        Set<Long> claimed = new HashSet<>();
        for (TurnStore.TurnRow t : turnStore.turns(sessionId)) {
            Turn turn = assemble(sessionId, t, byId);
            if (turn == null) {
                continue;
            }
            turns.add(turn);
            if (t.triggerMessageId() != null) {
                claimed.add(t.triggerMessageId());
            }
            if (t.replyMessageId() != null) {
                claimed.add(t.replyMessageId());
            }
        }
        turns.addAll(pairUnclaimed(sessionId, rows, claimed));
        turns.sort(Comparator.comparingLong(History::firstMessageId));
        return withLiveTail(sessionId, turns);
    }

    /** Turn 行 + 两端的消息行 → 领域 Turn；两端消息都已被删（回退过）则返回 null（跳过这条） */
    private Turn assemble(String sessionId, TurnStore.TurnRow t, Map<Long, MessageRow> byId) {
        MessageRow triggerRow = t.triggerMessageId() == null ? null : byId.get(t.triggerMessageId());
        MessageRow replyRow = t.replyMessageId() == null ? null : byId.get(t.replyMessageId());
        if (triggerRow == null && replyRow == null) {
            return null;
        }
        TurnTrigger trigger = triggerRow != null
                ? TurnTrigger.user(triggerRow.content(), triggerRow.id())
                : new TurnTrigger(TurnTrigger.of(t.triggerSource()), null, null);
        return Turn.landed(sessionId, trigger, replyRow == null ? null : replyOf(replyRow));
    }

    /** 兜底配对：没有被任何 Turn 引用的消息行，仍旧按「用户行 + 紧随的回答行」的规则配 */
    private List<Turn> pairUnclaimed(String sessionId, List<MessageRow> rows, Set<Long> claimed) {
        List<Turn> turns = new ArrayList<>();
        Turn pending = null; // 已出现、尚未配到回答的用户 Turn
        for (MessageRow m : rows) {
            if (claimed.contains(m.id())) {
                pending = null; // 已归某条显式 Turn，不再参与兜底
                continue;
            }
            if ("user".equals(m.role())) {
                pending = Turn.landed(sessionId, TurnTrigger.user(m.content(), m.id()), null);
                turns.add(pending);
            } else if ("assistant".equals(m.role())) {
                if (pending != null) {
                    /* 用户行 + 紧随的回答行 = 一条完整 Turn */
                    turns.set(turns.size() - 1, Turn.landed(sessionId, pending.trigger(), replyOf(m)));
                    pending = null;
                } else {
                    /* 无用户行在前（子代理投回的结论等）：自己就是一条 Turn */
                    turns.add(Turn.landed(sessionId, triggerOf(m), replyOf(m)));
                }
            }
        }
        return turns;
    }

    /** 排序键：这条 Turn 第一条消息的行 id（用户 Turn 取提问行，旁路 Turn 取回答行） */
    private static long firstMessageId(Turn turn) {
        Long triggerId = turn.trigger().messageId();
        if (triggerId != null) {
            return triggerId;
        }
        Turn.Reply reply = turn.reply();
        return reply != null && reply.messageId() != null ? reply.messageId() : Long.MAX_VALUE;
    }

    /**
     * 回放：把 Turn 序列展开成一次对话补全的 messages（在途那条只回放它的问题，不回放半截回答）。
     *
     * <p><b>只在逼近窗口时才降级</b>：总估算没超过 {@link ContextRequest#historyTokenBudget()}
     * 就原样返回——不丢轮次、不截断，与从前逐字一致。超了才按阶梯降级：
     * 先从最早的轮次整条丢（直到回到预算内），仍超才对保留部分做截断（长思考 / 工具结果）。
     * 按<b>轮</b>丢，用户提问与它的回答、工具往返一起走，tool_calls 与结果不会被拆散。
     */
    @Override
    public List<JsonNode> messages(ContextRequest req) {
        List<JsonNode> out = new ArrayList<>();
        for (TurnMessages tm : turnMessages(req)) {
            out.addAll(tm.messages());
        }
        return out;
    }

    /** 一条 Turn 与它在 messages 里贡献的那几条（成对） */
    public record TurnMessages(Turn turn, List<JsonNode> messages) {
    }

    /**
     * 逐 Turn 的回放分组（降级后）：Turn 与其贡献的 messages 成对。
     *
     * <p>与 {@link #messages} 同源——本方法就是它的实现体，只多保留了「哪几条属于哪条 Turn」。
     * 「原始上下文」预览借它把请求体按 Turn 切片（前端据此给每个 Turn 挂「请求 / 响应」按钮），
     * 因此两者的分组与顺序必须逐字一致，改动时务必同步。
     */
    public List<TurnMessages> turnMessages(ContextRequest req) {
        /* 只有前台会话回放历史：子代理没有历史，潜意识走自己的素材 provider（见 STEP 6-③） */
        if (req.surface() != ContextRequest.Surface.CHAT) {
            return List.of();
        }
        int budget = req.historyTokenBudget();
        List<TurnMessages> verbatim = grouped(req.sessionId(), req.contextTrace(), false);
        if (!props.isCompressHistory() || budget <= 0 || fits(verbatim, budget)) {
            return verbatim;
        }
        List<TurnMessages> dropped = dropOldest(verbatim, budget);
        if (fits(dropped, budget)) {
            return dropped;
        }
        /* 丢到最少仍超（保留的轮次本身就大）：对保留部分再截断——这是最后一级降级 */
        return dropOldest(grouped(req.sessionId(), req.contextTrace(), true), budget);
    }

    // ---------- 内部 ----------

    /**
     * 逐轮展开：每个 Turn 一组 messages。分组是为了降级时能整轮丢——工具往返不会被拆散。
     *
     * @param clip 是否截断超长的思考 / 工具结果（只在逼近窗口、且丢轮次不够时才为真）
     */
    private List<TurnMessages> grouped(String sessionId, boolean contextTrace, boolean clip) {
        List<TurnMessages> grouped = new ArrayList<>();
        for (Turn t : turns(sessionId)) {
            List<JsonNode> out = new ArrayList<>();
            emit(out, t, contextTrace, clip);
            if (!out.isEmpty()) {
                grouped.add(new TurnMessages(t, out));
            }
        }
        return grouped;
    }

    /** 总估算是否还在预算内 */
    private static boolean fits(List<TurnMessages> grouped, int budget) {
        return estimateAll(grouped) <= budget;
    }

    /** 从最早的轮次整条丢，直到回到预算内；无论如何保留最近 {@code compressKeepTurns} 轮 */
    private List<TurnMessages> dropOldest(List<TurnMessages> grouped, int budget) {
        long total = estimateAll(grouped);
        int keep = Math.min(Math.max(props.getCompressKeepTurns(), 0), grouped.size());
        int from = 0;
        while (from < grouped.size() - keep && total > budget) {
            total -= estimateGroup(grouped.get(from).messages());
            from++;
        }
        return from == 0 ? grouped : grouped.subList(from, grouped.size());
    }

    /** 全部轮次的估算 token 数 */
    private static long estimateAll(List<TurnMessages> grouped) {
        long sum = 0;
        for (TurnMessages group : grouped) {
            sum += estimateGroup(group.messages());
        }
        return sum;
    }

    /** 一轮的估算 token 数：整条序列化后估算，工具调用参数与思考也一并算进去 */
    private static long estimateGroup(List<JsonNode> group) {
        long sum = 0;
        for (JsonNode m : group) {
            sum += TokenUsageService.estimateTokens(m.toString());
        }
        return sum;
    }

    // ---------- 内部 ----------

    /** 在途尾巴：把正在生成的那条挂到末尾（末尾若是尚未配到回答的用户 Turn，就补成它的输出） */
    private List<Turn> withLiveTail(String sessionId, List<Turn> turns) {
        ChatDtos.LiveRun run = live.snapshot(sessionId);
        if (!run.running()) {
            return turns;
        }
        Turn last = turns.isEmpty() ? null : turns.get(turns.size() - 1);
        boolean unanswered = last != null && last.reply() == null
                && last.trigger().source() == TurnTrigger.Source.USER_INPUT;
        TurnTrigger trigger = unanswered ? last.trigger()
                : TurnTrigger.user(run.userText(), run.userMessageId());
        Turn tail = new Turn(sessionId, trigger,
                new Turn.Reply(null, run.content(), run.reasoning(), run.trace(), List.of()), true);
        if (unanswered) {
            turns.set(turns.size() - 1, tail);
            return turns;
        }
        List<Turn> out = new ArrayList<>(turns);
        out.add(tail);
        return out;
    }

    /**
     * 把一条 Turn 展开成 messages（用户 Turn 先出一条 user 行，再出回答行）。
     *
     * @param clip 是否截断超长的思考 / 工具结果——逼近窗口、且丢轮次不够时才为真；
     *             平时原样回放，不做任何降级
     */
    private void emit(List<JsonNode> out, Turn turn, boolean contextTrace, boolean clip) {
        if (turn.trigger().source() == TurnTrigger.Source.USER_INPUT) {
            out.add(om.createObjectNode().put("role", "user").put("content", turn.trigger().text()));
        }
        if (turn.inFlight()) {
            /* 在途：问题要回放（模型正在回答它），半截回答不回放 */
            return;
        }
        Turn.Reply reply = turn.reply();
        if (reply == null) {
            return;
        }
        if (contextTrace) {
            for (JsonNode round : reply.toolRounds()) {
                out.add(ChatProtocol.withReasoningForToolCalls(clip ? clipRound(round) : round));
            }
            ObjectNode answer = om.createObjectNode().put("role", "assistant").put("content", reply.content());
            if (reply.reasoning() != null && !reply.reasoning().isBlank()) {
                answer.put("reasoning_content",
                        clip ? limit(reply.reasoning(), CONTEXT_REASONING_LIMIT) : reply.reasoning());
            }
            out.add(answer);
        } else {
            out.add(om.createObjectNode().put("role", "assistant").put("content", reply.content()));
        }
    }

    /** 回答行 → Turn 的输出（子代理结论按回放约定补出处头） */
    private Turn.Reply replyOf(MessageRow m) {
        return new Turn.Reply(m.id(), attributed(m), m.reasoning(),
                ChatTraceCodec.readTrace(om, m.trace()), rounds(m.toolRounds()));
    }

    /** 无用户行在前的回答行 → 它的触发源（子代理投回 / 兜底） */
    private TurnTrigger triggerOf(MessageRow m) {
        if (ChatMessageStore.KIND_SUBAGENT.equals(m.kind())) {
            ChatDtos.SubagentBrief brief = ChatProtocol.readSubagentBrief(om, m.kind(), m.meta());
            return new TurnTrigger(TurnTrigger.Source.SUBAGENT_REPORT,
                    brief == null ? null : brief.title(), null);
        }
        return new TurnTrigger(TurnTrigger.Source.SYSTEM, null, null);
    }

    /**
     * 子代理结论 → 带来源头的回放正文：kind=subagent 的消息是子代理跑完投回的，不是主模型写的——
     * 回放时在正文前拼一行出处（标题 + 执行 id，取自 meta 里的气泡元信息），模型跨轮仍知道这段话
     * 来自哪件派出去的活，可正确转述出处、必要时按 id 用 read_subagent_run 核对，而不是把它当成
     * 自己说过的话（对齐 OpenClaw 等异步子代理「完成通知带归因」的做法）。
     * 普通消息原样返回；元信息缺失时退化为无标题/id 的通用标记，归因仍在。
     */
    private String attributed(MessageRow m) {
        if (!ChatMessageStore.KIND_SUBAGENT.equals(m.kind())) {
            return m.content();
        }
        ChatDtos.SubagentBrief brief = ChatProtocol.readSubagentBrief(om, m.kind(), m.meta());
        if (brief == null) {
            return "〔子代理的回报〕\n" + m.content();
        }
        StringBuilder head = new StringBuilder("〔子代理");
        if (brief.title() != null && !brief.title().isBlank()) {
            head.append("「").append(brief.title()).append('」');
        }
        head.append("的回报");
        if (brief.execId() != null) {
            head.append(" · 执行 id=").append(brief.execId());
        }
        return head.append("〕\n").append(m.content()).toString();
    }

    /** 反序列化落库的工具轮原生消息；无 / 解析失败回退空列表（该轮按普通回答回放） */
    private List<JsonNode> rounds(String toolRounds) {
        if (toolRounds == null || toolRounds.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(toolRounds, new TypeReference<List<JsonNode>>() {
            });
        } catch (Exception e) {
            log.warn("工具轮消息反序列化失败（该轮按普通回答回放）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 工具轮回放截断（历史留痕）：只压缩超限的思考（reasoning_content）、tool 结果（role=tool 的
     * content）与调用参数（tool_calls[].function.arguments），tool_calls / tool_call_id 等
     * 协议字段不动——模型只需要记得自己做过什么，不需要原文全量。
     */
    private static JsonNode clipRound(JsonNode round) {
        if (!(round instanceof ObjectNode obj)) {
            return round;
        }
        JsonNode reasoning = obj.path("reasoning_content");
        if (reasoning.asText().length() > CONTEXT_REASONING_LIMIT) {
            obj.put("reasoning_content", limit(reasoning.asText(), CONTEXT_REASONING_LIMIT));
        }
        if ("tool".equals(obj.path("role").asText())) {
            JsonNode result = obj.path("content");
            if (result.isTextual() && result.asText().length() > CONTEXT_TOOL_LIMIT) {
                obj.put("content", limit(result.asText(), CONTEXT_TOOL_LIMIT));
            }
        }
        JsonNode calls = obj.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                if (!(call.path("function") instanceof ObjectNode fn)) {
                    continue;
                }
                JsonNode args = fn.path("arguments");
                if (args.isTextual() && args.asText().length() > CONTEXT_TOOL_LIMIT) {
                    fn.put("arguments", limit(args.asText(), CONTEXT_TOOL_LIMIT));
                }
            }
        }
        return obj;
    }

    /** 单段文本按 max 截断（超限尾部以省略号标记） */
    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
