package cc.ginpika.minis.agent.multiagent;

import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import cc.ginpika.minis.config.MultiAgentProps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 选人入口：这一轮该谁说话。
 *
 * <p>它自己只做两件与「排序规则」无关的事——点名（{@link #addressed}）与会话里带 {@code @} 的长名优先
 * 匹配；具体「谁下一个开口」交给<b>应答者策略</b>（{@link SpeakerStrategy}）。策略由
 * {@code minis.multi-agent.speaker-strategy} 给出缺省值、由每一次发言的请求逐次覆盖
 * （见 {@link #resolve}）：
 * <ul>
 *   <li><b>点名</b>——话里 @ 了某人：就让他应答（{@link #addressed}，不经过策略）；</li>
 *   <li><b>无指向</b>——没 @ 任何人：由策略的 {@code pickOpener} 定一位开场；</li>
 *   <li><b>往下轮</b>——某位说完之后：由策略的 {@code pickNext} 把话头交出去。</li>
 * </ul>
 *
 * <p>三种策略，取舍已写在 {@link SpeakerStrategy} 上：{@code rotation}（固定顺序轮转，缺省）、
 * {@code random}（随机）与 {@code desire}（发言欲望优先队列）。被点到的人是谁，每次都写进话题事件，
 * 事后可回放。
 */
@Component
public class SpeakerPicker {

    /** 全部策略，按标识索引（每次选人按 key 取用） */
    private final Map<String, SpeakerStrategy> byKey = new LinkedHashMap<>();
    /** 缺省策略标识：请求没指定、或指定了一个不认识的标识时用它 */
    private final String defaultKey;

    public SpeakerPicker(List<SpeakerStrategy> strategies, MultiAgentProps props) {
        for (SpeakerStrategy strategy : strategies) {
            byKey.put(strategy.key(), strategy);
        }
        String configured = props.getSpeakerStrategy() == null ? "" : props.getSpeakerStrategy().strip();
        /* 配置写错了当场失败：那是部署时就该发现的问题，不该拖到某次发言才暴露 */
        if (!byKey.containsKey(configured)) {
            throw new IllegalStateException("未知的应答者策略: " + configured
                    + "（可选：" + byKey.keySet() + "）");
        }
        this.defaultKey = configured;
    }

    /** 缺省策略标识（没人指定时用哪个） */
    public String defaultKey() {
        return defaultKey;
    }

    /**
     * 取这一次发言要用的策略：请求给了一个认识的标识就用它，否则（没给 / 不认识）回退缺省。
     *
     * <p>对请求值宽容、对配置值严格（见构造器）：请求来自界面，一个过时的标识不该让整次发言 500。
     */
    public SpeakerStrategy resolve(String key) {
        if (key == null || key.isBlank()) {
            return byKey.get(defaultKey);
        }
        return byKey.getOrDefault(key.strip(), byKey.get(defaultKey));
    }

    /**
     * 话里有没有 @ 到名册里的人；有就返回它（长名字优先匹配，避免「张三丰」被「张三」抢先命中）。
     */
    public Optional<PeerRow> addressed(String text, List<PeerRow> peers) {
        if (text == null || text.isBlank() || peers.isEmpty()) {
            return Optional.empty();
        }
        List<PeerRow> byLength = new ArrayList<>(peers);
        byLength.sort(Comparator.comparingInt((PeerRow p) -> p.name().length()).reversed());
        for (PeerRow peer : byLength) {
            if (text.contains("@" + peer.name())) {
                return Optional.of(peer);
            }
        }
        return Optional.empty();
    }
}
