package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.subconscious.SubconsciousInjectors;
import cc.ginpika.minis.agent.subconscious.SubconsciousPrompts;
import cc.ginpika.minis.agent.subconscious.SubconsciousService;
import cc.ginpika.minis.agent.subconscious.dto.SubconsciousDtos;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.subconscious.store.PlanStore;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.knowledge.recall.RagRecallStore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 潜意识接口：观测台（心跳开关与节拍、实时流）+ 任务计划的读取 + 执行留痕 + 资源使用视图。
 *
 * <p><b>没有增删改编制的接口</b>：任务计划（{@code sub_plan}）是大模型自己立的编制，
 * 只有它能立（{@code create_plan}）、改（{@code update_plan}）、撤（{@code delete_plan}）；
 * 子代理（{@code sub_exec.kind=agent}）更不建档案，当场交代即跑。这些都在对话里发生，不是页面上的表单。
 *
 * <p>页面只读加一个人工触发：
 * <ul>
 *   <li>{@code /plans} 名册——手上常驻的编制</li>
 *   <li>{@code /agents} 子代理调用记录——那些匿名 worker 最近干了什么</li>
 *   <li>{@code /resources} 资源使用——窗口内的执行量、耗时、工具往返与 token 估算</li>
 *   <li>{@code /injectors} 插槽词典、{@code /tools} 工具词典——两份只读说明文档</li>
 *   <li>{@code /plans/{id}/run} 立即执行一次（当场验证一份编制）</li>
 * </ul>
 *
 * <p>观测台用 SSE 推送「最近几批 + 各自的执行」的快照：没有变化时只发注释帧保活，
 * 有变化才推 pulse，前端按 id 覆盖即可。
 */
@RestController
@RequestMapping("/api/subconscious")
public class SubconsciousController {

    private static final Logger log = LoggerFactory.getLogger(SubconsciousController.class);

    /** SSE 流最长保持：与 nginx 的 proxy_read_timeout（300s）对齐 */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    /** 观测台快照间隔 */
    private static final long PULSE_MS = 2_000L;

    /** 保活注释帧间隔（无变化时） */
    private static final long PING_MS = 15_000L;

    /** 快照里带回的批次数 */
    private static final int PULSE_RUNS = 5;

    /** 快照里每批带回的执行条数 */
    private static final int PULSE_EXECS = 30;

    /** 资源视图窗口上限（一年） */
    private static final int RESOURCE_HOURS_MAX = 24 * 365;

    /** 字符量 → 估算 token 的除数：CJK 约 1 字 1 token、其余约 4 字符 1 token，取中间值作量级参考 */
    private static final int CHARS_PER_TOKEN = 3;

    private final SubconsciousService service;
    private final PlanStore plans;
    private final ExecStore execs;
    private final SubconsciousInjectors injectors;
    private final ToolManager tools;
    private final ObjectMapper om;
    /** RAG 召回留痕（三路召回工具调用的一次记录）：只读 + 清空，供「RAG 召回」子页回看效果 */
    private final RagRecallStore ragRecalls;
    /** 每个观测台连接一个虚拟线程：只做「读快照 → 推 SSE」，不占请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SubconsciousController(SubconsciousService service, PlanStore plans, ExecStore execs,
                                  SubconsciousInjectors injectors, ToolManager tools, ObjectMapper om,
                                  RagRecallStore ragRecalls) {
        this.service = service;
        this.plans = plans;
        this.execs = execs;
        this.injectors = injectors;
        this.tools = tools;
        this.om = om;
        this.ragRecalls = ragRecalls;
    }

    // ---------- 观测台 ----------

    @GetMapping("/state")
    public SubconsciousDtos.StateView state() {
        return stateView();
    }

    @GetMapping("/pulse")
    public SubconsciousDtos.PulseView pulse() {
        return pulseView();
    }

    /**
     * 插槽词典：全部可用注入器各自取什么、参数怎么给。
     * 只读、不参与心跳，故独立成一条接口，不塞进每两秒一推的 pulse。
     */
    @GetMapping("/injectors")
    public SubconsciousDtos.InjectorGuideView injectorGuide() {
        return new SubconsciousDtos.InjectorGuideView(
                injectors.infos().stream()
                        .map(i -> new SubconsciousDtos.InjectorInfoView(i.name(), i.summary(),
                                i.args(), i.source()))
                        .toList());
    }

