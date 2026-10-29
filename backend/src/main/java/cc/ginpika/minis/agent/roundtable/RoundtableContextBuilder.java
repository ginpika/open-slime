package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicStore.TopicRow;
import cc.ginpika.minis.ai.core.prompt.block.CoordinationBlock;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import cc.ginpika.minis.config.RoundtableProps;
import cc.ginpika.minis.stats.TokenUsageService;
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
    private final RoundtableMessageStore maMessages;
    private final RoundtableTodoStore taskBoard;
    private final RoundtableProps props;
    private final cc.ginpika.minis.project.ProjectKnowledgeStore knowledge;

    public RoundtableContextBuilder(AgentDirectory directory, AgentPromptService peerPrompts,
                              TopicStateMachine machine, RoundtableMessageStore maMessages,
                              RoundtableTodoStore taskBoard, RoundtableProps props,
                              cc.ginpika.minis.project.ProjectKnowledgeStore knowledge) {
        this.directory = directory;
        this.peerPrompts = peerPrompts;
        this.machine = machine;
        this.maMessages = maMessages;
        this.taskBoard = taskBoard;
        this.props = props;
        this.knowledge = knowledge;
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

    /**
     * 调度器交给这位成员的那条 user：一份调度文书（{@code <dispatch>}），里面两段——
     * {@code <transcript>} 是本场对话的公开记录，{@code <situation>} 是此刻局面。
     *
     * <p>分节是为了把「谁说了什么」「圆桌上发生过什么」「此刻谁是什么状态」在结构上分开：
     * 三类内容混在一段散文里时，模型只能靠「谁：」这种前缀去猜哪句是发言、哪句是动作。
     *
     * @param budget 公开记录能占的 token 预算（已扣除 system、工具面与输出预留）
     */
    public String dispatch(String sessionId, int budget, TopicRow topic, String peerId) {
        StringBuilder sb = new StringBuilder(RoundtablePrompts.DISPATCH_HEAD).append('\n');
        String pinned=pinnedContext(sessionId);
        sb.append(pinned);
        sb.append(RoundtablePrompts.TRANSCRIPT_HEAD).append('\n');
        for (String line : transcriptLines(sessionId, Math.max(0,budget-(int)TokenUsageService.estimateTokens(pinned)))) {
            sb.append(line).append('\n');
        }
        sb.append(RoundtablePrompts.TRANSCRIPT_TAIL).append('\n');
        if (topic != null) {
            sb.append(situation(topic, peerId)).append('\n');
            /* 顺手把共享任务区摊在桌面上：不摊，讨论就看不见「哪条受阻、还有什么没做」——
               成员只会回应人的那句话，受阻条目与后续交付就断在这里（踩过这个坑） */
            String board = boardBlock(topic.sessionId());
            if (!board.isEmpty()) {
                sb.append(board).append('\n');
            }
        }
        return sb.append(RoundtablePrompts.DISPATCH_TAIL).toString();
    }

    /**
     * 共享任务区的快照：包括已结束与放弃的条目，主控才能在新要求下复用原条目。
     *
     * <p>受阻条目会带上原因——那正是这轮讨论要处理的事。
     */
    private String boardBlock(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(RoundtablePrompts.BOARD_HEAD).append('\n');
        boolean any = false;
        for (RoundtableTodoStore.TodoRow t : taskBoard.list(sessionId)) {
            any = true;
            sb.append("  <todo id=\"").append(t.id()).append("\" status=\"").append(t.status()).append('"');
            if (t.ownerPeer() != null) {
                sb.append(" owner=\"").append(nameOr(t.ownerPeer())).append('"');
            }
            sb.append('>').append(t.title());
            if (t.detail() != null) sb.append("（要求：").append(t.detail()).append('）');
            if (t.dependsOn() != null) sb.append("（前置ID：").append(t.dependsOn()).append('）');
            if (t.result() != null) sb.append("（产出：").append(t.result()).append('）');
            if (t.blockReason() != null && !t.blockReason().isBlank()) {
                sb.append("（检查或受阻意见：").append(t.blockReason()).append('）');
            }
            sb.append("</todo>\n");
        }
        return any ? sb.append(RoundtablePrompts.BOARD_TAIL).toString() : "";
    }

    /** Durable goal and latest issue conclusions survive the rolling transcript window. */
    public String pinnedContext(String sessionId) {
        var rows = maMessages.bySession(sessionId);
        StringBuilder out = new StringBuilder("<task_context>\n");
        var seed = rows.stream().filter(m -> "task_context".equals(m.eventKind()) || "issue_context".equals(m.eventKind())).findFirst();
        var users = rows.stream().filter(m -> "user".equals(m.role())).toList();
        seed.or(() -> users.stream().findFirst()).ifPresent(m -> out.append(m.content()).append('\n'));
        out.append("最近的用户要求（在原目标之上生效）：\n");
        users.stream().skip(Math.max(0, users.size() - 2)).forEach(m -> out.append(m.content()).append('\n'));
        var conclusions = rows.stream().filter(m -> "issue_resolved".equals(m.eventKind())).toList();
        rows.stream().filter(m -> "workspace_binding".equals(m.eventKind())).reduce((a,b) -> b)
                .ifPresent(m -> out.append(m.content()).append('\n'));
        conclusions.stream().skip(Math.max(0, conclusions.size() - 4))
                .forEach(m -> out.append(m.content()).append('\n'));
        return out.append("</task_context>\n").append(knowledge.context(sessionId)).toString();
    }

    /**
     * 本次回放哪些公开记录行：先按条数封顶，再从<b>最新往回</b>按 token 预算收。
     *
     * <p>「从最新往回」是关键：圆桌的记录里既有人的话、也有各成员的发言与状态动作，
     * 越靠后越相关。窗口的选取规则抽在 {@link #windowOf}（纯函数，便于单测）。
     */
    public String conversationTranscript(String sessionId, int budget) {
        String pinned=pinnedContext(sessionId);
        return pinned + "\n" + String.join("\n", transcriptLines(sessionId, Math.max(0,budget-(int)TokenUsageService.estimateTokens(pinned))));
    }

    private List<String> transcriptLines(String sessionId, int budget) {
        List<RoundtableMessageStore.MessageRow> all = maMessages.bySession(sessionId);
        if (all.isEmpty()) {
            return List.of();
        }
        List<Long> costs = new ArrayList<>(all.size());
        for (RoundtableMessageStore.MessageRow m : all) {
            costs.add(TokenUsageService.estimateTokens(lineOf(m)));
        }
        List<String> out = new ArrayList<>();
        for (int index : windowOf(costs, props.getHistoryLimit(), budget, lastUserIndex(all))) {
            out.add(lineOf(all.get(index)));
        }
        return out;
    }

    /**
     * 公开记录窗口的选取规则（纯函数：只吃「每行的 token 估算」与两个上限，不碰库，故可直接单测）。
     *
     * <p>三条规则，按优先级：
     * <ol>
     *   <li><b>从最新往回</b>收：累加最靠后的若干行，直到再加一行就超预算为止；</li>
     *   <li><b>最后一行永远保留</b>：哪怕它一个人就超预算——空着比只给半句强；</li>
     *   <li><b>「本轮人的话」必须在</b>（{@code askedIndex}）：它是这一桌为什么开口的全部依据。
     *       窗口没够到它（被条数封顶挡在外面，或预算先耗尽）时，把它补在<b>最末</b>——
     *       紧邻后面的局面交代，是模型注意力最近处。少了它，成员会以为「还没有实际话题」而集体沉默。</li>
     * </ol>
     *
     * @param costs      每行的 token 估算（按时间升序）
     * @param limit      条数上限
     * @param budget     token 预算
     * @param askedIndex 最近一条「人的话」的下标；没有则 -1
     * @return 要回放的行的下标（升序；补进来的那条在最末）
     */
    static List<Integer> windowOf(List<Long> costs, int limit, int budget, int askedIndex) {
        int end = costs.size();
        if (end == 0) {
            return List.of();
        }
        int floor = Math.max(0, end - Math.max(1, limit));
        int keep = end;
        long used = 0;
        while (keep > floor) {
            long cost = Math.max(0L, costs.get(keep - 1));
            /* 已经保住了一行，再往前加会超预算就停手（第一行不看预算，见规则 2） */
            if (keep < end && used + cost > budget) {
                break;
            }
            used += cost;
            keep--;
        }
        List<Integer> out = new ArrayList<>();
        for (int i = keep; i < end; i++) {
            out.add(i);
        }
        if (askedIndex >= 0 && askedIndex < keep) {
            out.add(askedIndex);
        }
        return out;
    }

    /** 最近一条「人的话」的下标（没有返回 -1）：它是「本轮人的话必须带上」的锚点 */
    private static int lastUserIndex(List<RoundtableMessageStore.MessageRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (RoundtableMessageStore.ROLE_USER.equals(rows.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 公开记录里的一行：人的话与成员的发言是 {@code <message speaker="谁">}，状态机动作是
     * {@code <event>}——两类内容在结构上就分开，模型不必靠前缀去猜这一行是发言还是动作。
     */
    private static String lineOf(RoundtableMessageStore.MessageRow m) {
        if (RoundtableMessageStore.ROLE_EVENT.equals(m.role())) {
            return "<event>" + m.content() + "</event>";
        }
        String who = RoundtableMessageStore.ROLE_USER.equals(m.role()) ? "用户"
                : (m.peerName() == null || m.peerName().isBlank() ? "某位成员" : m.peerName());
        return "<message speaker=\"" + who + "\">" + m.content() + "</message>";
    }

    private String nameOr(String peerId) {
        String name = directory.nameOf(peerId);
        return name == null ? peerId : name;
    }
}
