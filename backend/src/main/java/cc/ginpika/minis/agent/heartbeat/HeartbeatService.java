package cc.ginpika.minis.agent.heartbeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.type.TypeReference;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.computer.terminal.TerminalService;
import cc.ginpika.minis.agent.chat.ChatThreads;
import cc.ginpika.minis.agent.chat.SubagentTool;
import cc.ginpika.minis.agent.chat.HeartbeatTool;
import cc.ginpika.minis.agent.member.AgentAnchorService;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryExec;
import cc.ginpika.minis.ai.core.MemoryScopeCtx;
import cc.ginpika.minis.ai.core.MemorySurface;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.heartbeat.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DelegatePlanTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.ListPlansTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.DeletePlanTool;
import cc.ginpika.minis.ai.core.tool.graph.ReadMyAnchorTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.heartbeat.UpdatePlanTool;
import cc.ginpika.minis.ai.core.tool.thread.OpenThreadTool;
import cc.ginpika.minis.ai.core.tool.thread.SendMessageTool;
import cc.ginpika.minis.config.HeartbeatProps;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 后台运行层：<b>逐 Agent</b> 的心跳调度与常驻职责编排（Subconscious / heartbeat）。
 *
 * <p>每个 Agent 各有一套后台：一行心跳（{@code heartbeat}）+ 一份职责名册（{@code heartbeat_plan}）。
 * 调度器只有<b>一个</b>，按固定小周期（{@link HeartbeatProps#getScanMs()}）醒来，逐个看哪些 Agent 的
 * 心跳开着、且到点（{@code now - last_beat_at >= tick_ms}）、且名册里有值得派的活（预筛
 * {@link #hasDueDuty(String)}）；到点就丢到虚拟线程上敲一拍，故一个慢 Agent 不会卡住整轮扫描。
 *
 * <p>每拍的核心是 {@link #beat(String, String)}：让该 Agent 的心跳主会话出场——它看着自己的职责名册
 * 决定这一拍派谁（{@code delegate_heartbeat}）、缺人手时自己攒编制（{@code create_heartbeat} / {@code update_heartbeat} /
 * {@code delete_heartbeat}）、想看清名册就 {@code list_heartbeats}，闲不住也可以当场派子代理去查。
 *
 * <p>「注入器」已彻底退役：心跳与职责的正文只写正文，素材由模型自己调工具现取
 * （everything in tools &amp; bash）。同一 Agent 的拍不会重叠（{@link #inflight} 逐 Agent 守卫）。
 */
@Service
public class HeartbeatService implements HeartbeatTool {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    /** 交付渠道：静默——只留痕，不主动对人说话 */
    public static final String CH_SILENT = "silent";
    /** 交付渠道：会话——计划可用 open_thread / send_message 往首页 chat 的会话投递消息 */
    public static final String CH_THREAD = "thread";

    /** 所有合法渠道（顺序即前端展示顺序） */
    public static final List<String> CHANNELS = List.of(CH_SILENT, CH_THREAD);

    /**
     * 编制上限：大模型对编制有全权限，留一个阀门防止它一拍接一拍地攒出一份读不完的名册。
     * 每个 Agent 各自计数。
     */
    private static final int MAX_PLANS = 24;

    /** 心跳未配正文时的展示名兜底（也用于找不到 Agent 名时） */
    private static final String DEFAULT_TITLE = "心跳";

    /** 一次心跳的摘要：agents = 这一拍真正跑起来的执行数（职责 + 子代理） */
    public record BeatSummary(String runId, long beat, String trigger, int agents) {
    }

    private final HeartbeatProps props;
    private final PlanStore plans;
    private final ExecStore execs;
    /** 任务：心跳主会话与它派出的每份职责各落一行，父子关系（心跳 → 职责）在此成树 */
    private final TaskStore tasks;
    private final HeartbeatContextBuilder builder;
    private final AgentRunner runner;
    private final SubagentTool subagent;
    private final KnowledgeToolbox toolbox;
    private final TerminalService terminal;
    private final ToolManager tools;
    private final ObjectMapper om;
    /** 首页 chat 的会话投递落点（open_thread / send_message）：只碰会话表与消息表，故无构造环 */
    private final ChatThreads threads;
    /** 名册门面：心跳留痕里带上 Agent 的展示名（找不到时退回 id） */
    private final AgentDirectory directory;
    /** 人格锚点：心跳 Agent 的 read_my_anchor 落地（读它自己的锚点） */
    private final AgentAnchorService anchors;

    /** 心跳扫描器（单线程：按固定周期醒来，逐个 Agent 判到点；真正的拍丢给虚拟线程） */
    private ScheduledExecutorService scanner;
    /** 敲拍用的虚拟线程池：一拍是一次完整的 LLM 往返，慢的 Agent 不该拖住扫描 */
    private final Executor beats = Executors.newVirtualThreadPerTaskExecutor();

    /** 全局心跳序号（仅用于展示排序） */
    private final AtomicLong beatSeq = new AtomicLong();

    /** 自启动以来因「无到点职责」被跳过的拍数：纯省下的空转，页面据此看得见预筛在做什么 */
    private final AtomicLong idleSkips = new AtomicLong();

    /** 正在跑拍的 Agent：同一时刻每个 Agent 至多一拍，避免自动与手动叠加 */
    private final Set<String> inflight = ConcurrentHashMap.newKeySet();

    /** 正在跑拍的计数（全局观测台显示「后台是否在忙」） */
    private final AtomicInteger running = new AtomicInteger();

    public HeartbeatService(HeartbeatProps props, PlanStore plans, ExecStore execs,
                            TaskStore tasks, HeartbeatContextBuilder builder,
                            AgentRunner runner,
                            SubagentTool subagent, KnowledgeToolbox toolbox,
                            TerminalService terminal, ToolManager tools,
                            ObjectMapper om, ChatThreads threads,
                            AgentDirectory directory, AgentAnchorService anchors) {
        this.props = props;
        this.plans = plans;
        this.execs = execs;
        this.tasks = tasks;
        this.builder = builder;
        this.runner = runner;
        this.subagent = subagent;
        this.toolbox = toolbox;
        this.terminal = terminal;
        this.tools = tools;
        this.om = om;
        this.threads = threads;
        this.directory = directory;
        this.anchors = anchors;
    }

    // ---------- 心跳调度生命周期 ----------

    @PostConstruct
    void start() {
        scanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-scan");
            t.setDaemon(true);
            return t;
        });
        long scanMs = Math.max(1_000L, props.getScanMs());
        scanner.scheduleWithFixedDelay(this::scan, props.getInitialDelayMs(), scanMs,
                TimeUnit.MILLISECONDS);
        log.info("潜意识心跳调度已就绪：scanMs={} maxAgentsPerBeat={}",
                scanMs, props.getMaxAgentsPerBeat());
    }

    @PreDestroy
    void stop() {
        if (scanner != null) {
            scanner.shutdownNow();
        }
    }

    /** 一次扫描：逐个「开着心跳」的 Agent 判到点，到点且有名册活就派一拍 */
    private void scan() {
        try {
            long now = System.currentTimeMillis();
            for (PlanStore.HeartbeatRow hb : plans.enabledHeartbeats()) {
                if (inflight.contains(hb.agentId())) {
                    continue;
                }
                long tick = Math.max(5_000L, hb.tickMs());
                if (hb.lastBeatAt() != null && now - hb.lastBeatAt() < tick) {
                    continue;
                }
                /* 预筛：没活就别叫模型（判定只看库里的便宜数据，见 hasDueDuty） */
                if (!hasDueDuty(hb.agentId())) {
                    idleSkips.incrementAndGet();
                    continue;
                }
                beats.execute(() -> {
                    try {
                        beat(hb.agentId(), "beat");
                    } catch (RuntimeException e) {
                        log.warn("心跳异常：agent={} {}", hb.agentId(), e.getMessage());
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("心跳扫描异常：{}", e.getMessage());
        }
    }

    /**
     * 这个 Agent 这一拍有没有值得叫醒心跳主会话的活：名册里至少有一条「到了该考虑派它的点」的职责。
     *
     * <p>只算<b>启用</b>的职责——停用是人踩的刹车。名册为空（或全被停用）同样算没活——
     * 「自己攒编制」不该靠每拍空转来实现；人手动敲一次永远绕过本预筛（见 {@link #beat(String, String)}）。
     */
    private boolean hasDueDuty(String agentId) {
        long now = System.currentTimeMillis();
        return plans.plans(agentId).stream().anyMatch(p -> p.enabled() && isDue(p, now));
    }

    /** 到点 = 没设节拍（每拍都可考虑），或距上次派出已过 {@code interval_ms} */
    private static boolean isDue(PlanStore.PlanRow plan, long now) {
        Long interval = plan.intervalMs();
        return interval == null || interval <= 0 || plan.lastBeatAt() == null
                || now - plan.lastBeatAt() >= interval;
    }

    /** 自启动以来被预筛跳过的拍数（页面显示「省下的空转」） */
    public long idleSkips() {
        return idleSkips.get();
    }

    /** 某 Agent 此刻是否有拍在跑 */
    public boolean running(String agentId) {
        return agentId != null && inflight.contains(agentId);
    }

    /** 是否有任意 Agent 的拍在跑（全局观测台用） */
    public boolean runningAny() {
        return running.get() > 0;
    }

    // ---------- 逐 Agent 心跳的读写（控制器用） ----------

    /** 某 Agent 的心跳行：库里没落过时给出内置正文与默认节拍（与执行时用的那份一致） */
    public PlanStore.HeartbeatRow heartbeat(String agentId) {
        return plans.heartbeat(agentId).orElseGet(() ->
                new PlanStore.HeartbeatRow(agentId, HeartbeatPrompts.HEARTBEAT, null, false,
                        Math.max(5_000L, props.getTickMs()), null));
    }

    /** 保存某 Agent 的心跳（正文 / 模型 / 开关 / 节拍，各字段给 null 表示不改） */
    public void saveHeartbeat(String agentId, String content, String modelId, Boolean enabled,
                              Long tickMs) {
        PlanStore.HeartbeatRow current = plans.heartbeat(agentId).orElse(null);
        String nextContent = content != null ? content
                : (current != null ? current.content() : HeartbeatPrompts.HEARTBEAT);
        String nextModel = modelId != null ? blankToNull(modelId)
                : (current != null ? current.modelId() : null);
        boolean nextEnabled = enabled != null ? enabled : (current != null && current.enabled());
        long nextTick = tickMs != null && tickMs > 0 ? tickMs
                : (current != null ? current.tickMs() : Math.max(5_000L, props.getTickMs()));
        Long lastBeat = current != null ? current.lastBeatAt() : null;
        plans.saveHeartbeat(new PlanStore.HeartbeatRow(agentId, nextContent, nextModel, nextEnabled,
                nextTick, lastBeat));
    }

    /** 运行时切换某 Agent 的心跳开关 */
    public void setHeartbeatEnabled(String agentId, boolean on) {
        plans.setHeartbeatEnabled(agentId, on);
    }

    /** 运行时调整某 Agent 的心跳节拍 */
    public void setHeartbeatTick(String agentId, long tickMs) {
        plans.setHeartbeatTick(agentId, tickMs);
    }

    // ---------- 心跳：主会话出场，按需派活 ----------

    /**
     * 敲一次某 Agent 的心跳：先让它的心跳主会话出场——它看着自己的职责名册决定这一拍派谁、怎么派；
     * 它派出去的每一次执行与它自己同属这一批 run，一次心跳即可看全。
     *
     * @param trigger beat=自动节拍 / manual=页面手动敲一次（只是留痕上的区别，派谁仍由主会话说了算）。
     *                预筛（{@link #hasDueDuty(String)}）只拦自动节拍：人手动敲一次永远真跑
     * @return 本轮摘要；若该 Agent 已有一拍在跑则返回 null
     */
    public BeatSummary beat(String agentId, String trigger) {
        if (agentId == null || agentId.isBlank() || !inflight.add(agentId)) {
            return null;
        }
        running.incrementAndGet();
        try {
            long beat = beatSeq.incrementAndGet();
            String runId = UUID.randomUUID().toString();
            execs.insertRun(runId, agentId, beat, trigger);
            /* 立刻记下这次真敲的时间：扫描据此判「到点」，避免同一拍被重复派 */
            plans.markHeartbeatBeat(agentId, System.currentTimeMillis());

            PlanStore.HeartbeatRow hb = heartbeat(agentId);
            HeartbeatContextBuilder.Built built = builder.build(hb.content(), agentId);
            String title = heartbeatTitle(agentId);

            AtomicInteger agents = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            /* 这一拍的心跳任务 id：发射器（heartbeatTool）要拿它把派出的职责 / 子代理挂成子任务 */
            AtomicLong heartbeatTask = new AtomicLong();
            AgentRunner.Request req = new AgentRunner.Request(runId, ExecStore.KIND_HEARTBEAT, null,
                    title, hb.modelId(), built.messages(), tools.toolsOf(ToolSurface.HEARTBEAT),
                    (call, execId) -> heartbeatTool(agentId, runId, call, agents, failed, heartbeatTask),
                    null, null, agentId, null);
            AgentRunner.Outcome outcome;
            try {
                long execId = runner.openExec(req);
                heartbeatTask.set(execId);
                outcome = MemoryScopeCtx.run(heartbeatScope(agentId),
                        () -> AgentScope.run(agentId, () -> runner.run(req, execId)));
            } catch (RuntimeException e) {
                log.warn("心跳主会话执行失败：agent={} {}", agentId, e.getMessage());
                outcome = new AgentRunner.Outcome(-1, "failed", null, e.getMessage());
            }

            /* 这一拍真正跑起来的执行数：减去主会话自己那一行 */
            int spawned = Math.max(0, execs.execsByRun(runId).size() - 1);
            boolean bad = !"done".equals(outcome.status()) || failed.get() > 0;
            execs.finishRun(runId, bad ? "failed" : "done", spawned,
                    bad ? (failed.get() > 0 ? failed.get() + " 个执行失败" : "主会话未能给出结论") : null);
            log.info("心跳 #{}（agent={}，{}）：这一拍跑起 {} 个执行，失败 {}",
                    beat, agentId, trigger, spawned, failed.get());
            return new BeatSummary(runId, beat, trigger, spawned);
        } finally {
            running.decrementAndGet();
            inflight.remove(agentId);
        }
    }

    /**
     * 单跑某 Agent 的一个职责（页面上的「立即执行」）：绕过主会话直接把这份编制派出去，独占一批。
     * 这是人点名要跑，所以不看节拍、也不由主会话裁量。
     */
    public BeatSummary runPlan(String agentId, String planId) {
        PlanStore.PlanRow plan = plans.planById(planId).orElse(null);
        if (plan == null || !agentId.equals(plan.agentId())) {
            return null;
        }
        if (!inflight.add(agentId)) {
            return null;
        }
        running.incrementAndGet();
        try {
            long beat = beatSeq.incrementAndGet();
            String runId = UUID.randomUUID().toString();
            execs.insertRun(runId, agentId, beat, "manual");
            AgentRunner.Outcome outcome = runChild(agentId, runId, plan, null);
            execs.finishRun(runId, outcome.status(), 1, "页面点名执行");
            return new BeatSummary(runId, beat, "manual", 1);
        } finally {
            running.decrementAndGet();
            inflight.remove(agentId);
        }
    }

    /**
     * 心跳主会话的工具分发：派职责、「管职责本身」的工具（增删改 / 看名册）、翻过程、读自己的锚点，
     * 以及当场派子代理——前几类的语义属于后台自己，子代理转交子代理模块；其余（只读检索）交给知识库工具箱。
     *
     * <p>它派出去的执行（职责 / 子代理）都挂到这一拍的心跳任务下（{@code heartbeatTask}），
     * 于是「一次心跳派了哪些活」在任务树上可查。
     */
    private ToolResult heartbeatTool(String agentId, String runId, JsonNode call, AtomicInteger agents,
                                     AtomicInteger failed, AtomicLong heartbeatTask) {
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("");
        if (ReadSubagentRunTool.NAME.equals(name) || DelegateSubagentTool.NAME.equals(name)) {
            /* 子代理那套由子代理模块执行；它派出的执行与前两类同属这一批 run，并挂在心跳任务下 */
            return new ToolResult(name, args,
                    subagent.runTool(agentId, call, runId, "beat", heartbeatTask.get()), false);
        }
        if (TerminalService.handles(name)) {
            /* 命令行那套由终端模块执行（逐目标开关 / 白黑名单都在它那一侧判定） */
            return terminal.run(call, TerminalService.SOURCE_HEARTBEAT);
        }
        ToolResult direct = switch (name) {
            case CreatePlanTool.NAME -> plain(name, args, createPlanTool(agentId, readTree(args)));
            case UpdatePlanTool.NAME -> plain(name, args, updatePlanTool(agentId, readTree(args)));
            case DeletePlanTool.NAME -> plain(name, args, deletePlanTool(agentId, readTree(args)));
            case ListPlansTool.NAME -> plain(name, args, listPlansText(agentId));
            case ReadMyAnchorTool.NAME -> plain(name, args, anchors.text(agentId));
            default -> null;
        };
        if (direct != null) {
            return direct;
        }
        if (!DelegatePlanTool.NAME.equals(name)) {
            return toolbox.run(call, "heartbeat");
        }
        JsonNode parsed = readTree(args);
        String instruction = parsed.path("instruction").asText("").trim();
        if (instruction.isEmpty()) {
            return new ToolResult(name, args,
                    "缺少 instruction：请说明这一轮要让职责做什么。", false);
        }
        /* 单拍派活上限：每派一个都是一次完整的 LLM 往返，留个阀门防它一口气派飞 */
        if (agents.get() >= Math.max(1, props.getMaxAgentsPerBeat())) {
            return new ToolResult(name, args,
                    "这一拍派出去的活已达上限，请就现有结果收束。", false);
        }
        agents.incrementAndGet();
        try {
            AgentRunner.Outcome outcome = runChild(agentId, runId,
                    delegatePlan(agentId, parsed.path("plan").asText("").trim(), instruction),
                    heartbeatTask.get());
            if (!"done".equals(outcome.status())) {
                failed.incrementAndGet();
                return new ToolResult(name, args, "职责执行失败："
                        + (outcome.error() == null ? "未知原因" : outcome.error()), false);
            }
            return new ToolResult(name, args,
                    "（执行 id=" + outcome.execId() + "，如需核对它的过程可调用 "
                            + ReadSubagentRunTool.NAME + "）\n" + outcome.output(), false);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            return new ToolResult(name, args, "职责执行失败：" + e.getMessage(), false);
        }
    }

    /**
     * 取出这一拍要派的职责：按名字或 id 在<b>该 Agent 自己的名册</b>里找到它（{@link #resolvePlan}），
     * 沿用它的正文、工具与模型，只把「这一轮具体做什么」换成委托方给的 instruction。
     */
    private PlanStore.PlanRow delegatePlan(String agentId, String planRef, String instruction) {
        PlanStore.PlanRow plan = resolvePlan(agentId, planRef);
        if (plan == null) {
            throw new IllegalArgumentException(planRef.isBlank()
                    ? "没有指定 plan" : "找不到职责「" + planRef + "」");
        }
        if (!plan.enabled()) {
            throw new IllegalArgumentException(disabledText(plan));
        }
        return new PlanStore.PlanRow(plan.id(), plan.agentId(), plan.name(), plan.role(), plan.content(),
                plan.tools(), plan.channels(), plan.intervalMs(), instruction,
                plan.modelId(), plan.rationale(), plan.sort(), plan.lastBeatAt(), true);
    }

    /**
     * 派出一份职责：用哪个计划已定，与调用方同属一批 run。
     *
     * @param parentTaskId 派它的那个任务（心跳主会话）；页面点名 / 对话委托没有父任务，传 null
     */
    private AgentRunner.Outcome runChild(String agentId, String runId, PlanStore.PlanRow plan,
                                        Long parentTaskId) {
        AgentRunner.Outcome outcome = execute(agentId, runId, plan, parentTaskId);
        if (plan.id() != null) {
            plans.markPlanBeat(plan.id(), System.currentTimeMillis());
        }
        return outcome;
    }

    /** 心跳的记忆执行作用域（memory-v6 §8.5）：该 Agent 自己（L2 私有记忆）；绑定项目随 P3 的会话绑定补齐 */
    private static MemoryExec heartbeatScope(String agentId) {
        return MemoryExec.background(MemorySurface.HEARTBEAT, agentId, null, null, null, null, null);
    }

    /** 执行一份职责：组装它那份上下文（正文 + 本轮交代）→ 开一次执行 → 交给执行引擎 */
    private AgentRunner.Outcome execute(String agentId, String runId, PlanStore.PlanRow plan,
                                       Long parentTaskId) {
        HeartbeatContextBuilder.Built built = builder.build(plan, agentId);
        AgentRunner.Request req = new AgentRunner.Request(runId, ExecStore.KIND_PLAN, plan.id(),
                plan.name(), plan.modelId(), built.messages(), toolSchemas(plan),
                (call, execId) -> planTool(agentId, call), parentTaskId, null, agentId, null);
        /* 开执行与跑的整段都在 try 内：写库出问题时兜成一次失败执行，
           与原来的行为一致，而不是把异常一路抛到页面上 */
        try {
            long execId = runner.openExec(req);
            return MemoryScopeCtx.run(heartbeatScope(agentId),
                    () -> AgentScope.run(agentId, () -> runner.run(req, execId)));
        } catch (RuntimeException e) {
            log.warn("职责「{}」执行失败：{}", plan.name(), e.getMessage());
            return new AgentRunner.Outcome(-1, "failed", null, e.getMessage());
        }
    }

    // ---------- 职责的工具分发 ----------

    /**
     * 职责实例的工具分发：会话工具（open_thread / send_message）的语义属于后台自己，先在这里接住；
     * 其余（检索、读文档、写库……）交给知识库工具箱。
     */
    private ToolResult planTool(String agentId, JsonNode call) {
        String name = call.path("function").path("name").asText("");
        boolean threadTool = OpenThreadTool.NAME.equals(name)
                || SendMessageTool.NAME.equals(name);
        if (!threadTool) {
            return toolbox.run(call, "plan");
        }
        String args = call.path("function").path("arguments").asText("");
        JsonNode parsed = readTree(args);
        String content = OpenThreadTool.NAME.equals(name)
                ? openThread(parsed)
                : sendMessage(parsed);
        return new ToolResult(name, args, content, false);
    }

    /**
     * open_thread：按会话名在首页 chat 里开/取一个会话（同名复用、无则新建），返回会话 id；
     * 职责随后用 send_message 往这个会话投消息。执行收敛在 {@link ChatThreads}（会话表只经它写）。
     */
    private String openThread(JsonNode args) {
        String title = args.path("title").asText("").trim();
        if (title.isEmpty()) {
            return "缺少 title：请给出会话名（同一主题始终用同一个名字，便于复用累积）。";
        }
        String hubId = threads.openOrCreate(title);
        return "会话已就绪：id=" + hubId + "，name=" + title + "。用 "
                + SendMessageTool.NAME + " 往这个 id 投消息（可连投多条）。";
    }

    /**
     * send_message：往首页 chat 的会话里投一条消息（正文即要说给人听的话，支持 markdown，
     * 图片直接写成 {@code ![](地址)}）。投完把该会话标记为未读，首页「最近会话」会打点提示。
     * 执行收敛在 {@link ChatThreads}（消息表只经它写）。
     */
    private String sendMessage(JsonNode args) {
        String threadId = args.path("thread_id").asText("").trim();
        if (threadId.isEmpty()) {
            return "缺少 thread_id：请先用 " + OpenThreadTool.NAME + " 开一个会话。";
        }
        String content = args.path("content").asText("").trim();
        if (content.isEmpty()) {
            return "缺少 content：消息正文不能为空。";
        }
        return threads.appendAssistantMessage(threadId, content)
                .map(title -> "已投递到会话「" + title + "」。")
                .orElse("会话 id=" + threadId + " 不存在，请重新用 "
                        + OpenThreadTool.NAME + " 开一个会话。");
    }

    /** 声明了哪些渠道（未知渠道忽略） */
    private Set<String> channels(String json) {
        Set<String> set = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return set;
        }
        try {
            for (JsonNode n : om.readTree(json)) {
                String c = n.asText("");
                if (CHANNELS.contains(c)) {
                    set.add(c);
                }
            }
        } catch (Exception e) {
            log.warn("渠道声明解析失败（按无渠道处理）：{}", e.getMessage());
        }
        return set;
    }

    // ---------- 供大模型调用的职责工具（对话侧转交过来） ----------

    /**
     * 执行职责工具（对话侧转交过来）：派职责，以及立 / 改 / 撤编制、看名册。
     * 由 {@code ChatTurnRunner} 在工具分发时转交过来——工具的语义属于后台模块自己。
     */
    @Override
    public String runTool(String agentId, JsonNode call) {
        String name = call.path("function").path("name").asText("");
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case DelegatePlanTool.NAME -> delegatePlanTool(agentId, args);
            case CreatePlanTool.NAME -> createPlanTool(agentId, args);
            case UpdatePlanTool.NAME -> updatePlanTool(agentId, args);
            case DeletePlanTool.NAME -> deletePlanTool(agentId, args);
            case ListPlansTool.NAME -> listPlansText(agentId);
            default -> "未知工具: " + name;
        };
    }

    private String delegatePlanTool(String agentId, JsonNode args) {
        String instruction = args.path("instruction").asText("").trim();
        if (instruction.isEmpty()) {
            return "缺少 instruction：请说明这一轮要让职责做什么。";
        }
        String planRef = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(agentId, planRef);
        if (plan == null) {
            return "派不出去：" + (planRef.isEmpty() ? "没有指定 plan" : "找不到职责「" + planRef + "」")
                    + "。当前名册：" + planRoster(agentId) + "。"
                    + "一次性的活可以直接派 " + DelegateSubagentTool.NAME + "。";
        }
        /* 停用是人踩的刹车：模型看得见的名字也可能是刚被停掉的，这里如实告知 */
        if (!plan.enabled()) {
            return disabledText(plan);
        }
        AgentRunner.Outcome outcome;
        try {
            outcome = delegate(agentId, instruction, plan);
        } catch (RuntimeException e) {
            return "职责执行失败：" + e.getMessage();
        }
        if (!"done".equals(outcome.status())) {
            return "职责执行失败：" + (outcome.error() == null ? "未知原因" : outcome.error());
        }
        return "（执行 id=" + outcome.execId() + "，如需核对其检索过程可调用 "
                + ReadSubagentRunTool.NAME + "）\n" + outcome.output();
    }

    /** 把一份职责派出去（供大模型调用）：独占一批 run 同步执行，返回结论（过程完整留痕，可用 id 回看） */
    public AgentRunner.Outcome delegate(String agentId, String instruction, PlanStore.PlanRow plan) {
        long beat = beatSeq.incrementAndGet();
        String runId = UUID.randomUUID().toString();
        execs.insertRun(runId, agentId, beat, "chat");
        AgentRunner.Outcome outcome = runChild(agentId, runId, new PlanStore.PlanRow(plan.id(),
                plan.agentId(), plan.name(), plan.role(), plan.content(), plan.tools(), plan.channels(),
                plan.intervalMs(), instruction, plan.modelId(), plan.rationale(), plan.sort(),
                plan.lastBeatAt(), true), null);
        execs.finishRun(runId, outcome.status(), 1, "对话委托");
        return outcome;
    }

    /**
     * 立一份编制：给名字与正文就够，其余按缺省（system / 只留痕 / 无工具 / 不设节拍）。
     * 归属给这个 Agent（{@code agentId}）；素材由它执行时自己调工具现取，正文只写正文。
     */
    private String createPlanTool(String agentId, JsonNode args) {
        String name = args.path("name").asText("").trim();
        String content = args.path("content").asText("");
        if (name.isEmpty()) {
            return "缺少 name：给这份编制起个名字（之后派活、改写都用它指认）。";
        }
        if (content.isBlank()) {
            return "缺少 content：编制的提示词正文不能为空。";
        }
        if (plans.planByName(agentId, name).isPresent()) {
            return "已有同名职责「" + name + "」：换个名字，或改用 "
                    + UpdatePlanTool.NAME + " 改写它。";
        }
        List<PlanStore.PlanRow> all = plans.plans(agentId);
        if (all.size() >= MAX_PLANS) {
            return "职责名册已满（上限 " + MAX_PLANS + " 份）：先用 "
                    + DeletePlanTool.NAME + " 撤掉不用的，再立新的。";
        }
        String id = UUID.randomUUID().toString();
        int sort = all.stream().mapToInt(PlanStore.PlanRow::sort).max().orElse(0) + 1;
        List<String> tools = stringList(args.path("tools"));
        List<String> channels = channelList(args.path("channels"));
        plans.insertPlan(new PlanStore.PlanRow(id, agentId, name, roleOf(args), content,
                writeList(tools), writeList(channels), intervalOf(args.path("interval_ms")), null,
                null, rationaleOf(args), sort, null, true));
        return "职责已立好：name=" + name + "，id=" + id + "（角色 " + roleOf(args)
                + "，工具：" + joinText(tools) + "，渠道：" + joinText(channels) + "）。"
                + "现在可以用 " + DelegatePlanTool.NAME + " 派它干活。";
    }

    /** 改写一份编制：只动模型给出的字段，没提的一律保持原样 */
    private String updatePlanTool(String agentId, JsonNode args) {
        String ref = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(agentId, ref);
        if (plan == null) {
            return notFound(agentId, ref);
        }
        String name = plan.name();
        if (args.hasNonNull("name")) {
            String next = args.path("name").asText("").trim();
            if (next.isEmpty()) {
                return "name 不能为空（不想改就别传这个字段）。";
            }
            if (!next.equals(name) && plans.planByName(agentId, next).isPresent()) {
                return "已有同名职责「" + next + "」：换个名字。";
            }
            name = next;
        }
        String content = args.hasNonNull("content") ? args.path("content").asText("") : plan.content();
        if (content == null || content.isBlank()) {
            return "content 不能为空。";
        }
        String role = args.hasNonNull("role") ? roleOf(args) : plan.role();
        String tools = args.has("tools") ? writeList(stringList(args.path("tools"))) : plan.tools();
        String channels = args.has("channels") ? writeList(channelList(args.path("channels")))
                : plan.channels();
        Long interval = args.has("interval_ms") ? intervalOf(args.path("interval_ms"))
                : plan.intervalMs();
        /* 由来只在给了的时候改写：改写正文不该顺手把「为什么立它」抹掉 */
        String rationale = args.hasNonNull("rationale") ? rationaleOf(args) : plan.rationale();
        /* 启停是人的刹车，不归模型改：改写时原样带着，模型只能改定义 */
        plans.updatePlan(new PlanStore.PlanRow(plan.id(), plan.agentId(), name, role, content, tools,
                channels, interval, plan.input(), plan.modelId(), rationale, plan.sort(),
                plan.lastBeatAt(), plan.enabled()));
        return "职责已改写：「" + name + "」（id=" + plan.id() + "）。它下一轮被派出时就按新定义做事。";
    }

    /** 撤一份编制：定义没了，历史留痕（exec / 写库清单）原样保留，便于事后复盘 */
    private String deletePlanTool(String agentId, JsonNode args) {
        String ref = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(agentId, ref);
        if (plan == null) {
            return notFound(agentId, ref);
        }
        plans.deletePlan(plan.id());
        return "职责已撤掉：「" + plan.name() + "」，此后不再被派活（它过去的执行留痕仍在）。";
    }

    /** list_heartbeats 的落地：回给模型这个 Agent 自己的职责名册（人话文本） */
    public String listPlansText(String agentId) {
        List<PlanStore.PlanRow> all = plans.plans(agentId);
        if (all.isEmpty()) {
            return "（名册为空。可以用 " + CreatePlanTool.NAME + " 立一份职责。）";
        }
        StringBuilder sb = new StringBuilder();
        for (PlanStore.PlanRow p : all) {
            sb.append("- ").append(p.name()).append("（plan=").append(p.id()).append("）｜")
                    .append(p.enabled() ? "启用" : "已停用").append("｜渠道：")
                    .append(joinText(names(p.channels()))).append("｜")
                    .append(cadence(p)).append("｜")
                    .append(p.lastBeatAt() == null ? "从未派过"
                            : "上次 " + ago(p.lastBeatAt()));
            String head = text(p.content()).replaceAll("\\s+", " ");
            if (!head.isEmpty()) {
                sb.append("\n  它做的事：")
                        .append(head.length() > 160 ? head.substring(0, 160) + "…" : head);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 按 id 或名字在<b>某个 Agent 自己的名册</b>里找计划：名字是模型最顺手的引用（id 是 uuid，不友好）。
     * 找不到就返回 null——不现造兜底壳，编制一律由大模型自己立。
     */
    private PlanStore.PlanRow resolvePlan(String agentId, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return plans.planById(ref).filter(p -> agentId.equals(p.agentId()))
                .orElseGet(() -> plans.planByName(agentId, ref).orElse(null));
    }

    private String notFound(String agentId, String ref) {
        return "找不到职责：" + (ref == null || ref.isBlank() ? "没有指定 plan" : "「" + ref + "」")
                + "。当前名册：" + planRoster(agentId) + "。";
    }

    /** 现有计划名册：派不出去/找不到时回给模型，好让它改口派对人 */
    private String planRoster(String agentId) {
        List<PlanStore.PlanRow> all = plans.plans(agentId);
        if (all.isEmpty()) {
            return "（空，可以先用 " + CreatePlanTool.NAME + " 立一个）";
        }
        return String.join("、", all.stream().map(PlanStore.PlanRow::name).toList());
    }

    // ---------- 工具与序列化助手 ----------

    /**
     * 这份编制这次能用的 tools schema = 它声明的工具（管理类与会话工具拿不到，见
     * {@link cc.ginpika.minis.ai.core.tool.ToolManager#toolsOf(List, cc.ginpika.minis.ai.core.tool.ToolSurface)}）
     * + thread 渠道附带的会话工具（open_thread / send_message）；都没有则返回 null（不挂 tools）。
     */
    private ArrayNode toolSchemas(PlanStore.PlanRow plan) {
        ArrayNode picked = om.createArrayNode();
        if (channels(plan.channels()).contains(CH_THREAD)) {
            picked.addAll(tools.toolsOf(ToolSurface.THREAD));
        }
        picked.addAll(tools.toolsOf(names(plan.tools()), ToolSurface.CHAT));
        return picked.isEmpty() ? null : picked;
    }

    /** 停用职责被派时的说明：人在页面上踩了刹车，模型不该把它再点着 */
    private static String disabledText(PlanStore.PlanRow plan) {
        return "「" + plan.name() + "」已被停用：先在它的后台页启用，或改用别的职责。";
    }

    /** 立这条职责的由来：一句话，没给就留空（页面于是不显示这一行） */
    private static String rationaleOf(JsonNode args) {
        String s = args.path("rationale").asText("").trim();
        return s.isEmpty() ? null : s;
    }

    /** 角色：只认 system / user，其余（含缺省）一律当 system */
    private static String roleOf(JsonNode args) {
        return "user".equalsIgnoreCase(args.path("role").asText("").trim()) ? "user" : "system";
    }

    /** 节拍参考（毫秒）：非正数一律当作「不设节拍」（每拍都可以考虑派它） */
    private static Long intervalOf(JsonNode node) {
        long v = node == null ? 0L : node.asLong(0L);
        return v > 0 ? v : null;
    }

    /** 工具调用参数里的字符串数组 → 去重后的清单（坏数据退化为空清单） */
    private static List<String> stringList(JsonNode node) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText("").trim();
                if (!s.isEmpty()) {
                    set.add(s);
                }
            }
        }
        return List.copyOf(set);
    }

    /** 交付渠道：只认已知渠道；一个都没给就退到「静默留痕」（产出恒定留痕，这是最低限度） */
    private static List<String> channelList(JsonNode node) {
        List<String> picked = stringList(node).stream().filter(CHANNELS::contains).toList();
        return picked.isEmpty() ? List.of(CH_SILENT) : picked;
    }

    /** 字符串清单 → JSON 数组文本；空清单落 null，让「未声明」与「声明为空」在库里是同一种状态 */
    private String writeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("清单序列化失败（按未声明处理）：{}", e.getMessage());
            return null;
        }
    }

    /** JSON 数组文本 → 字符串清单（坏数据退化为空清单） */
    private List<String> names(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("职责工具声明解析失败（按无工具处理）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 清单 → 「、」相连的人话；空清单给「无」 */
    private static String joinText(List<String> list) {
        return list == null || list.isEmpty() ? "无" : String.join("、", list);
    }

    /** 名册里的节拍说明：interval_ms 只是给主会话的参考，不是它必须遵守的时刻表 */
    private static String cadence(PlanStore.PlanRow p) {
        Long ms = p.intervalMs();
        if (ms == null || ms <= 0) {
            return "每拍";
        }
        if (ms % 3_600_000L == 0) {
            return "每约 " + ms / 3_600_000L + " 小时";
        }
        if (ms % 60_000L == 0) {
            return "每约 " + ms / 60_000L + " 分钟";
        }
        return "每约 " + Math.max(1, ms / 1000L) + " 秒";
    }

    /** 上次派出的时间 → 「多久以前」 */
    private static String ago(long at) {
        long minutes = Math.max(0L, (System.currentTimeMillis() - at) / 60_000L);
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        return hours < 24 ? hours + " 小时前" : hours / 24 + " 天前";
    }

    /** 心跳留痕里的展示名：取 Agent 的展示名，找不到则退回「心跳」 */
    private String heartbeatTitle(String agentId) {
        String name = directory.nameOf(agentId);
        return name == null || name.isBlank() || name.equals(agentId) ? DEFAULT_TITLE : name;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String text(String s) {
        return s == null ? "" : s.trim();
    }

    private static ToolResult plain(String name, String args, String content) {
        return new ToolResult(name, args, content, false);
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
