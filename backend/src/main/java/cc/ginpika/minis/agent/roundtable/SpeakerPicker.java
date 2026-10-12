package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.config.RoundtableProps;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 选人入口：这一轮该谁说话。
 *
 * <p>它自己只做两件与「排序规则」无关的事——点名（{@link #addressed}）与会话里带 {@code @} 的长名优先
 * 匹配；具体「谁下一个开口」交给<b>应答者策略</b>（{@link SpeakerStrategy}）。策略由
 * {@code minis.roundtable.speaker-strategy} 给出缺省值、由每一次发言的请求逐次覆盖
 * （见 {@link #resolve}）：
 * <ul>
 *   <li><b>点名</b>——话里 @ 了某（几）位成员：就让它们依次应答（{@link #addressed}，不经过策略）；</li>
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

    public SpeakerPicker(List<SpeakerStrategy> strategies, RoundtableProps props) {
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
     * 话里 @ 到的所有成员（按在话里出现的先后排序；长名字优先匹配，避免「张三丰」被「张三」抢先命中）。
     *
     * <p>支持一次 @ 多位：从每个 {@code @} 起，用最长的成员名去匹配那一段（匹配到就跳过这个名字的长度），
     * 于是「@张三丰」不会被「张三」切走，也不会重复命中同一个人。没 @ 到任何人就返回空表——
     * 那时这一轮照常由应答者策略定开场（见 {@link SpeakerStrategy}）。
     */
    public List<AgentRow> addressed(String text, List<AgentRow> peers) {
        List<AgentRow> hits = new ArrayList<>();
        if (text == null || text.isBlank() || peers.isEmpty()) {
            return hits;
        }
        /* 长名优先：同一个位置能匹配多个名字时取最长的那个 */
        List<AgentRow> byLength = new ArrayList<>(peers);
        byLength.sort(Comparator.comparingInt((AgentRow p) -> p.name().length()).reversed());
        int i = 0;
        while ((i = text.indexOf('@', i)) >= 0) {
            String rest = text.substring(i + 1);
            AgentRow hit = null;
            for (AgentRow peer : byLength) {
                String name = peer.name();
                if (!name.isBlank() && rest.startsWith(name)) {
                    hit = peer;
                    break;
                }
            }
            if (hit == null) {
                i += 1;
                continue;
            }
            AgentRow target = hit;
            if (hits.stream().noneMatch(h -> h.id().equals(target.id()))) {
                hits.add(target);
            }
            i += 1 + target.name().length();
        }
        return hits;
    }
}
