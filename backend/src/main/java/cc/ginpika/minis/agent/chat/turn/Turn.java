package cc.ginpika.minis.agent.chat.turn;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 一条 Turn：对话历史的可重现单元 = 触发（头） + Agent 的输出（可缺）。
 *
 * <p><b>不变量</b>：Turn 是协议闭合的最小单位——{@link Reply#toolRounds()} 里 assistant 的
 * {@code tool_calls} 与其 tool 结果必须配对、同生同灭；回放就是把 Turn 序列按序展开成 messages
 * （见 {@link History#replay}），不必再猜边界。
 *
 * <p>子代理 / 计划将来是它的<b>子 Turn</b>（完成即触发父会话的一条新 Turn，「再激活」由此进模型）——
 * 嵌套字段随子代理那一步一并落下，此处先不建空位。
 *
 * @param sessionId 归属会话
 * @param trigger   触发（头）
 * @param reply     Agent 的输出；尚未产出时为 null（未配到回答的用户 Turn、或该触发源暂无 reaction）
 * @param inFlight  是否尚未落定（在途生成中的那条尾巴）；回放只带上它的问题，不带半截回答
 */
public record Turn(String sessionId, TurnTrigger trigger, Reply reply, boolean inFlight) {

    /**
     * Agent 对这一 Turn 的输出。
     *
     * @param messageId 落库的回答行 id；在途（尚未落定）时为 null
     * @param content   回答正文（子代理结论已按回放约定带上出处头）
     * @param reasoning 思维链纯文本
     * @param segments  结构化分段（思考 / 工具），供前端分阶段折叠回放
     * @param toolRounds 本轮工具往返的原生消息（assistant 含 tool_calls + 配对 tool 结果）
     */
    public record Reply(Long messageId, String content, String reasoning,
                        List<ChatDtos.TraceSegment> segments, List<JsonNode> toolRounds) {
    }

    /** 已落定的 Turn（非在途） */
    public static Turn landed(String sessionId, TurnTrigger trigger, Reply reply) {
        return new Turn(sessionId, trigger, reply, false);
    }
}
