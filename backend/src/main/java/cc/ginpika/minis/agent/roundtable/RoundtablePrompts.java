package cc.ginpika.minis.agent.roundtable;

/**
 * 多智能体调度文书的词汇：公开记录与局面的措辞（{@code <dispatch> / <transcript> / <situation>}）。
 *
 * <p>这里只剩「调度器交给成员的那条 user 长什么样」——圆桌协作规程（system 的第二段）已迁进提示词子系统，
 * 见 {@code CoordinationBlock} 与 {@link cc.ginpika.minis.ai.core.prompt.PromptLibrary#CTX_ROUNDTABLE}；
 * 它不再是代码里的常量，而是智能体内部页里可见、可覆盖的一个提示词块。
 *
 * <p>留下的这组标签仍<b>刻意不进提示词库</b>：它们不是提示词，而是运行时素材（同
 * {@code HeartbeatPrompts.HEARTBEAT} 的地位）——{@code RoundtableChatService} 按这些标签把公开记录与
 * 此刻局面拼进那条 user，{@code RoundtableContextBuilder} 也按 {@code SITUATION_HEAD/TAIL} 拼局面。
 * 改标签等于改代码与提示词之间的接口，因此它们不在提示词页里出现。
 */
public final class RoundtablePrompts {

    private RoundtablePrompts() {
    }

    /** 那条 user 的外框：一份调度文书，公开记录与此刻局面各占一个元素，内容类型一眼可辨 */
    public static final String DISPATCH_HEAD = "<dispatch>";
    public static final String DISPATCH_TAIL = "</dispatch>";

    /** 公开记录：本场对话的往来（{@code <message speaker="谁">}）与圆桌上的动作（{@code <event>}） */
    public static final String TRANSCRIPT_HEAD = "<transcript>";
    public static final String TRANSCRIPT_TAIL = "</transcript>";

    /** 此刻局面：话题状态、你自己、全员状态（内容由 {@code RoundtableContextBuilder#situation} 拼） */
    public static final String SITUATION_HEAD = "<situation>";
    public static final String SITUATION_TAIL = "</situation>";

    /**
     * 共享任务区：全桌没做完的条目（{@code <todo status="..." owner="...">标题</todo>}）。
     *
     * <p>不摊在桌面上，讨论就看不见「哪条受阻、还剩什么没做」——成员会只顾着回应人的那句话，
     * 受阻条目与后续交付就断在那里（见 {@code RoundtableContextBuilder#boardBlock}）。
     */
    public static final String BOARD_HEAD = "<board>";
    public static final String BOARD_TAIL = "</board>";
}
