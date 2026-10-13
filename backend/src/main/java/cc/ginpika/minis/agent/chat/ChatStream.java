package cc.ginpika.minis.agent.chat;

/** 流式阶段回调（按发生顺序调用）；默认空实现，调用方只覆写关心的阶段 */
public interface ChatStream {
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
