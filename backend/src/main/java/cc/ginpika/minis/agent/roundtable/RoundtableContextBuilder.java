package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.ai.core.prompt.block.CoordinationBlock;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 成员的 system 组装：人格 + 圆桌协作规程。
 *
 * <p>两段各管一件事，顺序固定：
 * <ol>
 *   <li><b>人格</b>——这位成员自己那套「对话」提示词（{@link AgentPromptService#system(String)}：
 *       对话身份 + 对话增强 + 终端，按它自己的顺序与绑定；没配过的部分回退全局），
 *       决定「你是谁、怎么说话」；</li>
 *   <li><b>协作规程</b>——{@link AgentPromptService#roundtableSystem(String)}（提示词子系统里的
 *       {@code PromptLibrary.CTX_ROUNDTABLE} 上下文，块为 {@code CoordinationBlock}），
 *       决定「在圆桌上怎么做」，并把「你的名字是〈谁〉、哪句话是你说的」讲清：请求里没有
 *       assistant 历史，身份的锚点只有这里。名字由本类把块里的 {@code {{name}}} 换掉。</li>
 * </ol>
 *
 * <p>两段的归属都逐成员：人格与协作规程各自一份版本（成员未配的位回退全局）。system 对
 * <b>同一成员恒定</b>：局面不进这里（见 {@link #situation}），于是它可以被供应商的前缀缓存命中，
 * 而变化的东西统一落在消息末尾那条 user 上——模型的注意力最近处。
 */
@Component
public class RoundtableContextBuilder {

    private final AgentDirectory directory;
    private final AgentPromptService peerPrompts;
    private final TopicStateMachine machine;

    public RoundtableContextBuilder(AgentDirectory directory, AgentPromptService peerPrompts,
                              TopicStateMachine machine) {
        this.directory = directory;
        this.peerPrompts = peerPrompts;
        this.machine = machine;
    }

    /**
     * 一次发言的完整 system：人格（成员自己的提示词）+ 协作规程（全桌共用一份，不随局面变化）。
     *
     * <p>{@code peerId} 为空是极端兜底（发言人上下文缺失）：这时取不到「它自己的人格」，
     * 只给一份按笼统称呼填好的协作规程。
     */
    public String system(String peerId) {
        String coordination = fillName(peerPrompts.roundtableSystem(peerId), directory.nameOf(peerId));
        if (peerId == null) {
            return coordination;
        }
        return peerPrompts.system(peerId) + "\n\n---\n\n" + coordination;
    }

    /**
     * 这位成员自己那套「对话」提示词（身份 + 增强 + 终端），<b>不含协作规程</b>。
     *
     * <p>一对一私聊（会话里只剩这一位成员）时用它：那时没有「在圆桌上怎么做」这回事，
     * 协作规程里的话题、发言权交代只会添乱。
     */
    public String persona(String peerId) {
        return peerId == null ? "" : peerPrompts.system(peerId);
    }

    /** 协作规程里的 {@code {{name}}} 换成这位成员的真名；名字未知时给个笼统的称呼 */
    private static String fillName(String coordination, String name) {
        String filled = name == null || name.isBlank() ? "某位成员" : name;
        return coordination.replace(CoordinationBlock.NAME, filled);
    }

    /**
     * 此刻局面（{@code <situation>}）：话题状态 + 你自己 + 全员状态 + 一句话说明。
     *
     * <p>用 XML 而不是一行行「- 本话题：…」，是为了让「话题 / 我 / 大家」这几类内容在结构上就分开：
     * 状态进属性、交代进元素文本，模型不必从散文里挑字段。
     */
    public String situation(TopicRow topic, String peerId) {
        StringBuilder sb = new StringBuilder(RoundtablePrompts.SITUATION_HEAD).append('\n');
        sb.append("  <topic status=\"").append(statusText(topic.status())).append('"');
        if (topic.resolveRound() > 0) {
            sb.append(" reopened=\"").append(topic.resolveRound()).append('"');
        }
        sb.append("/>\n");

        Map<String, PeerState> states = machine.states(topic.id());
        PeerState mine = states.getOrDefault(peerId, PeerState.ACTIVE);
        sb.append("  <you name=\"").append(directory.nameOf(peerId)).append("\" state=\"")
                .append(mine.label()).append("\">").append(nudge(mine)).append("</you>\n");

        /* 本次会话的成员全员都列出来（含「非活跃」= 还没入场）：模型要能看见「这张桌子上都有谁、
           各自什么状态」。只列已开口的人，它就不知道还有谁在听、谁已经投了结束票 */
        sb.append("  <members>\n");
        for (AgentRow peer : directory.peers(topic.sessionId())) {
            PeerState state = states.getOrDefault(peer.id(), PeerState.INACTIVE);
            sb.append("    <member name=\"").append(peer.name()).append("\" state=\"")
                    .append(state.label()).append('"');
            if (peer.id().equals(peerId)) {
                sb.append(" you=\"true\"");
            }
            sb.append("/>\n");
        }
        sb.append("  </members>\n");
        sb.append("  <legend>「提议结束」= 已表态可以收场；「沉默」= 这一轮不再开口；"
                + "「等待用户」= 全桌暂停、等用户发言；「非活跃」= 还没入场。</legend>\n");
        sb.append("  <note>以上是你此刻需要遵守的场面：说话顺序、谁在什么状态，都以这里为准，"
                + "不要自己假设还有别人在等你说完。</note>\n");
        return sb.append(RoundtablePrompts.SITUATION_TAIL).toString();
    }

    /** 该状态下的动作提示：把「可以说的话」与「该按的按钮」对上（{@code <you>} 元素的文本） */
    private static String nudge(PeerState state) {
        return switch (state) {
            case PROMPTED -> "系统要求你立刻表态：这一轮必须在「发言」与 propose_end 之间二选一，"
                    + "不能不出声，此时也不能 go_silent。";
            case SILENT -> "你已转入沉默，本轮不必开口。用户再说一句话就开启新一轮，届时你会被重新叫到。";
            case PROPOSE_END -> "你已提议结束。若有人重新发言把你撤回，你会再被叫到一次。";
            case AWAIT_USER -> "你正在等用户表态，全桌已暂停。用户已经回应，接着往下说即可。";
            default -> "现在轮到你发言。要说的每一句话都用 speak 工具说出来（可以边说边想、分几次说）；"
                    + "没什么要说的就别硬找话说——go_silent 直接转入沉默，一个字都不必说。"
                    + "最后用一次工具调用表明去向：话说完、可以收场了就 propose_end，"
                    + "还想再听听就 go_silent（只在 speak 或正文里说都不算表态）。"
                    + "收尾信封里务必带上 desire（0~10 的下一轮发言欲望）——它决定下一轮谁先开口。";
        };
    }

    private static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "closing" -> "进行中（正在向沉默者确认）";
            case "waiting" -> "已暂停（等用户发言）";
            case "closed" -> "已结束";
            default -> "进行中";
        };
    }
}
