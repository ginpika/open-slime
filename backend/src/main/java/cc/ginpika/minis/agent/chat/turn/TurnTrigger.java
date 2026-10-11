package cc.ginpika.minis.agent.chat.turn;

/**
 * 一次 Turn 的「触发」——Turn 的头。
 *
 * <p>用户输入只是触发源之一：子代理报告、心跳、计划同样会激活会话，并要求 Agent 给出 reaction。
 * 把触发源显式收在这里，替代过去靠 {@code chat_message.kind} / {@code meta} 零散表达的做法。
 *
 * @param source    触发源
 * @param text      触发文本（用户提问原文；非用户触发且无原文时为 null）
 * @param messageId 触发落库的那条消息行 id（用户提问用；无对应行时为 null）
 */
public record TurnTrigger(Source source, String text, Long messageId) {

    /** 触发源 */
    public enum Source {
        /** 用户提问：会话由此创建或激活 */
        USER_INPUT,
        /** 子代理结论投回 */
        SUBAGENT_REPORT,
        /** 心跳（潜意识）节拍 */
        HEARTBEAT,
        /** 计划实例执行 */
        PLAN,
        /** 兜底：库里出现一条没有归属触发者的历史回答 */
        SYSTEM
    }

    /** 用户提问触发（最常用的一条构造） */
    public static TurnTrigger user(String text, Long messageId) {
        return new TurnTrigger(Source.USER_INPUT, text, messageId);
    }

    /** 落库字符串 → 触发源；认不出的一律按 {@link Source#SYSTEM}（旧库 / 手改数据也不至于崩） */
    public static Source of(String stored) {
        if (stored == null || stored.isBlank()) {
            return Source.SYSTEM;
        }
        try {
            return Source.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return Source.SYSTEM;
        }
    }
}
