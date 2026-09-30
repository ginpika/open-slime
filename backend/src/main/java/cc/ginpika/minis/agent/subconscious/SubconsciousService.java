package cc.ginpika.minis.agent.subconscious;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.core.type.TypeReference;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.agent.chat.ChatThreads;
import cc.ginpika.minis.agent.chat.SubagentTool;
import cc.ginpika.minis.agent.chat.SubconsciousTool;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subconscious.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.subconscious.DelegatePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subconscious.DeletePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.subconscious.UpdatePlanTool;
import cc.ginpika.minis.ai.core.tool.thread.OpenThreadTool;
import cc.ginpika.minis.ai.core.tool.thread.SendMessageTool;
import cc.ginpika.minis.config.SubconsciousProps;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 潜意识：HeartBeat 驱动的「任务计划」编排。
 *
 * <p>两层概念在这一侧只有一层半：心跳主会话（总线）与任务计划（常驻编制）。
 * <ul>
 *   <li><b>心跳主会话</b>——{@code sub_heartbeat} 单行：正文 + 插槽绑定 + 模型。每敲一拍先由它出场，
 *       看着 {{agents}} 名册决定这一拍派谁（{@code delegate_plan}）、缺人手时自己攒编制
 *       （{@code create_plan} / {@code update_plan} / {@code delete_plan}），闲不住也可以当场派子代理去查。</li>
 *   <li><b>任务计划</b>——{@code sub_plan} 名册：一份带正文/工具/渠道的常驻编制，带节拍参考与上次派出时间。
 *       每被派出一次就是起一个匿名实例跑一遍（执行留痕与子代理同表，kind=plan）。</li>
 * </ul>
 *
 * <p>子代理（匿名 worker）不在这一侧：它由 {@link SubagentService} 负责，主对话与心跳主会话都能派它。
 * 两侧共用同一个执行引擎（{@link AgentRunner}）——因为「被派出去跑一次」这件事是一样的。
 *
 * <p>产出按渠道交付（计划级声明，二选一）：
 * <ul>
 *   <li>{@code silent} —— 静默：只留痕（exec 行本身），不打扰人</li>
 *   <li>{@code thread} —— 会话：计划额外拿到 {@code open_thread} / {@code send_message} 两个工具，
 *       按名字在首页 chat 里开（同名复用、无则新建）一个会话，把要说给人听的话投进去；
 *       首页「最近会话」会为该会话打未读点，用户点开即见</li>
 * </ul>
 */
@Service
public class SubconsciousService implements SubconsciousTool {

    private static final Logger log = LoggerFactory.getLogger(SubconsciousService.class);

    /** 交付渠道：静默——只留痕，不主动对人说话 */
    public static final String CH_SILENT = "silent";
    /** 交付渠道：会话——计划可用 open_thread / send_message 往首页 chat 的会话投递消息 */
    public static final String CH_THREAD = "thread";

    /** 所有合法渠道（顺序即前端展示顺序） */
    public static final List<String> CHANNELS = List.of(CH_SILENT, CH_THREAD);

    /** 主会话在留痕里显示的名字（它不落 sub_plan，只落一行 exec） */
    private static final String HEARTBEAT_NAME = "心跳主会话";

    /**
     * 编制上限：大模型对编制有全权限，留一个阀门防止它一拍接一拍地攒出一份读不完的名册。
     */
    private static final int MAX_PLANS = 24;

    /** 一次心跳的摘要：agents = 这一拍真正跑起来的执行数（计划 + 子代理） */
    public record BeatSummary(String runId, long beat, String trigger, int agents) {
    }

    private final SubconsciousProps props;
    private final PlanStore plans;
    private final ExecStore execs;
    private final SubconsciousContextBuilder builder;
    private final SubconsciousInjectors injectors;
    private final AgentRunner runner;
    private final SubagentTool subagent;
    private final KnowledgeToolbox toolbox;
    private final TerminalService terminal;
    private final ToolManager tools;
    private final ObjectMapper om;
    /** 首页 chat 的会话投递落点（open_thread / send_message）：只碰会话表与消息表，故无构造环 */
    private final ChatThreads threads;

