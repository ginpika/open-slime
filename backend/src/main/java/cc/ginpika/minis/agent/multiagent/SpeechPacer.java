package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.config.MultiAgentProps;
import org.springframework.stereotype.Component;

/**
 * 发言节流：一段话该等多久才推到前端，以及真的等在那里。
 *
 * <p><b>要解决什么</b>：LLM 生成比人打字快得多。成员用 speak 工具说出一整段话时，那一整段是
 * <b>一次</b>推给页面的（见 {@code MaChatService.SpeechSink#say}），于是屏幕上会「啪」地整段出现；
 * 一次发言里说了好几段、或几位成员连着说时，几条更是一起冒出来。
 *
 * <p><b>怎么算</b>：把这段发言按 {@link MultiAgentProps#getSpeechCharsPerSecond 打字速度}折算成
 * 「人把它敲出来要多久」，推送前先等这么久——消息于是按打字的节奏一条一条到，而不是一次性刷屏。
 * 等待发生在发言这条线程上（圆桌每轮本来就在自己的虚拟线程里跑），不另开线程、不会积压，
 * 流一结束等待也就结束了。
 *
 * <p><b>代价是整轮会变长</b>：一段 200 字的话按 40 字/秒要多等 5 秒。所以有
 * {@link MultiAgentProps#getSpeechMaxDelayMillis 单条封顶}兜住长发言，也随时可以把
 * {@code minis.multi-agent.speech-chars-per-second} 调大或设为 0 关掉节流。
 *
 * <p>节流只针对发言本身：思考增量、工具调用、状态快照与话题动作行照旧即时透传——它们要么本来就
 * 是逐字流的，要么是「系统正在做什么」的即时反馈，压后反而让人以为卡住了。
 */
@Component
public class SpeechPacer {

    private final MultiAgentProps props;

    public SpeechPacer(MultiAgentProps props) {
        this.props = props;
    }

    /**
     * 按这段发言的字数等一会儿，再让调用方把它推出去。
     *
     * <p>被中断（人按了停止、或客户端断开后线程被收走）就立刻返回，不再等——此时尽快把流程
     * 收尾比把这段等完重要。中断标志照旧留给上层看见。
     *
     * @return 实际等待的毫秒数（留痕 / 观测用）
     */
    public long pace(String text) {
        long millis = delayMillis(text);
        if (millis <= 0) {
            return 0;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return millis;
    }

    /**
     * 这段发言按当前速度要等多久（毫秒）：字数 ÷ 速度，再封顶。
     *
     * <p>不节流（速度为 0 / 负数）、没有正文、封顶为 0 时都返回 0（调用方据此跳过等待）。
     */
    public long delayMillis(String text) {
        int charsPerSecond = props.getSpeechCharsPerSecond();
        long cap = Math.max(0, props.getSpeechMaxDelayMillis());
        if (text == null || text.isBlank() || charsPerSecond <= 0) {
            return 0;
        }
        long millis = Math.ceilDiv((long) text.strip().length() * 1000L, charsPerSecond);
        return Math.min(millis, cap);
    }
}
