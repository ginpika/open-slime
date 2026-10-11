package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.heartbeat.HeartbeatService;
import cc.ginpika.minis.agent.heartbeat.dto.HeartbeatDtos;
import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.ai.core.tool.ToolManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 系统级接口（全局、跨 Agent）：工具词典 + 全局观测台（所有 Agent 的心跳状态与最近执行）。
 *
 * <p>「后台」已逐 Agent 化：逐 Agent 的读取 / 操作在 {@link AgentController} 的 {@code /api/agents/{agentId}/*}；
 * 这里只放<b>全局</b>两样——工具字典（与 Agent 无关）与全局观测台（跨全部 Agent 聚合）。
 * 旧 {@code /api/heartbeat}（含 {@code /api/subconscious} 一类）路由已全部移除。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    /** SSE 流最长保持：与 nginx 的 proxy_read_timeout（300s）对齐 */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    /** 全局观测台快照间隔 */
    private static final long PULSE_MS = 2_000L;

    /** 保活注释帧间隔（无变化时） */
    private static final long PING_MS = 15_000L;

    /** 快照里带回的批次数 */
    private static final int PULSE_RUNS = 8;

    /** 快照里每批带回的执行条数 */
    private static final int PULSE_EXECS = 30;

    private final HeartbeatService service;
    private final PlanStore plans;
    private final ExecStore execs;
    private final ToolManager tools;
    private final AgentDirectory directory;
    private final ObjectMapper om;

    /** 每个观测台连接一个虚拟线程：只做「读快照 → 推 SSE」，不占请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SystemController(HeartbeatService service, PlanStore plans, ExecStore execs,
                            ToolManager tools, AgentDirectory directory, ObjectMapper om) {
        this.service = service;
        this.plans = plans;
        this.execs = execs;
        this.tools = tools;
        this.directory = directory;
        this.om = om;
    }

    /**
     * 工具词典：全部工具各自是什么、属于哪些工具面、参数怎么给。
     * 只读、不参与调度，故独立成一条接口（全局一份，与 Agent 无关）。
     */
    @GetMapping("/tools")
    public List<HeartbeatDtos.ToolInfoView> tools() {
        return tools.allTools().stream()
                .map(t -> new HeartbeatDtos.ToolInfoView(t.name(), t.description(),
                        t.surfaces().stream().map(Enum::name).sorted().toList(),
                        t.writes(), t.agentsMayCall(), t.parallelizable(), t.parameters(om)))
                .toList();
    }

    /** 全局观测台快照：全部「已启用」心跳的逐 Agent 状态 + 所有 Agent 的最近几批执行 */
    @GetMapping("/dashboard/pulse")
    public HeartbeatDtos.PulseView pulse() {
        return pulseView();
    }

    /** 全局观测台实时流：变化时推 pulse（整份快照），无变化时发注释帧保活 */
    @GetMapping(value = "/dashboard/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            String last = null;
            long lastPing = System.currentTimeMillis();
            try {
                for (;;) {
                    HeartbeatDtos.PulseView pulse = pulseView();
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

    // ---------- 快照 ----------

    private HeartbeatDtos.PulseView pulseView() {
        List<HeartbeatDtos.HeartbeatState> states = plans.enabledHeartbeats().stream()
                .map(hb -> toState(hb))
                .toList();
        List<HeartbeatDtos.RunBundle> recent = execs.runs(null, PULSE_RUNS).stream()
                .map(r -> new HeartbeatDtos.RunBundle(toRun(r), execsOfRun(r.id())))
                .toList();
        return new HeartbeatDtos.PulseView(states, recent);
    }

    /** 某批的执行（超过 PULSE_EXECS 时只留最近的若干条） */
    private List<HeartbeatDtos.ExecView> execsOfRun(String runId) {
        List<ExecStore.ExecRow> rows = execs.execsByRun(runId);
        if (rows.size() > PULSE_EXECS) {
            rows = rows.subList(rows.size() - PULSE_EXECS, rows.size());
        }
        return rows.stream().map(r -> toExec(r, false)).toList();
    }

    private HeartbeatDtos.HeartbeatState toState(PlanStore.HeartbeatRow hb) {
        return new HeartbeatDtos.HeartbeatState(hb.agentId(), directory.nameOf(hb.agentId()),
                hb.enabled(), hb.tickMs(), hb.lastBeatAt(), service.running(hb.agentId()));
    }

    /** 快照指纹：只有内容变了才推 pulse（AgentController 的逐 Agent 流复用本方法） */
    static String pulseKey(HeartbeatDtos.PulseView pulse) {
        StringBuilder sb = new StringBuilder();
        for (HeartbeatDtos.HeartbeatState s : pulse.heartbeats()) {
            sb.append(s.agentId()).append(s.enabled()).append(s.running()).append(':');
        }
        sb.append('|');
        for (HeartbeatDtos.RunBundle bundle : pulse.recentRuns()) {
            HeartbeatDtos.RunView run = bundle.run();
            sb.append(run.id()).append(run.status()).append(run.agentCount()).append(':');
            for (HeartbeatDtos.ExecView e : bundle.execs()) {
                sb.append(e.id()).append(':').append(e.status()).append(',');
            }
            sb.append('|');
        }
        return sb.toString();
    }

    // ---------- 映射（与 AgentController 共用同一形状） ----------

    static HeartbeatDtos.RunView toRun(ExecStore.RunRow r) {
        return new HeartbeatDtos.RunView(r.id(), r.agentId(), r.beat(), r.trigger(), r.status(),
                r.agentCount(), r.note(), r.startedAt(), r.finishedAt());
    }

    private HeartbeatDtos.ExecView toExec(ExecStore.ExecRow r, boolean withContext) {
        return new HeartbeatDtos.ExecView(r.id(), r.runId(), r.kind(), r.planId(), r.title(),
                r.status(), withContext ? r.context() : null, r.output(), r.reasoning(),
                traces(r.trace()), List.of(), r.error(), r.startedAt(), r.finishedAt(), null);
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

    private void send(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(om.writeValueAsString(payload)));
        } catch (Exception e) {
            throw new ClientGone();
        }
    }

    /** 客户端断开导致写流失败：常态，静默收尾 */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("观测台流客户端已断开，写失败按断开收尾：{}", e.getMessage());
    }

    /** 客户端已断开：结束该观测台线程 */
    private static final class ClientGone extends RuntimeException {
    }
}
