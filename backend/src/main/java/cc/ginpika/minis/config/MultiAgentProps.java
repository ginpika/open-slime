package cc.ginpika.minis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多智能体模块配置（圆桌的规模、护栏与选人规则）。
 *
 * <p>圆桌上「说些什么」是成员自己按信号决定的，不是代码排的班；所以规模相关的旋钮都是
 * <b>上限</b>——用来兜住跑飞与死循环，而不是用来编排内容。三处上限各管一件事：
 * 单次发言的工具往返（一次发言能查多少）、一轮推进的发言次数（人说完一句话后最多自动接几轮）、
 * 重开次数（「沉默者发言 → 全员重新决策」这条回路最多转几圈）。
 *
 * <p>两处例外，都不编排内容，只改<b>呈现</b>：
 * <ul>
 *   <li>{@link #speakerStrategy}——决定「谁下一个开口」，但那只是<b>顺序</b>：可替换的
 *       {@code SpeakerStrategy}（顺序轮转 / 随机 / 发言欲望优先队列），换一个实现即换一种坐法；</li>
 *   <li>{@link #speechCharsPerSecond} / {@link #speechMaxDelayMillis}——决定「一段话多久才到屏幕上」，
 *       即把 LLM 的生成速度折算成人打字的节奏（见 {@code SpeechPacer}），免得整段砸屏。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "minis.multi-agent")
public class MultiAgentProps {

    /**
     * 应答者策略的缺省值：这一轮该谁说话。会话页的「⋯」菜单可以逐次覆盖它（见
     * {@code MultiAgentDtos.SendRequest#speakerStrategy}），这里只是「没人指定时用哪个」。
     *
     * <p>{@code rotation}（默认）= 固定顺序轮转（见 {@code RotationSpeakerStrategy}）：按名册顺序
     * 依次交接，可复盘、可复现。{@code random} = 随机（见 {@code RandomSpeakerStrategy}）：每次在
     * 可发言的人里掷骰子。{@code desire} = 发言欲望优先队列（见 {@code DesireSpeakerStrategy}）：
     * 每位成员说完报一个下一轮发言欲望（0~10），欲望高的先开口，平手随机。
     */
    private String speakerStrategy = "rotation";

    /**
     * 单次发言的工具往返上限：超过后撤掉 tools 强制成员把话说出来。
     *
     * <p>说话（speak）本身也占轮次——「说一句、查一下、再说一句」才像在聊天，所以这里的额度
     * 要容得下边说边想，而不只是查资料。
     */
    private int maxToolRounds = 16;

    /** 人一次发言之后，最多自动推进几轮成员发言（每一轮都是一次完整的 LLM 往返） */
    private int maxSpeechesPerTurn = 8;

    /**
     * 「沉默者发言 → 全员重新决策」最多重开几次。
     *
     * <p>这条回路本身可以无限转（沉默者一发言就重开，重开后大家又都提议结束），
     * 所以必须封顶；到顶之后按「沉默者也提议结束」收束（见 MaTopicService#settle）。
     */
    private int maxResolveRounds = 2;

    /** 成员上下文里最多带多少条话题消息（共享议题历史，太长时只保留最近若干条） */
    private int historyLimit = 128;

    /**
     * 发言的「打字」速度（字 / 秒）：一段发言要等够它「打出来」的时间，才推给前端。
     *
     * <p>LLM 生成比人打字快得多，一段话常常整段砸到屏幕上；圆桌上几位成员连着说时更是几条一起冒出来。
     * 按这个速度把每段发言的等待时间折算出来（见 {@code SpeechPacer}），消息就一条一条地到。
     * 0 或负数 = 不节流，原样即时推送。
     */
    private int speechCharsPerSecond = 20;

    /**
     * 单条发言最多等多久（毫秒）：长发言按字数线性等下去会等到天荒地老（1000 字 ≈ 25 秒），所以封顶。
     *
     * <p>它不是「速度」，只是兜住极端值——正常长度（一两百字）的发言够不着这个上限。
     */
    private long speechMaxDelayMillis = 3000;

    public String getSpeakerStrategy() {
        return speakerStrategy;
    }

    public void setSpeakerStrategy(String speakerStrategy) {
        this.speakerStrategy = speakerStrategy;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    public int getMaxSpeechesPerTurn() {
        return maxSpeechesPerTurn;
    }

    public void setMaxSpeechesPerTurn(int maxSpeechesPerTurn) {
        this.maxSpeechesPerTurn = maxSpeechesPerTurn;
    }

    public int getMaxResolveRounds() {
        return maxResolveRounds;
    }

    public void setMaxResolveRounds(int maxResolveRounds) {
        this.maxResolveRounds = maxResolveRounds;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public int getSpeechCharsPerSecond() {
        return speechCharsPerSecond;
    }

    public void setSpeechCharsPerSecond(int speechCharsPerSecond) {
        this.speechCharsPerSecond = speechCharsPerSecond;
    }

    public long getSpeechMaxDelayMillis() {
        return speechMaxDelayMillis;
    }

    public void setSpeechMaxDelayMillis(long speechMaxDelayMillis) {
        this.speechMaxDelayMillis = speechMaxDelayMillis;
    }
}