    /**
     * 工具词典：全部工具各自是什么、属于哪些工具面、参数怎么给。
     * 只读、不参与心跳，故独立成一条接口，进「工具」子页时拉一次。
     */
    @GetMapping("/tools")
    public List<SubconsciousDtos.ToolInfoView> tools() {
        return tools.allTools().stream()
                .map(t -> new SubconsciousDtos.ToolInfoView(t.name(), t.description(),
                        t.surfaces().stream().map(Enum::name).sorted().toList(),
                        t.writes(), t.agentsMayCall(), t.parallelizable(), t.parameters(om)))
                .toList();
    }

    /** 心跳开关 / 节拍（运行时生效，不改配置文件） */
    @PutMapping("/heartbeat")
    public SubconsciousDtos.StateView heartbeat(@RequestBody SubconsciousDtos.HeartbeatRequest req) {
        if (req != null && req.enabled() != null) {
            service.setHeartbeatEnabled(req.enabled());
        }
        if (req != null && req.tickMs() != null && req.tickMs() > 0) {
            service.setTickMs(req.tickMs());
        }
        return stateView();
    }

    /** 手动敲一次心跳：让心跳主会话立刻出场，由它决定这一拍派谁 */
    @PostMapping("/beat")
    public SubconsciousDtos.BeatView beat() {
        return toBeat(service.beat("manual"));
    }

    /** 人工把某份编制单跑一次（页面上的「立即执行」）：绕过主会话，独占一批 */
    @PostMapping("/plans/{id}/run")
    public SubconsciousDtos.BeatView runPlan(@PathVariable String id) {
        return toBeat(service.runPlan(id));
    }

    /** 观测台实时流：变化时推 pulse（整份快照），无变化时发注释帧保活 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            String last = null;
            long lastPing = System.currentTimeMillis();
            try {
                for (;;) {
                    SubconsciousDtos.PulseView pulse = pulseView();
                    String key = pulseKey(pulse);
                    long now = System.currentTimeMillis();
                    if (!key.equals(last)) {
                        last = key;
                        lastPing = now;
                        send(emitter, "pulse", pulse);
                    } else if (now - lastPing >= PING_MS) {
                        lastPing = now;
                        emitter.send(SseEmitter.event().comment("ping"));
                    }
                    Thread.sleep(PULSE_MS);
                }
            } catch (ClientGone e) {
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (Exception e) {
                emitter.complete();
            }
        });
        return emitter;
    }

    // ---------- 批次与执行留痕 ----------

    @GetMapping("/runs")
    public List<SubconsciousDtos.RunView> runs(@RequestParam(defaultValue = "30") int limit) {
        return execs.runs(clampLimit(limit)).stream().map(SubconsciousController::toRun).toList();
    }

    /** 某批里的各次执行（观测台点开一批时展开） */
    @GetMapping("/runs/{id}/execs")
    public List<SubconsciousDtos.ExecView> runExecs(@PathVariable String id) {
        return execs.execsByRun(id).stream().map(r -> toExec(r, false)).toList();
    }

