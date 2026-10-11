package cc.ginpika.minis.agent.roundtable;

/**
 * 发言状态（Communication State）——设计一 §四的第一个维度。
 *
 * <p>它只回答一件事：<b>这位成员此刻向不向讨论室发言</b>。它与「任务在不在跑」是两回事，
 * 所以不再用一个枚举把两件事压在一起（原先的 {@code ACTIVE / SILENT} 回答不了
 * 「它是在生成、等工具，还是只是暂时不说话」）。
 *
 * <p>与 {@link ExecutionState} 组合出的典型状态见 {@link PeerStatus}。
 */
public enum CommunicationState {

    /** 发言中：持有发言权，正在向讨论室说话 */
    SPEAKING("发言中"),
    /** 在听：不发言，但保持在场、可被点名 */
    LISTENING("在听"),
    /** 不发言：这一轮明确不说话（继续听，但本轮不再被叫到） */
    SILENT("不发言");

    private final String label;

    CommunicationState(String label) {
        this.label = label;
    }

    /** 中文标签（前端与状态块展示用） */
    public String label() {
        return label;
    }
}
