package cc.ginpika.minis.agent.chat;

/** 流式阶段回调（按发生顺序调用）；默认空实现，调用方只覆写关心的阶段 */
public interface ChatStream {
    /** The homepage's live transport accepts more user messages before the turn finishes. */
    default boolean steerable() {
        return false;
    }

    /** 发起这条流的已认证用户，用来校验补充消息的发起方。 */
    default String actorId() {
        return null;
    }

    /** user 消息已落库：sessionId=实际会话，messageId=该条提问的行 id */
    default void meta(String sessionId, long messageId) {
    }

    /** 进入第 round 轮补全（>0 为工具轮之后的新一轮，前端应清空已渲染的正文） */
    default void round(int round) {
    }

    /** 模型产出的增量片段：reasoning=思维链、content=正文，缺一为 null */
    default void delta(String reasoning, String content) {
    }

    /** 模型请求调用工具：name=工具名，args=调用参数（JSON 字符串） */
    default void tool(String name, String args) {
    }

    /** 一次工具执行已经开始，或者已经退出执行。target 仅含适合界面展示的参数。 */
    default void toolActivity(String callId, String name, String target, boolean running) {
    }

    /** 已保存到本轮的补充用户消息。 */
    default void steer(long messageId, String input) {
    }

    /** 执行仍在进行时已落库的阶段回复。 */
    default void interim(long messageId, String content) {
    }

    /** 丢弃指定模型轮次中已推送的临时正文和思考，保留先前的工作记录。 */
    default void reset(int round) {
    }

    /**
     * 又开口说了一段：text 就是这一段话本身。
     *
     * <p><b>只有圆桌用得上</b>（默认空实现，对话页不会收到）：那里成员可以边说边想，
     * 一次发言由好几个段落组成——每段各占一个气泡，所以「一段的边界」得有个独立信号，
     * 不能混在 {@link #delta} 里靠换行去猜。它一旦出现，此前的正文就到此为止。
     */
    default void speech(String text) {
    }

    /** 用户主动停止且无任何正文产出：向上抛出中断生成，Controller 静默收尾（不推 error 事件） */
    public static final class ChatCancelledException extends RuntimeException {
    }
}