    /** 心跳调度器（单线程：按节拍唤醒主会话；上一拍没跑完就不敲下一拍） */
    private ScheduledExecutorService heartbeat;

    /** 全局心跳序号 */
    private final AtomicLong beatSeq = new AtomicLong();

    /** 同一时刻只允许一轮在跑，避免手动与自动叠加 */
    private final AtomicBoolean beating = new AtomicBoolean();

    /** 运行时心跳开关（页面可切，不改配置文件） */
    private final AtomicBoolean heartbeatOn = new AtomicBoolean();

    /** 当前节拍（页面可调） */
    private final AtomicLong tickMs = new AtomicLong();

    public SubconsciousService(SubconsciousProps props, PlanStore plans, ExecStore execs,
                               SubconsciousContextBuilder builder,
                               SubconsciousInjectors injectors, AgentRunner runner,
                               SubagentTool subagent, KnowledgeToolbox toolbox,
                               TerminalService terminal, ToolManager tools,
                               ObjectMapper om, ChatThreads threads) {
        this.props = props;
        this.plans = plans;
        this.execs = execs;
        this.builder = builder;
        this.injectors = injectors;
        this.runner = runner;
        this.subagent = subagent;
        this.toolbox = toolbox;
        this.terminal = terminal;
        this.tools = tools;
        this.om = om;
        this.threads = threads;
    }

    // ---------- 心跳生命周期 ----------

    @PostConstruct
    void start() {
        /* 启动初值取自配置（默认关）：心跳需用户在潜意识页手动开启，重启后回到关闭态 */
        heartbeatOn.set(props.isEnabled());
        tickMs.set(Math.max(5_000L, props.getTickMs()));
        applySchedule();
        log.info("潜意识心跳已就绪：enabled={} tickMs={} maxAgentsPerBeat={} maxToolRounds={}",
                heartbeatOn.get(), tickMs.get(), props.getMaxAgentsPerBeat(), props.getMaxToolRounds());
    }

