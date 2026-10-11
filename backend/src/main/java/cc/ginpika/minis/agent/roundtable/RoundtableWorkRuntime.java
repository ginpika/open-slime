package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
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
 * 执行态运行时（设计：讨论态与执行态并行）：每个 Agent 一个「核心线程」，消费共享任务板里自己名下的条目。
 *
 * <p>一次闭环：
 * <ol>
 *   <li>讨论态里有人调 {@code start_work} → 发 {@code WORK_STARTED} 事件（讨论态 → 执行态）；</li>
 *   <li>本类接住它，给任务板里还有待办的每位成员各起一个 worker（虚拟线程，一次任务一条）；</li>
 *   <li>worker 按顺序把自己名下（或公共）的 {@code pending} 条目一条条做掉——<b>每条 = 一次 Turn</b>
 *       （{@link RoundtableChatService#executeTodo}，与讨论共用同一执行内核）；</li>
 *   <li>做完更新任务板（模型自己调 {@code complete_todo}，或由本类按「执行完成」收尾并记结论）；</li>
 *   <li>遇阻则标 {@code blocked} 并 {@code request_discussion} —— 讨论态被重新激活（执行态 → 讨论态），
 *       全桌据此动态增删改任务板，再开工。这就是那条全链路 loop。</li>
 * </ol>
 *
 * <p><b>不空转</b>：worker 只在被唤醒且确有 {@code pending} 条目时存在，取不到活就退出——
 * 「常驻核心线程」是逻辑意义（每成员一个执行主体），不是持续烧 token 的物理线程。
 */
@Component
public class RoundtableWorkRuntime {

    private static final Logger log = LoggerFactory.getLogger(RoundtableWorkRuntime.class);

    /** 执行结论落进会话记录的字符上限 */
    private static final int RESULT_MAX = 800;

    private final RoundtableChatService chat;
    private final RoundtableTodoStore taskBoard;
    private final RoundtableDiscussionRuntime discussions;
    private final AgentDirectory directory;
    private final RuntimeEventBus events;
    private final RoundtableMessageStore messages;
    private final RoundtableLiveBus liveBus;
    /** 执行留痕：把这次执行收成终态（失败 / 受阻时） */
    private final RoundtableWorkRunStore workRuns;

    /** 每个 Agent 一条的核心线程：一次任务一条虚拟线程，不占平台线程、空闲即退 */
    private final ExecutorService workers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("roundtable-worker-", 0).factory());
    /** 正在跑的 worker：{@code sessionId|peerId}（同一成员不并发开两个 worker） */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /**
     * 已经报过「全员完成」的会话：避免同一次「做完即静止」被重复上报。
     * 一旦有新活开工（{@link #startWorker}）就移除——那是新一轮，之后做完该再报一次。
     */
    private final Set<String> allDoneNotified = ConcurrentHashMap.newKeySet();

    public RoundtableWorkRuntime(RoundtableChatService chat, RoundtableTodoStore taskBoard,
                                 RoundtableDiscussionRuntime discussions, AgentDirectory directory,
                                 RuntimeEventBus events, RoundtableMessageStore messages,
                                 RoundtableLiveBus liveBus, RoundtableWorkRunStore workRuns) {
        this.chat = chat;
        this.taskBoard = taskBoard;
        this.discussions = discussions;
        this.directory = directory;
        this.events = events;
        this.messages = messages;
        this.liveBus = liveBus;
        this.workRuns = workRuns;
        /* 全局订阅：开工信号（讨论 → 执行）、以及「一条做完了」——后者可能正是别人在等的前置 */
        events.subscribeAll(row -> {
            String type = row.type();
            if (RuntimeEventType.WORK_STARTED.name().equals(type)) {
                onWorkStarted(row.sessionId());
            } else if (RuntimeEventType.TASK_COMPLETED.name().equals(type)) {
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
        for (AgentRow peer : directory.peers(sessionId)) {
            if (!taskBoard.claimable(sessionId, peer.id()).isEmpty()) {
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
     * 其余状态（进行中 / 已完成 / 已放弃）不适用——进行中本来就在跑。
     */
    public boolean checkNow(String todoId) {
        TodoRow todo = taskBoard.todo(todoId).orElse(null);
        if (todo == null) {
            return false;
        }
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(todo.status())) {
            taskBoard.update(todoId, RoundtableTodoStore.STATUS_PENDING, null, null, null, null);
        } else if (!RoundtableTodoStore.STATUS_PENDING.equals(todo.status())) {
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
        return running.contains(sessionId + "|" + peerId);
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
            if (key.endsWith(suffix)) {
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
        String key = sessionId + "|" + peerId;
        if (!running.add(key)) {
            return;
        }
        /* 有新活开工 = 新一轮：撤销「已报过全员完成」的标记，让这一轮做完后能再报一次 */
        allDoneNotified.remove(sessionId);
        workers.execute(() -> {
            try {
                drain(sessionId, peerId);
            } catch (RuntimeException e) {
                log.warn("成员执行线程失败：session={} peer={} error={}", sessionId, peerId, e.toString());
            } finally {
                running.remove(key);
                /* 自己退出后可能正是最后一位：若全桌就此做完，报「全员完成」把大家叫回来 */
                maybeAnnounceAllDone(sessionId);
            }
        });
    }

    /**
     * 成员的核心线程：按顺序把自己名下（或公共）的 {@code pending} 条目一条条做掉。
     *
     * <p>取不到活就退出（不空转）；遇阻就停下来请大家再议（执行 → 讨论）。
     */
    private void drain(String sessionId, String peerId) {
        while (true) {
            List<TodoRow> next = taskBoard.claimable(sessionId, peerId);
            if (next.isEmpty()) {
                return;
            }
            TodoRow todo = next.get(0);
            /* 先领走（标 doing）：防止同一条被重复取 */
            taskBoard.update(todo.id(), RoundtableTodoStore.STATUS_DOING, null, null, peerId, null);
            announce(sessionId, peerId, "开始执行：" + todo.title());
            try {
                ChatDtos.ChatReply reply = chat.executeTodo(sessionId, peerId, todo, null);
                TodoRow after = taskBoard.todo(todo.id()).orElse(null);
                if (after != null && RoundtableTodoStore.STATUS_BLOCKED.equals(after.status())) {
                    /* 模型自己把这条标了受阻：**立刻**在讨论态提出，不能默默卡着——否则这位成员
                       就空载了。带上传「找谁」的决策（block_with，逗号分隔可多个、也可含「user」）：
                       点名同伴就只和它们定向沟通一轮，点名用户则全桌暂停等用户发言，留空则全桌一起议
                       （见 RoundtableDiscussionRuntime#handleReport）。
                       （update_todo 已经发过 TASK_BLOCKED 事件，那条当前不由运行时接住，这条 report 才是
                       真正唤醒讨论的那一下，所以决策要在这里带出去。） */
                    announce(sessionId, peerId, "受阻：" + todo.title()
                            + (after.blockReason() == null ? "" : "（" + after.blockReason() + "）"));
                    workRuns.finishLatest(todo.id(), RoundtableWorkRunStore.STATUS_BLOCKED,
                            after.blockReason());
                    discussions.report(sessionId, peerId, "「" + todo.title() + "」受阻："
                            + (after.blockReason() == null ? "未说明" : after.blockReason()), null,
                            after.blockWith());
                    return;   // 停下等讨论：讨论把任务区改好后会自动复工（见 runtime 的 autoResume）
                }
                /* 模型没自己动这条，就按「执行完成」收尾并把结论记上 */
                if (after != null && RoundtableTodoStore.STATUS_DOING.equals(after.status())) {
                    taskBoard.update(todo.id(), RoundtableTodoStore.STATUS_DONE,
                            summarize(reply == null ? null : reply.message()), null, null, null);
                    events.publish(RuntimeEvent.of(RuntimeEventType.TASK_COMPLETED, sessionId, peerId,
                            Map.of("todoId", todo.id())));
                }
                /* 报「执行完成」只在它确实收在这条上时：人中途把它放回队列（pending）或放弃时，
                   那不是这条的结局，不该报完成 */
                boolean requeued = after != null
                        && (RoundtableTodoStore.STATUS_PENDING.equals(after.status())
                                || RoundtableTodoStore.STATUS_DROPPED.equals(after.status()));
                if (!requeued) {
                    announce(sessionId, peerId, "执行完成：" + todo.title());
                }
            } catch (RuntimeException e) {
                /* 执行失败 = 遇阻：标 blocked 并发起讨论（这条回边把讨论态重新激活） */
                taskBoard.update(todo.id(), RoundtableTodoStore.STATUS_BLOCKED, null,
                        "执行失败：" + e.getMessage(), null, null);
                workRuns.finishLatest(todo.id(), RoundtableWorkRunStore.STATUS_FAILED, e.getMessage());
                announce(sessionId, peerId, "执行「" + todo.title() + "」失败：" + e.getMessage());
                discussions.report(sessionId, peerId,
                        "执行「" + todo.title() + "」失败：" + e.getMessage(), null);
                return;
            }
        }
    }

    /** 落一条执行动态进会话记录（全桌可见），并即时广播——以**她自己的发言**呈现，不是动作标签 */
    private void announce(String sessionId, String peerId, String text) {
        String name = directory.nameOf(peerId);
        messages.insert(sessionId, null, RoundtableMessageStore.ROLE_PEER, peerId, name,
                "【执行】" + text, null, null, null, null);
        liveBus.emit(sessionId, null, "action", Map.of("text", "【执行】" + text));
    }

    private static String summarize(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.strip();
        return flat.length() <= RESULT_MAX ? flat : flat.substring(0, RESULT_MAX) + "…";
    }
}
