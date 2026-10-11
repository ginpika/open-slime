package cc.ginpika.minis.agent.context;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * 组装一次对话补全请求体所需的输入（STEP 2 上下文平面）。
 *
 * <p>它是「这次要组装什么」的纯值：模型名、system 基底（身份提示词 + 对话增强，由调用方按提示词位
 * 取好）、会话与开关（历史回放要不要按原生协议展开）、以及这次可用的工具 schema。各
 * {@link ContextProvider} 据此现取素材、追加自己的那一段，组装结果与调用方无关——于是「组装 messages」
 * 从业务代码里彻底独立出来。
 *
 * @param model        本次发往供应商的模型名
 * @param systemBase   system 消息的基底正文（身份提示词 + 对话增强；不含各源追加的尾段）
 * @param sessionId    会话 id（历史源据此取历史，在跑子代理源据此取进度，子代理面据此取共享黑板）
 * @param contextTrace 本会话是否把历史思考与工具调用按原生协议回灌（「…」菜单开关）
 * @param userQuery    本会话最近一条用户输入（知识检索 / 记忆注入等主动召回源据此取 top-k；
 *                     无历史时为空串）
 * @param historyTokenBudget 本次历史最多可占的 token（调用方按「窗口 − 输出预留 − 安全余量 −
 *                     system 与工具面估算」算好）：上下文平面超过它才降级——从最早的轮次整条丢、
 *                     仍超再截断（见 {@code agent.chat.turn.History}）。≤0 表示不限
 * @param toolSchemas  本次可用的工具定义数组（工具面的选择仍在调用方，分层路由见架构文档 STEP 3）
 * @param surface      这次组装属于哪条流（见 {@link Surface}）：各 {@link ContextProvider} 据此早退——
 *                     会话历史 / 在跑子代理 / 知识 / 记忆 / 待办只服务前台会话，子代理面与潜意识面各有
 *                     自己的 provider。不写 surface 时按 {@link Surface#CHAT}，于是现有调用点与测试
 *                     一行不用改（见便捷构造）。
 */
public record ContextRequest(String model, String systemBase, String sessionId,
                             boolean contextTrace, String userQuery, int historyTokenBudget,
                             ArrayNode toolSchemas, Surface surface) {

    /**
     * 一次组装属于哪条流。三条流（前台会话 / 子代理 / 心跳后台）各有自己的上下文源；
     * 共用 {@link ContextBuilder} 的骨架，但素材互不串门。
     */
    public enum Surface {
        /** 前台会话（含圆桌的会话式复写）：历史 / 在跑子代理 / 知识 / 记忆 / 待办全量参与 */
        CHAT,
        /** 子代理：独立上下文，只有「此前同伴的发现」（共享黑板）与当前时间 */
        SUBAGENT,
        /** 心跳后台：某 Agent 的心跳主会话与常驻职责；素材由模型自己调工具现取 */
        HEARTBEAT
    }

    /**
     * 便捷构造：不写 surface 时按 {@link Surface#CHAT}——等效抽取阶段（STEP 2）与前台会话、圆桌、
     * 历史回放测试都走这一条，语义与加 surface 之前逐字一致。
     */
    public ContextRequest(String model, String systemBase, String sessionId,
                          boolean contextTrace, String userQuery, int historyTokenBudget,
                          ArrayNode toolSchemas) {
        this(model, systemBase, sessionId, contextTrace, userQuery, historyTokenBudget,
                toolSchemas, Surface.CHAT);
    }
}