    /** 某一次执行的完整过程：组装出的上下文、思维链、过程分段、写库清单 */
    @GetMapping("/execs/{id}")
    public SubconsciousDtos.ExecView exec(@PathVariable long id) {
        ExecStore.ExecRow row = execs.exec(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "执行记录不存在: " + id));
        return toExec(row, true);
    }

    // ---------- 任务计划（只读） ----------

    /** 名册：全部编制的完整定义（页面只读；立改撤都在大模型的工具里） */
    @GetMapping("/plans")
    public List<SubconsciousDtos.PlanView> plans() {
        return plans.plans().stream().map(this::toPlan).toList();
    }

    /** 某份编制最近若干次被派出的记录 */
    @GetMapping("/plans/{id}/execs")
    public List<SubconsciousDtos.ExecView> planExecs(@PathVariable String id,
                                                     @RequestParam(defaultValue = "20") int limit) {
        return execs.execsByPlan(id, clampLimit(limit)).stream()
                .map(r -> toExec(r, false))
                .toList();
    }

    // ---------- 子代理（匿名 worker 的调用记录） ----------

    /** 最近若干次子代理调用：它们不建档案，所以这里只有「跑过的记录」，没有名册 */
    @GetMapping("/agents")
    public List<SubconsciousDtos.ExecView> agents(@RequestParam(defaultValue = "30") int limit) {
        return execs.latestExecs(ExecStore.KIND_AGENT, clampLimit(limit)).stream()
                .map(r -> toExec(r, false))
                .toList();
    }

    // ---------- 资源使用 ----------

    /**
     * 大模型后台的资源使用：名册规模 + 心跳状态 + 窗口内的执行总量（分全部 / 子代理 / 计划 / 心跳四档）
     * + 逐份编制的用量。全部由留痕表聚合而来，不新增任何存储。
     *
     * @param hours 统计窗口（小时，默认 24，上限一年）
     */
    @GetMapping("/resources")
    public SubconsciousDtos.ResourceView resources(@RequestParam(defaultValue = "24") int hours) {
        int h = Math.max(1, Math.min(hours, RESOURCE_HOURS_MAX));
        long since = System.currentTimeMillis() - h * 3_600_000L;
        List<PlanStore.PlanRow> roster = plans.plans();
        return new SubconsciousDtos.ResourceView(service.heartbeatEnabled(), service.tickMs(),
                service.running(), h, roster.size(),
                toTotals(execs.usage(since, null)),
                toTotals(execs.usage(since, ExecStore.KIND_AGENT)),
                toTotals(execs.usage(since, ExecStore.KIND_PLAN)),
                toTotals(execs.usage(since, ExecStore.KIND_HEARTBEAT)),
                planUsage(since, roster));
    }

    /** 逐份编制的用量：聚合结果按 planId 对上名册，名册里没跑过的补一条空用量 */
    private List<SubconsciousDtos.PlanUsage> planUsage(long since, List<PlanStore.PlanRow> roster) {
        List<SubconsciousDtos.PlanUsage> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ExecStore.PlanUsageRow r : execs.planUsage(since)) {
            seen.add(r.planId());
            out.add(new SubconsciousDtos.PlanUsage(r.planId(), planName(roster, r.planId()),
                    r.runs(), r.done(), r.failed(), r.avgMs(), r.maxMs(), r.toolRounds(),
                    r.chars() / CHARS_PER_TOKEN, r.lastAt()));
        }
        for (PlanStore.PlanRow p : roster) {
            if (!seen.contains(p.id())) {
                out.add(new SubconsciousDtos.PlanUsage(p.id(), p.name(), 0, 0, 0, 0, 0, 0, 0, null));
            }
        }
        return out;
    }

    /** planId → 展示名；计划已被撤掉时留空（历史留痕仍在，不应凭空消失） */
    private static String planName(List<PlanStore.PlanRow> roster, String planId) {
        return roster.stream().filter(p -> p.id().equals(planId)).findFirst()
                .map(PlanStore.PlanRow::name).orElse(null);
    }

    // ---------- RAG 召回留痕 ----------

    /**
     * 最近若干条三路召回留痕（倒序）：每次「向量 / 图 / 时序事件」召回工具调用一行，
     * 原样保留查询与回给模型的 tool 消息原文，供「RAG 召回」子页回看 RAG 效果。
     *
     * @param limit   条数上限（默认 100，夹取到 [1,200]）
     * @param channel 只看某一路（vector / graph / memory）；缺省全部
     */
    @GetMapping("/rag-recalls")
    public List<SubconsciousDtos.RagRecallView> ragRecalls(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String channel) {
        return ragRecalls.recent(clampLimit(limit), blank(channel) ? null : channel.trim()).stream()
                .map(SubconsciousController::toRagRecall)
                .toList();
    }

    /** 清空留痕（页面上的「清空」按钮）：方便在调整检索配置后重新观察一批干净样本 */
    @DeleteMapping("/rag-recalls")
    public SubconsciousDtos.RagRecallCleared clearRagRecalls() {
        return new SubconsciousDtos.RagRecallCleared(ragRecalls.clear());
    }

    // ---------- 心跳主会话（总线）的上下文 ----------

    /** 心跳主会话的上下文：库里还没落过这一行时给出内置正文（与执行时用的那份一致） */
    @GetMapping("/heartbeat/context")
    public SubconsciousDtos.HeartbeatView heartbeatContext() {
        return toHeartbeat(plans.heartbeat().orElseGet(() ->
                new PlanStore.HeartbeatRow(SubconsciousPrompts.HEARTBEAT,
                        SubconsciousPrompts.heartbeatInjectors(), null)));
    }

    /** 保存心跳主会话的上下文（只有这一行，整体覆盖写） */
    @PutMapping("/heartbeat/context")
    public SubconsciousDtos.HeartbeatView saveHeartbeatContext(
            @RequestBody SubconsciousDtos.HeartbeatContextRequest req) {
        require(blank(req.content()), "content 不能为空");
        plans.saveHeartbeat(new PlanStore.HeartbeatRow(req.content(),
                writeBindings(req.injectors()), blank(req.modelId()) ? null : req.modelId().trim()));
        return toHeartbeat(plans.heartbeat().orElseThrow());
    }

    // ---------- 快照与映射 ----------

    private SubconsciousDtos.StateView stateView() {
        return new SubconsciousDtos.StateView(service.heartbeatEnabled(), service.tickMs(),
                service.running(), SubconsciousService.CHANNELS, injectors.names());
    }

    private SubconsciousDtos.PulseView pulseView() {
        List<SubconsciousDtos.RunBundle> recent = execs.runs(PULSE_RUNS).stream()
                .map(r -> new SubconsciousDtos.RunBundle(toRun(r), execsOfRun(r.id())))
                .toList();
        return new SubconsciousDtos.PulseView(stateView(), recent);
    }

    /** 某批的执行（超过 PULSE_EXECS 时只留最近的若干条） */
    private List<SubconsciousDtos.ExecView> execsOfRun(String runId) {
        List<ExecStore.ExecRow> rows = execs.execsByRun(runId);
        if (rows.size() > PULSE_EXECS) {
            rows = rows.subList(rows.size() - PULSE_EXECS, rows.size());
        }
        return rows.stream().map(r -> toExec(r, false)).toList();
    }

    /** 快照指纹：只有内容变了才推 pulse */
    private static String pulseKey(SubconsciousDtos.PulseView pulse) {
        StringBuilder sb = new StringBuilder()
                .append(pulse.state().running()).append('|')
                .append(pulse.state().heartbeatEnabled()).append('|')
                .append(pulse.state().tickMs()).append('|');
        for (SubconsciousDtos.RunBundle bundle : pulse.recentRuns()) {
            SubconsciousDtos.RunView run = bundle.run();
            sb.append(run.id()).append(run.status()).append(run.agentCount()).append(':');
            for (SubconsciousDtos.ExecView e : bundle.execs()) {
                sb.append(e.id()).append(':').append(e.status()).append(',');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    private SubconsciousDtos.BeatView toBeat(SubconsciousService.BeatSummary summary) {
        if (summary == null) {
            return new SubconsciousDtos.BeatView(false, null, 0, null, 0);
        }
        return new SubconsciousDtos.BeatView(true, summary.runId(), summary.beat(), summary.trigger(),
                summary.agents());
    }

    private SubconsciousDtos.HeartbeatView toHeartbeat(PlanStore.HeartbeatRow r) {
        return new SubconsciousDtos.HeartbeatView(r.content(), bindings(r.injectors()), r.modelId());
    }

    private SubconsciousDtos.PlanView toPlan(PlanStore.PlanRow r) {
        return new SubconsciousDtos.PlanView(r.id(), r.name(), r.role(), r.content(),
                names(r.tools()), bindings(r.injectors()), names(r.channels()),
                r.intervalMs(), r.input(), r.modelId(), r.sort(), r.lastBeatAt());
    }

    private static SubconsciousDtos.RunView toRun(ExecStore.RunRow r) {
        return new SubconsciousDtos.RunView(r.id(), r.beat(), r.trigger(), r.status(),
                r.agentCount(), r.note(), r.startedAt(), r.finishedAt());
    }

    /** 执行视图；withContext=false 时省掉 context（messages JSON 很大，列表不需要） */
    private SubconsciousDtos.ExecView toExec(ExecStore.ExecRow r, boolean withContext) {
        List<SubconsciousDtos.EditView> edits = withContext
                ? execs.editsByExec(r.id()).stream().map(SubconsciousController::toEdit).toList()
                : List.of();
        return new SubconsciousDtos.ExecView(r.id(), r.runId(), r.kind(), r.planId(), r.title(),
                r.status(), withContext ? r.context() : null, r.output(), r.reasoning(),
                traces(r.trace()), edits, r.error(), r.startedAt(), r.finishedAt());
    }

    private static SubconsciousDtos.EditView toEdit(ExecStore.EditRow r) {
        return new SubconsciousDtos.EditView(r.id(), r.tool(), r.args(), r.summary(), r.createdAt());
    }

    private static SubconsciousDtos.RagRecallView toRagRecall(RagRecallStore.RecallRow r) {
        return new SubconsciousDtos.RagRecallView(r.id(), r.channel(), r.tool(), r.source(),
                r.query(), r.result(), r.resultChars(), r.createdAt());
    }

    /** 用量行 → 视图：字符量按 {@link #CHARS_PER_TOKEN} 折成 token 估算（只作量级参考） */
    private static SubconsciousDtos.UsageTotals toTotals(ExecStore.UsageRow r) {
        return new SubconsciousDtos.UsageTotals(r.runs(), r.done(), r.failed(), r.avgMs(),
                r.maxMs(), r.toolRounds(), r.chars() / CHARS_PER_TOKEN);
    }

    // ---------- 序列化助手 ----------

    /** JSON 数组 → 插槽绑定列表；空/坏数据回退空列表 */
    private List<SubconsciousDtos.InjectorBinding> bindings(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<SubconsciousDtos.InjectorBinding>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** JSON 字符串数组 → 清单；空/坏数据回退空列表 */
    private List<String> names(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ChatDtos.TraceSegment> traces(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<ChatDtos.TraceSegment>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 空列表落 null，让「未声明」与「声明为空」在库里是同一种状态 */
    private String writeBindings(List<SubconsciousDtos.InjectorBinding> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(list);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数序列化失败: " + e.getMessage());
        }
    }

    private static void require(boolean invalid, String message) {
        if (invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(om.writeValueAsString(payload)));
        } catch (Exception e) {
            throw new ClientGone();
        }
    }

    /**
     * 向已断开的客户端写流而失败：这不是业务错误，收下即可。
     *
     * <p>Spring 会把流写失败当成异步请求错误再派发回来走异常解析，若无人处理，tomcat 便以
     * ERROR 记一整条堆栈（Windows 上是「你的主机中的软件中止了一个已建立的连接」）。
     * 观测台的流由前端循环重连，用户切页、关标签时都会发生，属常态，故按断开静默收尾。
     */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("观测台流客户端已断开，写失败按断开收尾：{}", e.getMessage());
    }

    /** 客户端已断开：结束该观测台线程 */
    private static final class ClientGone extends RuntimeException {
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
