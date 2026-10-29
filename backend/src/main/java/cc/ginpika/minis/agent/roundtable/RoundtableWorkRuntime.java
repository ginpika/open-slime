package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTodoStore.TodoRow;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 执行运行时：受控会话按明确派发的 TODO 并行执行，同一成员的检查按序处理；历史圆桌仍每成员一个 worker。
 *
 * <p>一次闭环：
 * <ol>
 *   <li>讨论态里有人调 {@code start_work} → 发 {@code WORK_STARTED} 事件（讨论态 → 执行态）；</li>
 *   <li>本类接住它，给任务板里还有待办的每位成员各起一个 worker（虚拟线程，一次任务一条）；</li>
 *   <li>worker 按顺序把自己名下（或公共）的 {@code pending} 条目一条条做掉——<b>每条 = 一次 Turn</b>
 *       （{@link RoundtableChatService#executeTodo}，与讨论共用同一执行内核）；</li>
 *   <li>{@code complete_todo} 只提交成果；另一位成员 {@code review_todo} 通过才完成，否则返工；</li>
 *   <li>受控会话只领取主控明确授权的这一批工作；整批执行检查后回报主控，不自行返工或启动下游。</li>
 * </ol>
 *
 * <p><b>不空转</b>：worker 只在被唤醒且确有 {@code pending} 条目时存在，取不到活就退出——
 * 「常驻核心线程」是逻辑意义（每成员一个执行主体），不是持续烧 token 的物理线程。
 */
@Component
public class RoundtableWorkRuntime {

    private static final Logger log = LoggerFactory.getLogger(RoundtableWorkRuntime.class);

    private final RoundtableChatService chat;
    private final RoundtableTodoStore taskBoard;
    private final AgentDirectory directory;
    private final RuntimeEventBus events;
    private final RoundtableMessageStore messages;
    private final RoundtableLiveBus liveBus;
    private final RoundtableProgress progress;
    private final CollaborationPlan plans;

    /** 每个 Agent 一条的核心线程：一次任务一条虚拟线程，不占平台线程、空闲即退 */
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("roundtable-worker-", 0).factory());
    /** Checks/legacy work use session|peer; managed implementation uses session|peer|todo. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /**
     * 已经报过「全员完成」的会话：避免同一次「做完即静止」被重复上报。
     * 一旦有新活开工（{@link #startWorker}）就移除——那是新一轮，之后做完该再报一次。
     */
    private final Set<String> allDoneNotified = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    public RoundtableWorkRuntime(RoundtableChatService chat, RoundtableTodoStore taskBoard,
                                 AgentDirectory directory,
                                 RuntimeEventBus events, RoundtableMessageStore messages,
                                 RoundtableLiveBus liveBus,
                                 RoundtableProgress progress, CollaborationPlan plans) {
        this.chat = chat;
        this.taskBoard = taskBoard;
        this.directory = directory;
        this.events = events;
        this.messages = messages;
        this.liveBus = liveBus;
        this.progress = progress;
        this.plans = plans;
        /* 全局订阅：开工信号（讨论 → 执行）、以及「一条做完了」——后者可能正是别人在等的前置 */
        events.subscribeAll(row -> {
            String type = row.type();
            if (plans.find(row.sessionId()).isPresent() && !RuntimeEventType.WORK_STARTED.name().equals(type)) return true;
            if (RuntimeEventType.WORK_STARTED.name().equals(type)) {
                onWorkStarted(row.sessionId());
            } else if (Set.of("TASK_COMPLETED", "TASK_SUBMITTED", "TASK_REVISED", "ISSUE_RESOLVED").contains(type)) {
                /* 一条完成 = 可能解锁了「等它」的下游条目：把现在能开工的成员叫起来。
                   依赖没满足的人压根领不到活，{@link #onWorkStarted} 自然不会给他们起线程，不会空转 */
                onWorkStarted(row.sessionId());
            }
            return true;
        });
    }

    /**
     * 讨论定案、开工：给「此刻确有可领条目」的每位成员起一个 worker。
     *
     * <p>「可领」已含前置依赖判定（见 {@link RoundtableTodoStore#claimable}）：下游角色在它的前置
     * 完成前领不到活，于是不会被唤醒去白跑一趟——这是「按分工分批开工」的落点。
     */
    public void onWorkStarted(String sessionId) {
        if (closing || !progress.allowed(sessionId)) return;
        var plan = plans.find(sessionId).orElse(null);
        if (plan != null) {
            for(var assignment:plans.assignments(sessionId)) {
                boolean review="checking".equals(assignment.phase());
                if(taskBoard.todo(assignment.todoId()).filter(t->(review?"reviewing":"pending").equals(t.status())).isPresent())
                    startWorker(sessionId,review?assignment.reviewerId():assignment.executorId(),assignment);
            }
            return;
        }
        for (AgentRow peer : directory.peers(sessionId)) {
            if (hasWork(sessionId, peer.id())) {
                startWorker(sessionId, peer.id());
            }
        }
        /* 一次「重新评估」之后可能已经没有可领的活了：若就此静止（无人再跑、任务板清空），
           报一次「全员完成」，把全桌重新叫到一起决定交付 / 新一轮。没人开工时没有 worker 退出，
           所以这一步不能只挂在 worker 的收尾上（见 {@link #startWorker}） */
        maybeAnnounceAllDone(sessionId);
    }

    /**
     * 全员顺利做完的判定与上报：这张桌子上<b>确有人做完过、且已无任何未完条目、也没有 worker 在跑</b>
     * 时，发一条 {@link RuntimeEventType#ALL_WORK_DONE}——讨论运行时据此把全桌重新叫起来（集结）。
     *
     * <p>三重条件缺一不可：
     * <ul>
     *   <li><b>有人做完过</b>（至少一条 done）——否则一张刚开、还没派活的白桌会被误当成「完成」；</li>
     *   <li><b>没有未完条目</b>（{@link RoundtableTodoStore#openOnes} 为空）——只要还有 pending / doing /
     *       blocked 就不算顺利做完；受阻的活由既有的受阻回边处理，不由这里上报；</li>
     *   <li><b>没有 worker 在跑</b>——还有人在跑就还没到集结的时候。</li>
     * </ul>
     *
     * <p>用 {@link #allDoneNotified} 去重：同一次「做完即静止」只报一次；
     * 一旦有新活开工（见 {@link #startWorker}）就重新武装，于是「新一轮做完」会再报一次。
     */
    private void maybeAnnounceAllDone(String sessionId) {
        if (plans.find(sessionId).isPresent()) return;
        if (closing || !progress.allowed(sessionId)) return;
        if (workingCount(sessionId) > 0) {
            return;
        }
        List<TodoRow> board = taskBoard.list(sessionId);
        boolean anyDone = board.stream().anyMatch(t -> RoundtableTodoStore.STATUS_DONE.equals(t.status()));
        if (!anyDone || !taskBoard.openOnes(sessionId).isEmpty()) {
            return;
        }
        if (!allDoneNotified.add(sessionId)) {
            return;
        }
        events.publish(RuntimeEvent.of(RuntimeEventType.ALL_WORK_DONE, sessionId, "runtime",
                Map.of("note", "全员顺利完成，进入推进集结")));
    }

    // ---------- 人对任务板的三项干预（执行态卡片上的 ⋯ 菜单） ----------

    /**
     * 放弃一条：置 {@code dropped}，并重新评估可开工——它的下游依赖随之解锁
     * （{@link RoundtableTodoStore#claimable} 把 dropped 视为已满足）。
     *
     * <p><b>不打断正在跑的那次 TURN</b>：那一次会照常跑完，但收尾时看到状态已不是 doing，
     * 就不会把结果写成 done（见 {@link #drain}）——所以放弃是稳的，只是那一次白跑了。
     *
     * @return 是否真的改了（不存在 / 已经是 dropped 时返回 false）
     */
    public boolean drop(String todoId) {
        TodoRow todo = taskBoard.todo(todoId).orElse(null);
        if(todo!=null)chat.requireDiscussable(todo.sessionId());
        if (todo == null || RoundtableTodoStore.STATUS_DROPPED.equals(todo.status())) {
            return false;
        }
        taskBoard.update(todoId, RoundtableTodoStore.STATUS_DROPPED, null, null, null, null);
        onWorkStarted(todo.sessionId());
        return true;
    }

    /**
     * 暂时跳过：把这条压到最低优先级——还有别的活就先做别的，没有别的活时才轮到它。
     *
     * <p>待办：直接压优先级即可。进行中：正在跑的那次<b>不打断</b>（不抢占），但要把它放回队列
     * （{@code doing → pending}），这样这次跑完不会被收成 done，而是按最低优先级重新排队——
     * 该成员随即去做别的活，它排到最后。其余状态（受阻 / 已完成 / 已放弃）不适用。
     */
    public boolean defer(String todoId) {
        TodoRow todo = taskBoard.todo(todoId).orElse(null);
        if(todo!=null)chat.requireDiscussable(todo.sessionId());
        if (todo == null) {
            return false;
        }
        if (RoundtableTodoStore.STATUS_PENDING.equals(todo.status())) {
            taskBoard.setPriority(todoId, RoundtableTodoStore.PRIORITY_LOW);
            return true;
        }
        if (RoundtableTodoStore.STATUS_DOING.equals(todo.status())) {
            taskBoard.update(todoId, RoundtableTodoStore.STATUS_PENDING, null, null, null, null);
            taskBoard.setPriority(todoId, RoundtableTodoStore.PRIORITY_LOW);
            return true;
        }
        return false;
    }

    /**
     * 立刻检查：把这条拉到最高优先级插到该成员的队首，并把它的执行线程叫起来。
     *
     * <p><b>不抢占</b>：不打断正在跑的那次 TURN，只是插队——它当前这次跑完，下一次取活就取到这条。
     * 成员只有一条 worker、逐条串行，所以「这个 TURN 跑完前，该成员不会再开新 TURN」是天然的。
     *
     * <p><b>只叫起这条的负责人</b>（{@link #wakeOwner}）：不像 {@link #onWorkStarted} 那样全桌扫描，
     * 免得点一条「立刻检查」顺手把别人的活也开跑。
     *
     * <p>待办：直接拉优先级。受阻：受阻的条目领不到（{@code claimable} 只取 pending），
     * 所以先放回待做（{@code blocked → pending}）再拉满，让它立刻被跑一次检查。
     * 受控任务的已放弃条目也可请求主控重新评估，由主控决定是否重开；普通任务不直接重跑已放弃条目。
     */
    public boolean checkNow(String todoId) {
        TodoRow todo = taskBoard.todo(todoId).orElse(null);
        if(todo!=null)chat.requireDiscussable(todo.sessionId());
        if (todo == null) {
            return false;
        }
        if (plans.find(todo.sessionId()).isPresent()) {
            if (!Set.of("pending", "blocked", "dropped", "reviewing").contains(todo.status())) return false;
            progress.resume(todo.sessionId());
            progress.retry(todo.id());
            events.publish(RuntimeEvent.of(RuntimeEventType.AGENT_REPORT, todo.sessionId(), "user",
                    Map.of("content", "用户要求重新评估条目「" + todo.title() + "」（id=" + todo.id() + "），请主控核对原因并决定派工。")));
            return true;
        }
        if (Set.of("pending", "blocked", "reviewing").contains(todo.status())) {
            progress.resume(todo.sessionId());
            progress.retry(todo.id());
        }
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(todo.status())) {
            taskBoard.update(todoId, RoundtableTodoStore.STATUS_PENDING, null, null, null, null);
        } else if (!Set.of("pending", "reviewing").contains(todo.status())) {
            return false;
        }
        taskBoard.setPriority(todoId, RoundtableTodoStore.PRIORITY_URGENT);
        wakeOwner(todo);
        return true;
    }

    /**
     * 只把「这条的负责人」叫起来：有负责人就只起它；公共条目（无主）交给名册里第一个领得到它的人。
     *
     * <p>与该成员已有的 worker 天然去重（{@link #startWorker}），所以正在跑的成员会在下一次取活时领到它。
     */
    private void wakeOwner(TodoRow todo) {
        if ("reviewing".equals(todo.status())) {
            onWorkStarted(todo.sessionId());
            return;
        }
        String owner = todo.ownerPeer();
        if (owner != null) {
            if (!taskBoard.claimable(todo.sessionId(), owner).isEmpty()) {
                startWorker(todo.sessionId(), owner);
            }
            return;
        }
        for (AgentRow peer : directory.peers(todo.sessionId())) {
            if (!taskBoard.claimable(todo.sessionId(), peer.id()).isEmpty()) {
                startWorker(todo.sessionId(), peer.id());
                return;
            }
        }
    }

    /** 该成员此刻是否有一个执行线程在跑（页面「执行态」图据此高亮） */
    public boolean isWorking(String sessionId, String peerId) {
        String key=sessionId+"|"+peerId;
        return running.stream().anyMatch(k->k.equals(key)||k.startsWith(key+"|"));
    }

    /**
     * 该成员此刻是否在<b>任一会话</b>里有执行线程在跑（全局 Agent 视角用）。
     *
     * <p>「在执行」是成员自己的事实，不随看哪张桌子而变，所以 Agent 视角跨会话取并集。
     */
    public boolean isWorkingAnywhere(String peerId) {
        if (peerId == null) {
            return false;
        }
        String suffix = "|" + peerId;
        for (String key : running) {
            if (key.endsWith(suffix) || key.contains(suffix+"|")) {
                return true;
            }
        }
        return false;
    }

    /** 该会话此刻有几个执行线程在跑 */
    public int workingCount(String sessionId) {
        int n = 0;
        for (String key : running) {
            if (key.startsWith(sessionId + "|")) {
                n++;
            }
        }
        return n;
    }

    /** 给某成员起一个 worker（已在跑则忽略） */
    public void startWorker(String sessionId, String peerId) {
        if(plans.find(sessionId).isPresent()) { onWorkStarted(sessionId); return; }
        startWorker(sessionId,peerId,null);
    }
    private void startWorker(String sessionId, String peerId, CollaborationPlan.Assignment assignment) {
        if (closing || !progress.allowed(sessionId)) return;
        // Executions are per TODO, even with the same Agent profile. Reviews by one member stay ordered.
        String key = sessionId + "|" + peerId + (assignment!=null && "executing".equals(assignment.phase())?"|"+assignment.todoId():"");
        if (!running.add(key)) {
            return;
        }
        /* 有新活开工 = 新一轮：撤销「已报过全员完成」的标记，让这一轮做完后能再报一次 */
        allDoneNotified.remove(sessionId);
        String owner=cc.ginpika.minis.ai.core.UserScope.get();
        workers.execute(() -> {
            try(var actor=cc.ginpika.minis.ai.core.UserScope.bind(owner)){
            try {
                drain(sessionId, peerId, assignment);
            } catch (RuntimeException e) {
                log.warn("成员执行线程失败：session={} peer={} error={}", sessionId, peerId, e.toString());
            } finally {
                running.remove(key);
                if (assignment != null) settleAssignment(sessionId, assignment.todoId(), assignment.version());
                // A completion signal can arrive just before removal of this worker's key.
                if (!closing && progress.allowed(sessionId)) onWorkStarted(sessionId);
                /* 自己退出后可能正是最后一位：若全桌就此做完，报「全员完成」把大家叫回来 */
                maybeAnnounceAllDone(sessionId);
            }
            }
        });
    }

    private boolean hasWork(String sessionId, String peerId) {
        return !taskBoard.reviewable(sessionId, peerId).isEmpty() || !taskBoard.claimable(sessionId, peerId).isEmpty();
    }

    /**
     * 成员的核心线程：按顺序把自己名下（或公共）的 {@code pending} 条目一条条做掉。
     *
     * <p>取不到活就退出（不空转）；遇阻就停下来请大家再议（执行 → 讨论）。
     */
    private void drain(String sessionId, String peerId, CollaborationPlan.Assignment assignment) {
        while (!closing && progress.allowed(sessionId)) {
            var plan = plans.find(sessionId).orElse(null);
            TodoRow todo = plan == null ? taskBoard.claimReview(sessionId, peerId)
                    .orElseGet(() -> taskBoard.claim(sessionId, peerId).orElse(null))
                    : assignment==null?null:claimAssignment(assignment, peerId);
            if (todo == null) return;
            boolean review = "checking".equals(todo.status());
            if (!progress.step(sessionId)) {
                taskBoard.transition(todo.id(), sessionId, todo.status(), review ? "reviewing" : "pending", null, null);
                announce(sessionId, peerId, progress.state(sessionId).reason());
                return;
            }
            if (!review) progress.attempt(todo.id());
            if (!progress.allowed(sessionId)) {
                taskBoard.transition(todo.id(), sessionId, todo.status(), review ? "reviewing" : "pending", null, null);
                return;
            }
            announce(sessionId, peerId, (review ? "开始检查：" : "开始执行：") + todo.title());
            try {
                chat.executeTodo(sessionId, peerId, todo, null);
                TodoRow after = taskBoard.todo(todo.id()).orElse(null);
                if (after == null) continue;
                if (todo.status().equals(after.status())) {
                    block(todo, peerId, review ? "检查者未提交检查结论，需补充证据或调整检查人"
                            : "本次未提交成果；请说明实际产出及验证结果后再推进", plan == null ? "user" : "");
                } else if ("reviewing".equals(after.status())) {
                    announce(sessionId, peerId, "成果已提交，等待其他成员检查：" + todo.title());
                    if (directory.peers(sessionId).stream().noneMatch(p -> !p.id().equals(after.ownerPeer())))
                        block(after, peerId, "缺少另一位成员检查成果，请补充成员后将条目恢复 pending", "user");
                } else if ("done".equals(after.status())) {
                    announce(sessionId, peerId, "检查通过：" + todo.title());
                } else if (review && "pending".equals(after.status())) {
                    announce(sessionId, peerId, "检查未通过，已附意见返工：" + todo.title());
                }
            } catch (cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException cancelled) {
                taskBoard.transition(todo.id(), sessionId, todo.status(), review ? "reviewing" : "pending", null, "执行已中断，尚未完成");
                return;
            } catch (RuntimeException e) {
                block(todo, peerId, "执行失败：" + e.getMessage(), "");
            }
            if (plan != null) return;
        }
    }

    private TodoRow claimAssignment(CollaborationPlan.Assignment assignment, String peer) {
        var live=plans.assignment(assignment.sessionId(),assignment.todoId()).orElse(null);
        if(live==null || live.version()!=assignment.version() || !live.phase().equals(assignment.phase()))return null;
        boolean review = "checking".equals(live.phase());
        if (!(review ? peer.equals(live.reviewerId()) : "executing".equals(live.phase()) && peer.equals(live.executorId()))) return null;
        return taskBoard.claimAssigned(live.sessionId(), peer, live.todoId(), review).orElse(null);
    }

    /** Publish one result after the worker has released its slot, so the controller can safely dispatch again. */
    private void settleAssignment(String session, String todoId, int version) {
        var plan = plans.find(session).orElse(null);
        if(plan==null)return;
        if (plan.version() != version) return;
        var assignment=plans.assignment(session,todoId).orElse(null);
        if(assignment==null)return;
        var todo = taskBoard.todo(todoId).orElse(null);
        if (todo != null && "reviewing".equals(todo.status()) && "executing".equals(assignment.phase())) {
            plans.checking(session, todo.id(), version);
            return;
        }
        if(todo!=null && (Set.of("doing","checking").contains(todo.status())
                || ("executing".equals(assignment.phase()) && "pending".equals(todo.status()))
                || ("checking".equals(assignment.phase()) && "reviewing".equals(todo.status()))))return;
        String report = todo == null ? "派工条目已不存在，请重新规划。" : "条目「" + todo.title() + "」回报：" + todo.status()
                + "\n成果：" + todo.result() + "\n检查或受阻意见：" + todo.blockReason();
        if (!plans.returned(session, todoId, report, version)) return;
        String waveReport=plans.find(session).orElseThrow().decision();
        if (!closing && progress.allowed(session)) events.publish(RuntimeEvent.of(RuntimeEventType.AGENT_REPORT, session, "runtime",
                plan.coordinatorId(), todoId, null, Map.of("content", waveReport)));
    }

    private void block(TodoRow todo, String peer, String reason, String target) {
        if (!taskBoard.transition(todo.id(), todo.sessionId(), todo.status(), "blocked", null, reason)) return;
        taskBoard.setBlockWith(todo.id(), target);
        announce(todo.sessionId(), peer, "受阻：" + todo.title() + "（" + reason + "）");
        events.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, todo.sessionId(), peer,
                target, todo.id(), null, Map.of("todoId", todo.id(), "title", todo.title(), "reason", reason, "with", target)));
    }

    /** 落一条执行动态进会话记录（全桌可见），并即时广播——以**她自己的发言**呈现，不是动作标签 */
    private void announce(String sessionId, String peerId, String text) {
        String name = directory.nameOf(peerId);
        messages.insert(sessionId, null, RoundtableMessageStore.ROLE_PEER, peerId, name,
                "【执行】" + text, null, null, null, null);
        liveBus.emit(sessionId, null, "action", Map.of("text", "【执行】" + text));
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        closing = true;
        for (String key : running) chat.requestStop(key.substring(0, key.indexOf('|')));
        workers.close();
    }
}