    @PreDestroy
    void stop() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
    }

    /** 按当前节拍重排心跳；开关关闭时只保留调度器空转，不触发执行 */
    private synchronized void applySchedule() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
        }
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subconscious-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleWithFixedDelay(this::tick, props.getInitialDelayMs(), tickMs.get(),
                TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (!heartbeatOn.get()) {
            return;
        }
        try {
            beat("beat");
        } catch (RuntimeException e) {
            log.warn("潜意识心跳异常：{}", e.getMessage());
        }
    }

    public boolean heartbeatEnabled() {
        return heartbeatOn.get();
    }

    public long tickMs() {
        return tickMs.get();
    }

    public boolean running() {
        return beating.get();
    }

    /** 运行时切换心跳开关 */
    public void setHeartbeatEnabled(boolean on) {
        heartbeatOn.set(on);
    }

    /** 运行时调整节拍（重排调度器） */
    public void setTickMs(long ms) {
        tickMs.set(Math.max(5_000L, ms));
        applySchedule();
    }

    // ---------- 心跳：主会话出场，按需派活 ----------

    /**
     * 敲一次心跳：先让心跳主会话出场——它看着任务计划名册决定这一拍派谁、怎么派；
     * 它派出去的每一次执行与它自己同属这一批 run，一次心跳即可看全。
     *
     * @param trigger beat=自动节拍 / manual=页面手动敲一次（只是留痕上的区别，派谁仍由主会话说了算）
     * @return 本轮摘要；若已有一轮在跑则返回 null
     */
    public BeatSummary beat(String trigger) {
        if (!beating.compareAndSet(false, true)) {
            return null;
        }
        try {
            long beat = beatSeq.incrementAndGet();
            String runId = UUID.randomUUID().toString();
            execs.insertRun(runId, beat, trigger);

            /* 主会话自己那份上下文：库里没配过就退到内置正文 */
            PlanStore.HeartbeatRow hb = plans.heartbeat().orElseGet(() ->
                    new PlanStore.HeartbeatRow(SubconsciousPrompts.HEARTBEAT,
                            SubconsciousPrompts.heartbeatInjectors(), null));
            SubconsciousContextBuilder.Built built = builder.build(hb.content(), hb.injectors());

            AtomicInteger agents = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AgentRunner.Outcome outcome;
            try {
                outcome = runner.run(new AgentRunner.Request(runId, ExecStore.KIND_HEARTBEAT,
                        PlanStore.HEARTBEAT_ID, HEARTBEAT_NAME, hb.modelId(), built.messages(),
                        tools.toolsOf(ToolSurface.HEARTBEAT),
                        (call, execId) -> heartbeatTool(runId, call, agents, failed)));
            } catch (RuntimeException e) {
                log.warn("心跳主会话执行失败：{}", e.getMessage());
                outcome = new AgentRunner.Outcome(-1, "failed", null, e.getMessage());
            }

            /* 这一拍真正跑起来的执行数：减去主会话自己那一行 */
            int spawned = Math.max(0, execs.execsByRun(runId).size() - 1);
            boolean bad = !"done".equals(outcome.status()) || failed.get() > 0;
            execs.finishRun(runId, bad ? "failed" : "done", spawned,
                    bad ? (failed.get() > 0 ? failed.get() + " 个执行失败" : "主会话未能给出结论") : null);
            log.info("潜意识心跳 #{}（{}）：这一拍跑起 {} 个执行，失败 {}",
                    beat, trigger, spawned, failed.get());
            return new BeatSummary(runId, beat, trigger, spawned);
        } finally {
            beating.set(false);
        }
    }

    /**
     * 单跑一个任务计划（页面上的「立即执行」）：绕过主会话直接把这份编制派出去，独占一批。
     * 这是人点名要跑，所以不看节拍、也不由主会话裁量。
     */
    public BeatSummary runPlan(String planId) {
        PlanStore.PlanRow plan = plans.plan(planId).orElse(null);
        if (plan == null) {
            return null;
        }
        if (!beating.compareAndSet(false, true)) {
            return null;
        }
        try {
            long beat = beatSeq.incrementAndGet();
            String runId = UUID.randomUUID().toString();
            execs.insertRun(runId, beat, "manual");
            AgentRunner.Outcome outcome = runChild(runId, plan);
            execs.finishRun(runId, outcome.status(), 1, "页面点名执行");
            return new BeatSummary(runId, beat, "manual", 1);
        } finally {
            beating.set(false);
        }
    }

    /**
     * 主会话的工具分发：派计划、「管子计划本身」的工具（增删改）、翻过程，以及当场派子代理——
     * 前四类的语义属于潜意识自己，后两类转交子代理模块；其余（只读检索）交给知识库工具箱。
     */
    private ToolResult heartbeatTool(String runId, JsonNode call,
                                                      AtomicInteger agents, AtomicInteger failed) {
        String name = call.path("function").path("name").asText("");
        String args = call.path("function").path("arguments").asText("");
        if (ReadSubagentRunTool.NAME.equals(name) || DelegateSubagentTool.NAME.equals(name)) {
            /* 子代理那套由子代理模块执行；它派出的执行与前两类同属这一批 run */
            return new ToolResult(name, args,
                    subagent.runTool(call, runId, "beat"), false);
        }
        if (TerminalService.handles(name)) {
            /* 命令行那套由终端模块执行（逐目标开关 / 白黑名单都在它那一侧判定） */
            return terminal.run(call, TerminalService.SOURCE_HEARTBEAT);
        }
        ToolResult direct = switch (name) {
            case CreatePlanTool.NAME -> plain(name, args, createPlanTool(readTree(args)));
            case UpdatePlanTool.NAME -> plain(name, args, updatePlanTool(readTree(args)));
            case DeletePlanTool.NAME -> plain(name, args, deletePlanTool(readTree(args)));
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
                    "缺少 instruction：请说明这一轮要让计划做什么。", false);
        }
        /* 单拍派活上限：每派一个都是一次完整的 LLM 往返，留个阀门防它一口气派飞 */
        if (agents.get() >= Math.max(1, props.getMaxAgentsPerBeat())) {
            return new ToolResult(name, args,
                    "这一拍派出去的活已达上限，请就现有结果收束。", false);
        }
        agents.incrementAndGet();
        try {
            AgentRunner.Outcome outcome = runChild(runId,
                    delegatePlan(parsed.path("plan").asText("").trim(), instruction));
            if (!"done".equals(outcome.status())) {
                failed.incrementAndGet();
                return new ToolResult(name, args, "计划执行失败："
                        + (outcome.error() == null ? "未知原因" : outcome.error()), false);
            }
            return new ToolResult(name, args,
                    "（执行 id=" + outcome.execId() + "，如需核对它的过程可调用 "
                            + ReadSubagentRunTool.NAME + "）\n" + outcome.output(), false);
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            return new ToolResult(name, args, "计划执行失败：" + e.getMessage(), false);
        }
    }

    /**
     * 取出这一拍要派的计划：按名字或 id 找到它（{@link #resolvePlan}），沿用它的正文、工具与模型，
     * 只把「这一轮具体做什么」换成委托方给的 instruction。找不到则抛异常——不现造兜底壳，
     * 编制一律由大模型自己立（{@link CreatePlanTool}）；由调用方兜成一条工具结果。
     */
    private PlanStore.PlanRow delegatePlan(String planRef, String instruction) {
        PlanStore.PlanRow plan = resolvePlan(planRef);
        if (plan == null) {
            throw new IllegalArgumentException(planRef.isBlank()
                    ? "没有指定 plan" : "找不到任务计划「" + planRef + "」");
        }
        return new PlanStore.PlanRow(plan.id(), plan.name(), plan.role(), plan.content(),
                plan.tools(), plan.injectors(), plan.channels(), plan.intervalMs(), instruction,
                plan.modelId(), plan.sort(), plan.lastBeatAt());
    }

    /** 派出一份编制：用哪个计划已定，与调用方同属一批 run */
    private AgentRunner.Outcome runChild(String runId, PlanStore.PlanRow plan) {
        AgentRunner.Outcome outcome = execute(runId, plan);
        if (plan.id() != null) {
            plans.markPlanBeat(plan.id(), System.currentTimeMillis());
        }
        return outcome;
    }

    /** 执行一份编制：组装它那份上下文（正文 + 插槽素材 + 本轮交代）→ 交给执行引擎 */
    private AgentRunner.Outcome execute(String runId, PlanStore.PlanRow plan) {
        SubconsciousContextBuilder.Built built = builder.build(plan);
        try {
            return runner.run(new AgentRunner.Request(runId, ExecStore.KIND_PLAN, plan.id(),
                    plan.name(), plan.modelId(), built.messages(), toolSchemas(plan),
                    (call, execId) -> planTool(call)));
        } catch (RuntimeException e) {
            log.warn("计划「{}」执行失败：{}", plan.name(), e.getMessage());
            return new AgentRunner.Outcome(-1, "failed", null, e.getMessage());
        }
    }

    // ---------- 计划的工具分发 ----------

    /**
     * 计划实例的工具分发：会话工具（open_thread / send_message）的语义属于潜意识自己，先在这里接住；
     * 其余（检索、读文档、写库……）交给知识库工具箱。
     */
    private ToolResult planTool(JsonNode call) {
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
     * 计划随后用 send_message 往这个会话投消息。执行收敛在 {@link ChatThreads}（会话表只经它写）。
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

    // ---------- 供大模型调用的四个计划工具 ----------

    /**
     * 执行计划工具（对话侧转交过来）：派计划，以及立 / 改 / 撤编制。
     * 由 {@code ChatService} 在工具分发时转交过来——工具的语义属于潜意识模块自己。
     */
    public String runTool(JsonNode call) {
        String name = call.path("function").path("name").asText("");
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case DelegatePlanTool.NAME -> delegatePlanTool(args);
            case CreatePlanTool.NAME -> createPlanTool(args);
            case UpdatePlanTool.NAME -> updatePlanTool(args);
            case DeletePlanTool.NAME -> deletePlanTool(args);
            default -> "未知工具: " + name;
        };
    }

    private String delegatePlanTool(JsonNode args) {
        String instruction = args.path("instruction").asText("").trim();
        if (instruction.isEmpty()) {
            return "缺少 instruction：请说明这一轮要让计划做什么。";
        }
        String planRef = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(planRef);
        if (plan == null) {
            return "派不出去：" + (planRef.isEmpty() ? "没有指定 plan" : "找不到任务计划「" + planRef + "」")
                    + "。当前名册：" + planRoster() + "。"
                    + "一次性的活可以直接派 " + DelegateSubagentTool.NAME + "。";
        }
        AgentRunner.Outcome outcome;
        try {
            outcome = delegate(instruction, plan);
        } catch (RuntimeException e) {
            return "计划执行失败：" + e.getMessage();
        }
        if (!"done".equals(outcome.status())) {
            return "计划执行失败：" + (outcome.error() == null ? "未知原因" : outcome.error());
        }
        return "（执行 id=" + outcome.execId() + "，如需核对其检索过程可调用 "
                + ReadSubagentRunTool.NAME + "）\n" + outcome.output();
    }

    /** 把一份编制派出去（供大模型调用）：独占一批 run 同步执行，返回结论（过程完整留痕，可用 id 回看） */
    public AgentRunner.Outcome delegate(String instruction, PlanStore.PlanRow plan) {
        long beat = beatSeq.incrementAndGet();
        String runId = UUID.randomUUID().toString();
        execs.insertRun(runId, beat, "chat");
        AgentRunner.Outcome outcome = runChild(runId, new PlanStore.PlanRow(plan.id(), plan.name(),
                plan.role(), plan.content(), plan.tools(), plan.injectors(), plan.channels(),
                plan.intervalMs(), instruction, plan.modelId(), plan.sort(), plan.lastBeatAt()));
        execs.finishRun(runId, outcome.status(), 1, "对话委托");
        return outcome;
    }

    /**
     * 立一份编制：给名字与正文就够，其余按缺省（system / 只留痕 / 无工具 / 不设节拍）。
     * 正文里与注入器同名的插槽（如 {@code {{now}}}）会自动绑上对应注入器，模型不必另配绑定表。
     */
    private String createPlanTool(JsonNode args) {
        String name = args.path("name").asText("").trim();
        String content = args.path("content").asText("");
        if (name.isEmpty()) {
            return "缺少 name：给这份编制起个名字（之后派活、改写都用它指认）。";
        }
        if (content.isBlank()) {
            return "缺少 content：编制的提示词正文不能为空。";
        }
        if (plans.planByName(name).isPresent()) {
            return "已有同名任务计划「" + name + "」：换个名字，或改用 "
                    + UpdatePlanTool.NAME + " 改写它。";
        }
        List<PlanStore.PlanRow> all = plans.plans();
        if (all.size() >= MAX_PLANS) {
            return "任务计划名册已满（上限 " + MAX_PLANS + " 份）：先用 "
                    + DeletePlanTool.NAME + " 撤掉不用的，再立新的。";
        }
        String id = UUID.randomUUID().toString();
        int sort = all.stream().mapToInt(PlanStore.PlanRow::sort).max().orElse(0) + 1;
        List<String> tools = stringList(args.path("tools"));
        List<String> channels = channelList(args.path("channels"));
        plans.insertPlan(new PlanStore.PlanRow(id, name, roleOf(args), content,
                writeList(tools), bindingsFor(content, null), writeList(channels),
                intervalOf(args.path("interval_ms")), null, null, sort, null));
        return "任务计划已立好：name=" + name + "，id=" + id + "（角色 " + roleOf(args)
                + "，工具：" + joinText(tools) + "，渠道：" + joinText(channels) + "）。"
                + "现在可以用 " + DelegatePlanTool.NAME + " 派它干活。";
    }

    /** 改写一份编制：只动模型给出的字段，没提的一律保持原样（正文改了则重新自动绑定插槽） */
    private String updatePlanTool(JsonNode args) {
        String ref = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(ref);
        if (plan == null) {
            return notFound(ref);
        }
        String name = plan.name();
        if (args.hasNonNull("name")) {
            String next = args.path("name").asText("").trim();
            if (next.isEmpty()) {
                return "name 不能为空（不想改就别传这个字段）。";
            }
            if (!next.equals(name) && plans.planByName(next).isPresent()) {
                return "已有同名任务计划「" + next + "」：换个名字。";
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
        String bindings = args.hasNonNull("content")
                ? bindingsFor(content, plan.injectors())
                : plan.injectors();
        plans.updatePlan(new PlanStore.PlanRow(plan.id(), name, role, content, tools,
                bindings, channels, interval, plan.input(), plan.modelId(), plan.sort(),
                plan.lastBeatAt()));
        return "任务计划已改写：「" + name + "」（id=" + plan.id() + "）。它下一轮被派出时就按新定义做事。";
    }

    /** 撤一份编制：定义没了，历史留痕（exec / 写库清单）原样保留，便于事后复盘 */
    private String deletePlanTool(JsonNode args) {
        String ref = args.path("plan").asText("").trim();
        PlanStore.PlanRow plan = resolvePlan(ref);
        if (plan == null) {
            return notFound(ref);
        }
        plans.deletePlan(plan.id());
        return "任务计划已撤掉：「" + plan.name() + "」，此后不再被派活（它过去的执行留痕仍在）。";
    }

    /**
     * 按 id 或名字找计划：名字是模型最顺手的引用（id 是 uuid，对模型不友好）。
     * 找不到就返回 null——不现造兜底壳，编制一律由大模型自己立。
     */
    private PlanStore.PlanRow resolvePlan(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return plans.plan(ref).orElseGet(() -> plans.planByName(ref).orElse(null));
    }

    private String notFound(String ref) {
        return "找不到任务计划：" + (ref == null || ref.isBlank() ? "没有指定 plan" : "「" + ref + "」")
                + "。当前名册：" + planRoster() + "。";
    }

    /** 现有计划名册：派不出去/找不到时回给模型，好让它改口派对人 */
    private String planRoster() {
        List<PlanStore.PlanRow> all = plans.plans();
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

    /**
     * 自动插槽绑定：正文里 {@code {{插槽}}} 的名字若正好是个注入器（{@code {{now}}}、{@code {{stats}}}、
     * {@code {{graph_hubs}}}……），就把它绑上去。模型写提示词时顺手写插槽即可，不必另配一张绑定表。
     *
     * <p>原有绑定先原样留着：它们可能带着参数（{@code doc_fulltext=12}），不该因为一次改写就被抹掉。
     */
    private String bindingsFor(String content, String previous) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (JsonNode b : arrayOf(previous)) {
            String slot = b.path("slot").asText("").trim();
            String injector = b.path("injector").asText("").trim();
            if (!slot.isEmpty() && !injector.isEmpty()) {
                merged.put(slot, injector);
            }
        }
        Matcher m = SubconsciousPrompts.SLOT.matcher(content == null ? "" : content);
        while (m.find()) {
            String slot = m.group(1).trim();
            if (injectors.names().contains(slot)) {
                merged.putIfAbsent(slot, slot);
            }
        }
        if (merged.isEmpty()) {
            return null;
        }
        ArrayNode arr = om.createArrayNode();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            arr.addObject().put("slot", e.getKey()).put("injector", e.getValue()).put("args", "");
        }
        return arr.toString();
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
            log.warn("计划工具声明解析失败（按无工具处理）：{}", e.getMessage());
            return List.of();
        }
    }

    /** JSON 数组文本 → 可遍历节点；坏数据退化为空数组（不抛错） */
    private Iterable<JsonNode> arrayOf(String json) {
        JsonNode node = readTree(json);
        return node.isArray() ? node : om.createArrayNode();
    }

    private static String joinText(List<String> list) {
        return list == null || list.isEmpty() ? "无" : String.join("、", list);
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
