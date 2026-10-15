package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.TopicStateMachine.PeerState;
import cc.ginpika.minis.ai.runtime.AgentActivitySource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 执行态聚合：页面「执行态」图的数据源，两条入口对应两个视角：
 * <ul>
 *   <li>{@link #overview()}——<b>Agent 视角</b>：全局名册上的成员此刻在不在跑（跨会话）、
 *       名下任务计数与所有会话的在跑 / 受阻任务，不绑定会话；</li>
 *   <li>{@link #view(String)}——<b>任务视角</b>：某张桌子此刻的编排全貌（成员二维状态 + 该会话的共享任务区）。</li>
 * </ul>
 *
 * <p>它只做只读拼装，不改变任何状态——三块内容各自来自权威正本：
 * <ul>
 *   <li><b>成员的执行态</b>：轮次参与状态（{@code roundtable_topic_event} 折叠，见
 *       {@link TopicStateMachine}）投影成二维（{@link PeerStatus}），再叠上「此刻有没有执行线程在跑」
 *       （{@link RoundtableWorkRuntime#isWorking}）；</li>
 *   <li><b>任务区</b>：{@link RoundtableTodoStore}（会话级共享任务板）；</li>
 *   <li><b>讨论节点</b>：会话的当前话题与发言权。</li>
 * </ul>
 */
@Service
public class RoundtableWorkflowService {

    private final RoundtableChatService chat;
    private final AgentDirectory directory;
    private final RoundtableTodoStore taskBoard;
    private final TopicStateMachine machine;
    private final RoundtableWorkRuntime workRuntime;
    private final RoundtableWorkRunStore workRuns;
    private final ObjectMapper om;
    private final List<AgentActivitySource> activitySources;

    public RoundtableWorkflowService(RoundtableChatService chat, AgentDirectory directory,
                                     RoundtableTodoStore taskBoard, TopicStateMachine machine,
                                     RoundtableWorkRuntime workRuntime,
                                     RoundtableWorkRunStore workRuns, ObjectMapper om,
                                     List<AgentActivitySource> activitySources) {
        this.chat = chat;
        this.directory = directory;
        this.taskBoard = taskBoard;
        this.machine = machine;
        this.workRuntime = workRuntime;
        this.workRuns = workRuns;
        this.om = om;
        this.activitySources = activitySources;
    }

    /**
     * 某条待办的执行历史（新→旧）：执行态页面点开这条任务时，回放「Agent 到底在干嘛」——
     * 思考链、过程分段（调用过哪些工具）、失败 / 受阻原因都在这里。
     */
    public List<AgentDtos.WorkRunView> todoRuns(String todoId, int limit) {
        return workRuns.byTodo(todoId, limit).stream().map(this::toRun).toList();
    }

    private AgentDtos.WorkRunView toRun(RoundtableWorkRunStore.RunRow r) {
        return new AgentDtos.WorkRunView(r.id(), r.todoId(), r.peerId(), r.peerName(), r.status(),
                r.output(), r.reasoning(), traces(r.trace()), r.error(), r.startedAt(), r.finishedAt());
    }

    private List<ChatDtos.TraceSegment> traces(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<ChatDtos.TraceSegment>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 某张桌子此刻的编排全貌（任务视角）：成员是该会话在座的人、任务区是该会话的共享任务板。
     *
     * @param sessionId 会话 id；为空时取最近建的一个
     * @return 没有会话时返回一张空视图（页面据此显示「还没有桌子」）
     */
    public AgentDtos.WorkflowView view(String sessionId) {
        return view(sessionId,cc.ginpika.minis.ai.core.UserScope.get());
    }

    public AgentDtos.WorkflowView view(String sessionId,String owner) {
        AgentDtos.SessionView session = pick(sessionId);
        if (session == null) {
            return new AgentDtos.WorkflowView(null, null, null, null, null, false, false,
                    List.of(), List.of());
        }
        String topicId = session.topicId();
        Map<String, PeerState> states = topicId == null || topicId.isBlank()
                ? Map.of() : machine.states(topicId);

        List<RoundtableTodoStore.TodoRow> board = taskBoard.list(session.id());
        List<AgentDtos.WorkflowTodo> todos = board.stream().map(this::toTodo).toList();

        boolean topicClosed = RoundtableTopicStore.STATUS_CLOSED.equals(session.topicStatus());
        /* 「整桌推进状态」：确有人做完过、此刻无未完条目、也没有 worker 在跑，且话题还开着——
           这正是「全员就绪、正集结」的时刻（后端在此发 ALL_WORK_DONE，见 RoundtableWorkRuntime） */
        boolean anyDone = board.stream()
                .anyMatch(t -> RoundtableTodoStore.STATUS_DONE.equals(t.status()));
        boolean advancing = anyDone && !topicClosed
                && taskBoard.openOnes(session.id()).isEmpty()
                && workRuntime.workingCount(session.id()) == 0;

        List<AgentDtos.WorkflowAgent> agents = new ArrayList<>();
        List<AgentActivitySource.Activity> activity = activities(owner,session.id());
        for (String peerId : session.memberIds()) {
            PeerStatus two = PeerStatus.of(states.getOrDefault(peerId, PeerState.INACTIVE));
            int pending = 0;
            int doing = 0;
            int done = 0;
            int blocked = 0;
            for (RoundtableTodoStore.TodoRow t : board) {
                if (!peerId.equals(t.ownerPeer())) {
                    continue;
                }
                switch (t.status()) {
                    case RoundtableTodoStore.STATUS_PENDING -> pending++;
                    case RoundtableTodoStore.STATUS_DOING -> doing++;
                    case RoundtableTodoStore.STATUS_DONE -> done++;
                    case RoundtableTodoStore.STATUS_BLOCKED -> blocked++;
                    default -> {
                    }
                }
            }
            for(var extra:activity){if(peerId.equals(extra.agentId())){pending+=extra.pending();doing+=extra.doing();done+=extra.done();}}
            boolean working = workRuntime.isWorking(session.id(), peerId)
                    || activity.stream().anyMatch(a->peerId.equals(a.agentId()) && a.working());
            /* 成员级「待推进」：自己没在跑、名下也没有未完条目、此刻也无活可领（含公共池），
               且这一轮确有人做完过、话题未收束——先做完的先到这里，等全桌集结 */
            boolean idleNoWork = !working && pending == 0 && doing == 0 && blocked == 0
                    && taskBoard.claimable(session.id(), peerId).isEmpty();
            String execution = two.execution().name();
            if (working) {
                execution = ExecutionState.RUNNING.name();
            } else if (idleNoWork && anyDone && !topicClosed) {
                execution = ExecutionState.ADVANCED.name();
            }
            AgentRow peerRow = directory.peer(peerId).orElse(null);
            agents.add(new AgentDtos.WorkflowAgent(peerId, nameOf(peerId),
                    two.communication().name(), execution, working, pending, doing, done,
                    peerRow == null ? null : peerRow.role(),
                    peerRow == null ? null : peerRow.roleColor()));
        }

        boolean discussing = session.topicStatus() != null
                && !RoundtableTopicStore.STATUS_CLOSED.equals(session.topicStatus());
        return new AgentDtos.WorkflowView(session.id(), session.title(), topicId, session.topicStatus(),
                session.floorPeer(), discussing, advancing && activity.stream().noneMatch(AgentActivitySource.Activity::working), agents, todos);
    }

    /**
     * 全局执行态总览（Agent 视角）：<b>不绑定任何会话</b>——名册上的每位成员此刻在不在跑
     * （跨会话取并集）、名下任务计数（跨会话聚合），以及所有会话里在做 / 受阻的任务
     * （供页面上「成员 → 它手上的活」连线）。
     *
     * <p>为什么成员不按会话过滤：Agent 是全局的，它「在不在跑」不随看哪张桌子而变；
     * 只有共享任务区（任务视角）才按会话切换，见 {@link #view(String)}。
     */
    public AgentDtos.WorkflowView overview() {
        return overview(cc.ginpika.minis.ai.core.UserScope.get());
    }

    public AgentDtos.WorkflowView overview(String owner) {
        List<RoundtableTodoStore.TodoRow> board = new ArrayList<>();
        for (AgentDtos.SessionView s : chat.sessions()) {
            board.addAll(taskBoard.list(s.id()));
        }
        List<AgentDtos.WorkflowTodo> todos = board.stream().map(this::toTodo).toList();

        List<AgentDtos.WorkflowAgent> agents = new ArrayList<>();
        List<AgentActivitySource.Activity> activity = activities(owner,null);
        for (AgentRow peer : directory.peers()) {
            String peerId = peer.id();
            int pending = 0;
            int doing = 0;
            int done = 0;
            for (RoundtableTodoStore.TodoRow t : board) {
                if (!peerId.equals(t.ownerPeer())) {
                    continue;
                }
                switch (t.status()) {
                    case RoundtableTodoStore.STATUS_PENDING -> pending++;
                    case RoundtableTodoStore.STATUS_DOING -> doing++;
                    case RoundtableTodoStore.STATUS_DONE -> done++;
                    default -> {
                    }
                }
            }
            for(var extra:activity){if(peerId.equals(extra.agentId())){pending+=extra.pending();doing+=extra.doing();done+=extra.done();}}
            boolean working = workRuntime.isWorkingAnywhere(peerId)
                    || activity.stream().anyMatch(a->peerId.equals(a.agentId()) && a.working());
            /* 没有单一话题可挂，发言维度（communication）在全局视角下无意义 → 留空由页面略去；
               执行维度只由「此刻有没有在跑」给出就绪 / 执行中 */
            agents.add(new AgentDtos.WorkflowAgent(peerId, peer.name(), null,
                    working ? PeerStatus.of(PeerState.SPEAKING).execution().name()
                            : PeerStatus.of(PeerState.INACTIVE).execution().name(),
                    working, pending, doing, done, peer.role(), peer.roleColor()));
        }
        return new AgentDtos.WorkflowView(null, null, null, null, null, false, false, agents, todos);
    }

    private List<AgentActivitySource.Activity> activities(String owner,String sessionId) {
        if(owner==null || owner.isBlank()){return List.of();}
        return activitySources.stream().flatMap(source->source.activities(owner,sessionId).stream()).toList();
    }

    private AgentDtos.WorkflowTodo toTodo(RoundtableTodoStore.TodoRow t) {
        return new AgentDtos.WorkflowTodo(t.id(), t.title(), t.detail(), t.status(),
                t.ownerPeer(), nameOf(t.ownerPeer()),
                t.createdBy(), nameOf(t.createdBy()),
                t.result(), t.blockReason(), t.seq(), depIds(t.dependsOn()));
    }

    /** depends_on 的 JSON 数组文本 → 前置 id 清单（坏数据退化为空；标题由页面自己按 id 解） */
    private List<String> depIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 取会话：给了 id 按 id 取，否则取最近建的一个 */
    private AgentDtos.SessionView pick(String sessionId) {
        List<AgentDtos.SessionView> all = chat.sessions();
        if (all.isEmpty()) {
            return null;
        }
        if (sessionId == null || sessionId.isBlank()) {
            return all.get(0);
        }
        return all.stream().filter(s -> sessionId.equals(s.id())).findFirst().orElse(null);
    }

    private String nameOf(String peerId) {
        return peerId == null ? null : directory.nameOf(peerId);
    }
}
