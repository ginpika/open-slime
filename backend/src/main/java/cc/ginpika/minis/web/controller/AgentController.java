package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.roundtable.RoundtableAgenda;
import cc.ginpika.minis.agent.roundtable.RoundtableChatService;
import cc.ginpika.minis.agent.roundtable.RoundtableDiscussionRuntime;
import cc.ginpika.minis.agent.roundtable.RoundtableLiveBus;
import cc.ginpika.minis.agent.roundtable.RoundtableTodoStore;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicService;
import cc.ginpika.minis.agent.roundtable.RoundtableWorkRuntime;
import cc.ginpika.minis.agent.roundtable.RoundtableWorkflowService;
import cc.ginpika.minis.agent.member.AgentAnchorService;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.agent.heartbeat.HeartbeatService;
import cc.ginpika.minis.agent.heartbeat.dto.HeartbeatDtos;
import cc.ginpika.minis.agent.heartbeat.store.PlanStore;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.subagent.SubagentService;
import cc.ginpika.minis.ai.knowledge.recall.RagRecallStore;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;
import cc.ginpika.minis.web.sso.SsoInterceptor;
import cc.ginpika.minis.web.sso.SsoUser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 多智能体接口：名册（配置面）+ 圆桌会话（对话面）+ 话题与事件（状态机面）。
 *
 * <p>与首页对话接口（{@code /api/chat*}）刻意分开：多智能体有自己的会话与消息，
 * 页面上也不复用对话页，所以路径、事件名、返回结构都自成一套，互不影响。
 *
 * <p>流式事件（{@code POST /api/agents/chat/stream}）按发生顺序推：
 * {@code topic}（话题确定）→ 每位成员一组 {@code speaker} / {@code reasoning} · {@code content} ·
 * {@code tool} / {@code speakerEnd} → 收束时 {@code topicEnd} → {@code done}（本轮摘要）；
 * 出错推 {@code error}。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** SSE 流最长保持：与 nginx 的 proxy_read_timeout（300s）对齐 */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    /** 后台观测台快照间隔 / 保活间隔 / 快照批次数 / 每批执行条数 */
    private static final long PULSE_MS = 2_000L;
    private static final long PING_MS = 15_000L;
    private static final int PULSE_RUNS = 8;
    private static final int PULSE_EXECS = 30;

    /** 圆桌流的事件名：这一轮的收尾（成功）与出错——订阅者据此关掉连接 */
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /** 资源视图窗口上限（一年） */
    private static final int RESOURCE_HOURS_MAX = 24 * 365;

    /** 字符量 → 估算 token 的除数：CJK 约 1 字 1 token、其余约 4 字符 1 token，取中间值作量级参考 */
    private static final int CHARS_PER_TOKEN = 3;

    private final AgentDirectory peers;
    private final RoundtableChatService chat;
    private final RoundtableTopicService topics;
    private final AgentPromptService peerPrompts;
    private final AgentAnchorService peerAnchors;
    private final ObjectMapper om;
    /** 逐 Agent 后台：心跳调度（开关 / 节拍 / 手动敲 / 单跑职责）与逐 Agent 观测台 */
    private final HeartbeatService heartbeat;
    private final PlanStore plans;
    private final ExecStore execs;
    /** 任务：子代理列表据此把「重跑」接到任务上 */
    private final TaskStore tasks;
    /** 子代理模块：重跑一条被中断的子代理任务由它重新派活 */
    private final SubagentService subagent;
    /** RAG 召回留痕：各 Agent 的「RAG 召回」子页（只读 + 清空） */
    private final RagRecallStore ragRecalls;
    /** 一轮圆桌发言在独立线程里跑（虚拟线程）：边收 LLM 边推前端，不占请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    /** 圆桌的讨论运行时（事件驱动协调器）：USER_MESSAGE / 取消 / 场外报告的事件入口 */
    private final RoundtableDiscussionRuntime runtime;
    /** 会话级运行时广播：一次推进的过程事件推给当前订阅者（见 {@link RoundtableLiveBus}） */
    private final RoundtableLiveBus liveBus;
    /** 执行态聚合：页面「执行态」图的数据源（讨论节点 + 成员二维状态 + 共享任务区） */
    private final RoundtableWorkflowService workflowFlow;
    /** 执行态运行时：人从卡片上干预任务板（放弃 / 跳过 / 立刻检查）时，由它改状态并重新调度 */
    private final RoundtableWorkRuntime workRuntime;
    /** 共享任务区：干预之后读回那一条（状态 + 优先级）给页面 */
    private final RoundtableTodoStore taskBoard;
    private final cc.ginpika.minis.agent.roundtable.CollaborationTaskLifecycle lifecycle;
    private cc.ginpika.minis.coding.CodingFacade coding;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCoding(@org.springframework.context.annotation.Lazy cc.ginpika.minis.coding.CodingFacade coding){this.coding=coding;}

    public AgentController(AgentDirectory peers, RoundtableChatService chat, RoundtableTopicService topics,
                                AgentPromptService peerPrompts, AgentAnchorService peerAnchors,
                                ObjectMapper om,
                                HeartbeatService heartbeat, PlanStore plans, ExecStore execs,
                                TaskStore tasks, SubagentService subagent, RagRecallStore ragRecalls,
                                RoundtableDiscussionRuntime runtime, RoundtableLiveBus liveBus,
                                RoundtableWorkflowService workflowFlow,
                                RoundtableWorkRuntime workRuntime, RoundtableTodoStore taskBoard,
                                cc.ginpika.minis.agent.roundtable.CollaborationTaskLifecycle lifecycle) {
        this.peers = peers;
        this.chat = chat;
        this.topics = topics;
        this.peerPrompts = peerPrompts;
        this.peerAnchors = peerAnchors;
        this.om = om;
        this.heartbeat = heartbeat;
        this.plans = plans;
        this.execs = execs;
        this.tasks = tasks;
        this.subagent = subagent;
        this.ragRecalls = ragRecalls;
        this.runtime = runtime;
        this.liveBus = liveBus;
        this.workflowFlow = workflowFlow;
        this.workRuntime = workRuntime;
        this.taskBoard = taskBoard;
        this.lifecycle=lifecycle;
    }

    // ---------- 名册（配置面） ----------

    @GetMapping("/peers")
    public List<AgentDtos.AgentView> peers() {
        return peers.peers().stream().map(this::toPeer).toList();
    }

    @PostMapping("/peers")
    public AgentDtos.AgentView createPeer(@RequestBody AgentDtos.AgentRequest req) {
        AgentRow row = peers.create(req == null ? null : req.name(),
                req == null ? null : req.terminals(), req == null ? null : req.modelId(),
                req == null ? null : req.note(), req == null ? null : req.avatar(),
                req == null ? null : req.role(), req == null ? null : req.roleColor());
        return toPeer(row);
    }

    @PutMapping("/peers/{id}")
    public AgentDtos.AgentView updatePeer(@PathVariable String id,
                                              @RequestBody AgentDtos.AgentRequest req) {
        AgentRow row = peers.update(id, req == null ? null : req.name(),
                req == null ? null : req.terminals(), req == null ? null : req.modelId(),
                req == null ? null : req.sort(), req == null ? null : req.note(),
                req == null ? null : req.avatar(),
                req == null ? null : req.role(), req == null ? null : req.roleColor());
        return toPeer(row);
    }

    @DeleteMapping("/peers/{id}")
    public AgentDtos.AgentDeleted deletePeer(@PathVariable String id) {
        peers.delete(id);
        return new AgentDtos.AgentDeleted(id);
    }

    // ---------- 成员提示词（每个智能体独立一套「对话」提示词） ----------

    /**
     * 这位成员自己那套「对话」提示词的全量视图：结构与提示词页一致（大类 → 上下文 → 提示词位），
     * 只是大类只有「对话」，且版本 / 生效项 / 顺序都是这位成员自己的。
     */
    @GetMapping("/peers/{id}/prompts")
    public PromptDtos.PromptsView peerPrompts(@PathVariable String id) {
        return peerPrompts.view(id);
    }

    /** 为这位成员的某提示词位新增一份版本 */
    @PostMapping("/peers/{id}/prompts/slots/{slot}/versions")
    public PromptDtos.PromptsView createPeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String slot,
                                                          @RequestBody PromptDtos.VersionRequest req) {
        peerPrompts.createVersion(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 改这位成员某份版本的正文 / 名字 */
    @PutMapping("/peers/{id}/prompts/versions/{vid}")
    public PromptDtos.PromptsView updatePeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String vid,
                                                          @RequestBody PromptDtos.VersionRequest req) {
        peerPrompts.updateVersion(id, vid, req);
        return peerPrompts.view(id);
    }

    @DeleteMapping("/peers/{id}/prompts/versions/{vid}")
    public PromptDtos.PromptsView deletePeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String vid) {
        peerPrompts.deleteVersion(id, vid);
        return peerPrompts.view(id);
    }

    /** 选定这位成员某提示词位生效的版本；versionId 为空或 builtin = 回退全局 */
    @PutMapping("/peers/{id}/prompts/slots/{slot}/binding")
    public PromptDtos.PromptsView bindPeerPromptSlot(@PathVariable String id,
                                                     @PathVariable String slot,
                                                     @RequestBody PromptDtos.BindRequest req) {
        peerPrompts.bind(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 保存这位成员某提示词位当前生效的正文：已绑定版本则改它，否则自动分叉一份专属版本 */
    @PutMapping("/peers/{id}/prompts/slots/{slot}/content")
    public PromptDtos.PromptsView savePeerPromptSlotContent(@PathVariable String id,
                                                            @PathVariable String slot,
                                                            @RequestBody PromptDtos.ContentRequest req) {
        peerPrompts.saveContent(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 覆盖这位成员某上下文内提示词位的拼装顺序（拖拽调整位置） */
    @PutMapping("/peers/{id}/prompts/contexts/{context}/order")
    public PromptDtos.PromptsView savePeerPromptContextOrder(@PathVariable String id,
                                                             @PathVariable String context,
                                                             @RequestBody PromptDtos.OrderRequest req) {
        peerPrompts.saveOrder(id, context, req);
        return peerPrompts.view(id);
    }

    // ---------- 图谱人格锚点（成员与知识图谱锚点实体的绑定） ----------

    /** 这位成员的图谱人格锚点视图：锚点实体 + 它的一跳子图；未绑定时 anchor=null */
    @GetMapping("/peers/{id}/anchor")
    public AgentDtos.AnchorView peerAnchor(@PathVariable String id) {
        return peerAnchors.view(id);
    }

    /** 新建并绑定：以成员名落一个锚点实体（types 含 ANCHOR）；已绑定时幂等 */
    @PostMapping("/peers/{id}/anchor")
    public AgentDtos.AnchorView createPeerAnchor(@PathVariable String id) {
        return peerAnchors.create(id);
    }

    /** 解除绑定：只断开成员与锚点的关联，锚点实体与其关系保留在图中 */
    @DeleteMapping("/peers/{id}/anchor")
    public AgentDtos.AnchorView deletePeerAnchor(@PathVariable String id) {
        return peerAnchors.unbind(id);
    }

    // ---------- 逐 Agent 的后台（心跳 / 职责 / 执行留痕 / 子代理 / RAG 召回 / 资源） ----------
    //
    // 「后台」已逐 Agent 化：每个 Agent 各有一套心跳与职责名册，本组接口全部以 /{agentId} 定位。
    // 全局的东西（工具词典、全局观测台）在 SystemController 的 /api/system 下。

    /** 某 Agent 的心跳：库里没落过时给出内置正文与默认节拍 */
    @GetMapping("/{agentId}/heartbeat")
    public HeartbeatDtos.HeartbeatView heartbeat(@PathVariable String agentId) {
        requirePeer(agentId);
        return toHeartbeat(heartbeat.heartbeat(agentId));
    }

    /** 保存某 Agent 的心跳（正文 / 模型 / 开关 / 节拍，各字段给 null 表示不改） */
    @PutMapping("/{agentId}/heartbeat")
    public HeartbeatDtos.HeartbeatView saveHeartbeat(@PathVariable String agentId,
                                                     @RequestBody HeartbeatDtos.HeartbeatRequest req) {
        requirePeer(agentId);
        heartbeat.saveHeartbeat(agentId, req == null ? null : req.content(),
                req == null ? null : req.modelId(),
                req == null ? null : req.enabled(), req == null ? null : req.tickMs());
        return toHeartbeat(heartbeat.heartbeat(agentId));
    }

    /** 手动敲一次：让该 Agent 的心跳主会话立刻出场，由它决定这一拍派谁 */
    @PostMapping("/{agentId}/heartbeat/beat")
    public HeartbeatDtos.BeatView beat(@PathVariable String agentId) {
        requirePeer(agentId);
        return toBeat(heartbeat.beat(agentId, "manual"));
    }

    /** 名册：该 Agent 全部职责的完整定义（页面只读；立改撤都在大模型的工具里） */
    @GetMapping("/{agentId}/plans")
    public List<HeartbeatDtos.PlanView> plans(@PathVariable String agentId) {
        requirePeer(agentId);
        return plans.plans(agentId).stream().map(this::toPlan).toList();
    }

    /** 启用 / 停用一份职责——本页唯一的写入口（停用只挡自动派出，不删定义） */
    @PutMapping("/{agentId}/plans/{id}/enabled")
    public HeartbeatDtos.PlanView setPlanEnabled(@PathVariable String agentId,
                                                 @PathVariable String id,
                                                 @RequestBody HeartbeatDtos.PlanEnabledRequest req) {
        requirePeer(agentId);
        if (req == null || req.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 不能为空");
        }
        PlanStore.PlanRow plan = requirePlan(agentId, id);
        plans.setPlanEnabled(plan.id(), req.enabled());
        return toPlan(plans.planById(id).orElse(plan));
    }

    /** 人工把某份职责单跑一次（页面上的「立即执行」）：绕过主会话，独占一批 */
    @PostMapping("/{agentId}/plans/{id}/run")
    public HeartbeatDtos.BeatView runPlan(@PathVariable String agentId, @PathVariable String id) {
        requirePeer(agentId);
        requirePlan(agentId, id);
        return toBeat(heartbeat.runPlan(agentId, id));
    }

    /** 某份职责最近若干次被派出的记录 */
    @GetMapping("/{agentId}/plans/{id}/execs")
    public List<HeartbeatDtos.ExecView> planExecs(@PathVariable String agentId,
                                                  @PathVariable String id,
                                                  @RequestParam(defaultValue = "20") int limit) {
        requirePeer(agentId);
        requirePlan(agentId, id);
        return execs.execsByPlan(id, clampLimit(limit)).stream()
                .map(r -> toExec(r, false, null))
                .toList();
    }

    /** 该 Agent 的最近几批（观测台按批分组） */
    @GetMapping("/{agentId}/runs")
    public List<HeartbeatDtos.RunView> runs(@PathVariable String agentId,
                                            @RequestParam(defaultValue = "30") int limit) {
        requirePeer(agentId);
        return execs.runs(agentId, clampLimit(limit)).stream().map(AgentController::toRun).toList();
    }

    /** 某批里的各次执行 */
    @GetMapping("/{agentId}/runs/{id}/execs")
    public List<HeartbeatDtos.ExecView> runExecs(@PathVariable String agentId,
                                                 @PathVariable String id) {
        requirePeer(agentId);
        return execs.execsByRun(id).stream().filter(r -> subagent.auditVisibleTo(agentId, r.id()))
                .map(r -> toExec(r, false, null)).toList();
    }

    /** 某一次执行的完整过程：组装出的上下文、思维链、过程分段、写库清单 */
    @GetMapping("/{agentId}/execs/{id}")
    public HeartbeatDtos.ExecView exec(@PathVariable String agentId, @PathVariable long id) {
        requirePeer(agentId);
        ExecStore.ExecRow row = execs.exec(id)
                .filter(r -> subagent.auditVisibleTo(agentId, r.id()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "执行记录不存在: " + id));
        return toExecWithEdits(row);
    }

    /** 该 Agent 的最近若干条任务（倒序）；kind 缺省看全部 */
    @GetMapping("/{agentId}/tasks")
    public List<HeartbeatDtos.TaskView> tasks(@PathVariable String agentId,
                                              @RequestParam(defaultValue = "100") int limit,
                                              @RequestParam(required = false) String kind,
                                              jakarta.servlet.http.HttpServletRequest request) {
        requirePeer(agentId);
        String owner=codingOwner(request);
        return tasks.recent(agentId, blank(kind) ? null : kind.trim(), clampLimit(limit)).stream()
                .filter(row -> !"coding".equals(row.origin()) || (coding!=null && coding.ownsTask(owner,row.id())))
                .map(AgentController::toTask)
                .toList();
    }

    /** 重跑一条子代理任务（按它留下的 payload 重新派一次），新任务挂为原任务的子任务 */
    @PostMapping("/{agentId}/tasks/{id}/run")
    public HeartbeatDtos.TaskRunView rerunTask(@PathVariable String agentId,
                                               @PathVariable long id, HttpServletRequest request) {
        requirePeer(agentId);
        String actorId = request.getAttribute(SsoInterceptor.ATTR) instanceof SsoUser user ? user.getId() : null;
        long execId = subagent.rerunTask(agentId, id, actorId);
        if (execId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该任务不可重跑: id=" + id);
        }
        return new HeartbeatDtos.TaskRunView(id, execId);
    }

    /** 该 Agent 最近若干次子代理调用（它们不建档案，只有「跑过的记录」） */
    @GetMapping("/{agentId}/subagents")
    public List<HeartbeatDtos.ExecView> subagents(@PathVariable String agentId,
                                                  @RequestParam(defaultValue = "30") int limit) {
        requirePeer(agentId);
        return execs.latestExecs(ExecStore.KIND_AGENT, clampLimit(limit), agentId).stream()
                .map(r -> toExec(r, false, r.id()))
                .toList();
    }

    /** 该 Agent 最近若干条三路召回留痕（倒序）；channel 缺省看全部 */
    @GetMapping("/{agentId}/rag-recalls")
    public List<HeartbeatDtos.RagRecallView> ragRecalls(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String channel) {
        requirePeer(agentId);
        return ragRecalls.recent(clampLimit(limit), blank(channel) ? null : channel.trim(), agentId)
                .stream().map(AgentController::toRagRecall).toList();
    }

    /** 清空该 Agent 的召回留痕 */
    @DeleteMapping("/{agentId}/rag-recalls")
    public HeartbeatDtos.RagRecallCleared clearRagRecalls(@PathVariable String agentId) {
        requirePeer(agentId);
        return new HeartbeatDtos.RagRecallCleared(ragRecalls.clear(agentId));
    }

    /** 该 Agent 后台的资源使用快照（窗口内执行量 / 耗时 / 工具往返 / token 估算） */
    @GetMapping("/{agentId}/resources")
    public HeartbeatDtos.ResourceView resources(@PathVariable String agentId,
                                                @RequestParam(defaultValue = "24") int hours) {
        requirePeer(agentId);
        int h = Math.max(1, Math.min(hours, RESOURCE_HOURS_MAX));
        long since = System.currentTimeMillis() - h * 3_600_000L;
        List<PlanStore.PlanRow> roster = plans.plans(agentId);
        PlanStore.HeartbeatRow hb = heartbeat.heartbeat(agentId);
        return new HeartbeatDtos.ResourceView(hb.enabled(), hb.tickMs(),
                heartbeat.running(agentId), h, roster.size(),
                toTotals(execs.usage(since, null, agentId)),
                toTotals(execs.usage(since, ExecStore.KIND_AGENT, agentId)),
                toTotals(execs.usage(since, ExecStore.KIND_PLAN, agentId)),
                toTotals(execs.usage(since, ExecStore.KIND_HEARTBEAT, agentId)),
                planUsage(since, agentId, roster),
                heartbeat.idleSkips());
    }

    /** 该 Agent 后台的观测台快照（首屏拉取） */
    @GetMapping("/{agentId}/pulse")
    public HeartbeatDtos.PulseView pulse(@PathVariable String agentId) {
        requirePeer(agentId);
        return pulseView(agentId);
    }

    /** 该 Agent 后台的实时流：变化时推 pulse，无变化时发注释帧保活 */
    @GetMapping(value = "/{agentId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String agentId) {
        requirePeer(agentId);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        streamExecutor.execute(() -> {
            String last = null;
            long lastPing = System.currentTimeMillis();
            try {
                while (alive.get()) {
                    HeartbeatDtos.PulseView pulse = pulseView(agentId);
                    String key = SystemController.pulseKey(pulse);
                    long now = System.currentTimeMillis();
                    if (!key.equals(last)) {
                        last = key;
                        lastPing = now;
                        sendQuiet(emitter, alive, "pulse", pulse);
                    } else if (now - lastPing >= PING_MS) {
                        lastPing = now;
                        emitter.send(SseEmitter.event().comment("ping"));
                    }
                    Thread.sleep(PULSE_MS);
                }
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

    // ---------- 圆桌会话（对话面） ----------

    @GetMapping("/sessions")
    public List<AgentDtos.SessionView> sessions() {
        return chat.sessions();
    }

    /** 新建会话：body 里带 memberIds 就只让那几位上桌；不带（null） = 全体名册 */
    @PostMapping("/sessions")
    public AgentDtos.NewSession createSession(
            @RequestBody(required = false) AgentDtos.NewSessionRequest req) {
        return chat.createSession(req == null ? null : req.memberIds(), req == null ? null : req.projectId());
    }

    @GetMapping("/sessions/{id}")
    public AgentDtos.SessionView session(@PathVariable String id) {
        return chat.session(id);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, String> deleteSession(@PathVariable String id) {
        lifecycle.delete(id);
        return Map.of("sessionId", id);
    }

    /** 会话的消息；带 topicId 时只看那一次话题 */
    @GetMapping("/sessions/{id}/messages")
    public List<AgentDtos.MessageView> messages(@PathVariable String id,
                                                     @RequestParam(required = false) String topicId) {
        return chat.messages(id, topicId);
    }

    /**
     * 某条发言当时的原始上下文（点开一条成员发言，看它当时到底看到了什么：
     * system 里的人格与协作规程、调度器给的那条 user 里的公开记录与此刻局面、以及挂了哪些工具）。
     *
     * <p>与对话页「查看原始上下文」的区别：那个是「此刻再发一条会发什么」，这个是**当时真正发出去的那一份**。
     */
    @GetMapping("/messages/{id}/context")
    public AgentDtos.MessageContext messageContext(@PathVariable long id) {
        return new AgentDtos.MessageContext(id, chat.contextOf(id).orElse(null));
    }

    @GetMapping("/sessions/{id}/topics")
    public List<AgentDtos.TopicView> topics(@PathVariable String id) {
        return topics.topics(id);
    }

    /**
     * 执行态图的数据源，按视角分两种：
     * <ul>
     *   <li><b>不带 sessionId</b>——Agent 视角：全局名册上所有智能体此刻的执行态（跨会话），不绑定会话；</li>
     *   <li><b>带 sessionId</b>——任务视角：某张桌子此刻的编排全貌（成员 + 该会话的共享任务区）。</li>
     * </ul>
     */
    @GetMapping("/workflow")
    public AgentDtos.WorkflowView workflow(@RequestParam(required = false) String sessionId,
                                          jakarta.servlet.http.HttpServletRequest request) {
        return sessionId == null || sessionId.isBlank()
                ? workflowFlow.overview(codingOwner(request)) : workflowFlow.view(sessionId,codingOwner(request));
    }

    /**
     * 某条待办的执行历史（含思考链 / 过程分段）：点开执行态里的一条任务，看 Agent 到底在干嘛。
     */
    @GetMapping("/todos/{id}/runs")
    public List<AgentDtos.WorkRunView> todoRuns(@PathVariable String id,
                                                @RequestParam(defaultValue = "10") int limit) {
        return workflowFlow.todoRuns(id, Math.max(1, Math.min(limit, 50)));
    }

    // ---------- 执行态卡片上的三项人工干预 ----------
    //
    // 都不打断正在跑的那次 TURN（不抢占）：只改任务板 + 重新评估可开工，页面靠轮询刷新。
    // drop 会解锁它的下游（dropped 视为依赖已满足）；defer / check 只动优先级。

    /** 放弃一条：置 dropped（它的下游依赖随之解锁）。 */
    @PostMapping("/todos/{id}/drop")
    public AgentDtos.TodoPatch dropTodo(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) {
        try(var actor=cc.ginpika.minis.ai.core.UserScope.bind(codingOwner(request))) {
            return todoPatch(id, workRuntime.drop(id));
        }
    }

    /** 暂时跳过：压到最低优先级（待办直接压；进行中的先放回队列，跑完不被收成完成、转而排最后）。 */
    @PostMapping("/todos/{id}/defer")
    public AgentDtos.TodoPatch deferTodo(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) {
        try(var actor=cc.ginpika.minis.ai.core.UserScope.bind(codingOwner(request))) {
            return todoPatch(id, workRuntime.defer(id));
        }
    }

    /** 立刻检查：拉满优先级插到该成员队首并叫起它的执行线程（受阻的先放回待做，才跑得起来）。 */
    @PostMapping("/todos/{id}/check")
    public AgentDtos.TodoPatch checkTodo(@PathVariable String id,jakarta.servlet.http.HttpServletRequest request) {
        try(var actor=cc.ginpika.minis.ai.core.UserScope.bind(codingOwner(request))) {
            return todoPatch(id, workRuntime.checkNow(id));
        }
    }

    /** 干预结果：读回那一条（不存在时状态为空、优先级为 0） */
    private AgentDtos.TodoPatch todoPatch(String id, boolean ok) {
        RoundtableTodoStore.TodoRow row = taskBoard.todo(id).orElse(null);
        return new AgentDtos.TodoPatch(ok, id, row == null ? null : row.status(),
                row == null ? 0 : row.priority());
    }

    // ---------- 说话（SSE） ----------

    /**
     * 人往圆桌说一句话：这一句会依次引出一串成员发言，增量边跑边推。
     *
     * <p>没有会话 id 时用最近一个会话（没有就新建），与对话页的「直接开说」手感一致。
     *
     * <p><b>本接口只是「订阅 + 投递」，不承担执行的生命周期</b>：先订阅该会话的运行时事件流，
     * 再把这一轮排进会话议程，随即返回 emitter。执行跑在议程线程上，与这条连接无关——
     * 断线只是没人看（已说出的内容照常落库），300s 也不再是整轮的上限。
     * 详见 {@link RoundtableAgenda} 与 {@link RoundtableLiveBus}。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody AgentDtos.SendRequest req, jakarta.servlet.http.HttpServletRequest request) {
        String input = req == null || req.input() == null ? "" : req.input().strip();
        if (input.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 为空");
        }
        String sessionId = req.sessionId() == null || req.sessionId().isBlank()
                ? chat.sessions().stream().findFirst()
                        .map(AgentDtos.SessionView::id)
                        .orElseGet(() -> chat.createSession(null).sessionId())
                : req.sessionId();
        /* 这一次交互的关联 id：本连接只转发它的事件——同会话并发的两条消息因此互不串台。
           它同时就是将来接上事件正本时的 correlation_id */
        chat.requireDiscussable(sessionId);
        String runId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        /* 先订阅、再投递：本轮开头的事件不会漏在订阅之前 */
        Runnable unsubscribe = liveBus.subscribe(sessionId, (rid, name, payload) -> {
            if (!runId.equals(rid)) {
                return true;    // 别的交互的事件：不转发，也不影响本订阅
            }
            if (!alive.get()) {
                return false;
            }
            boolean sent = sendQuiet(emitter, alive, name, payload);
            if (sent && (EVENT_DONE.equals(name) || EVENT_ERROR.equals(name))) {
                /* 本轮到此为止：关掉连接。执行侧早已收尾，SSE 只是订阅者 */
                emitter.complete();
            }
            return sent && alive.get();
        });
        emitter.onCompletion(() -> {
            alive.set(false);
            unsubscribe.run();
        });
        emitter.onTimeout(() -> {
            alive.set(false);
            unsubscribe.run();
            emitter.complete();
        });
        emitter.onError(e -> {
            alive.set(false);
            unsubscribe.run();
        });

        /* 运行时按明确的调整意图区分普通对话与重新规划，按序发布并推 done / error。
           控制器只负责构造事件及订阅本次交互的事件流。 */
        try(var actor=cc.ginpika.minis.ai.core.UserScope.bind(codingOwner(request))){
            runtime.submitUserMessage(sessionId, input, req.modelId(), req.speakerStrategy(), runId,
                    Boolean.TRUE.equals(req.redirectWork()));
        }
        return emitter;
    }

    /** 人打断当前发言（页面的「停止」按钮）：正在说话的那位就此打住，已说出的部分照常落库 */
    @PostMapping("/chat/stop")
    public Map<String, Boolean> stop(@RequestBody AgentDtos.SendRequest req) {
        String sessionId = req == null ? null : req.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("stopped", false);
        }
        return Map.of("stopped", runtime.cancel(sessionId));
    }

    // ---------- 映射与流助手 ----------

    private AgentDtos.AgentView toPeer(AgentRow row) {
        return new AgentDtos.AgentView(row.id(), row.name(), row.builtin(),
                peers.declaredTerminals(row), row.modelId(), row.sort(),
                row.note(), row.avatar(), row.role(), row.roleColor(),
                "CHAT");
    }

    // ---------- 逐 Agent 后台的映射与快照 ----------

    private HeartbeatDtos.PulseView pulseView(String agentId) {
        PlanStore.HeartbeatRow hb = heartbeat.heartbeat(agentId);
        HeartbeatDtos.HeartbeatState state = new HeartbeatDtos.HeartbeatState(agentId,
                peers.nameOf(agentId), hb.enabled(), hb.tickMs(), hb.lastBeatAt(),
                heartbeat.running(agentId));
        List<HeartbeatDtos.RunBundle> recent = execs.runs(agentId, PULSE_RUNS).stream()
                .map(r -> new HeartbeatDtos.RunBundle(AgentController.toRun(r),
                        execsOfRun(r.id())))
                .toList();
        return new HeartbeatDtos.PulseView(List.of(state), recent);
    }

    /** 某批的执行（超过 PULSE_EXECS 时只留最近的若干条） */
    private List<HeartbeatDtos.ExecView> execsOfRun(String runId) {
        List<ExecStore.ExecRow> rows = execs.execsByRun(runId);
        if (rows.size() > PULSE_EXECS) {
            rows = rows.subList(rows.size() - PULSE_EXECS, rows.size());
        }
        return rows.stream().map(r -> toExec(r, false, null)).toList();
    }

    private HeartbeatDtos.HeartbeatView toHeartbeat(PlanStore.HeartbeatRow r) {
        return new HeartbeatDtos.HeartbeatView(r.agentId(), r.content(), r.modelId(), r.enabled(),
                r.tickMs(), r.lastBeatAt());
    }

    private HeartbeatDtos.PlanView toPlan(PlanStore.PlanRow r) {
        return new HeartbeatDtos.PlanView(r.id(), r.agentId(), r.name(), r.role(), r.content(),
                names(r.tools()), names(r.channels()), r.intervalMs(), r.input(), r.modelId(),
                r.sort(), r.lastBeatAt(), r.rationale(), r.enabled());
    }

    static HeartbeatDtos.RunView toRun(ExecStore.RunRow r) {
        return new HeartbeatDtos.RunView(r.id(), r.agentId(), r.beat(), r.trigger(), r.status(),
                r.agentCount(), r.note(), r.startedAt(), r.finishedAt());
    }

    /** 执行视图（带归属任务）：taskId 只在子代理列表里给（其余场景这次执行没有对应任务） */
    private HeartbeatDtos.ExecView toExec(ExecStore.ExecRow r, boolean withContext, Long taskId) {
        return new HeartbeatDtos.ExecView(r.id(), r.runId(), r.kind(), r.planId(), r.title(),
                r.status(), withContext ? r.context() : null, r.output(), r.reasoning(),
                traces(r.trace()), List.of(), r.error(), r.startedAt(), r.finishedAt(), taskId);
    }

    /** 执行详情：带上写库清单 */
    private HeartbeatDtos.ExecView toExecWithEdits(ExecStore.ExecRow r) {
        List<HeartbeatDtos.EditView> edits = execs.editsByExec(r.id()).stream()
                .map(AgentController::toEdit).toList();
        return new HeartbeatDtos.ExecView(r.id(), r.runId(), r.kind(), r.planId(), r.title(),
                r.status(), r.context(), r.output(), r.reasoning(), traces(r.trace()), edits,
                r.error(), r.startedAt(), r.finishedAt(), null);
    }

    private static HeartbeatDtos.TaskView toTask(TaskStore.TaskRow r) {
        /* 只有真正的执行（origin 非空）才留下执行留痕、可点开看全过程；
           前台会话任务（origin 为空，正文在会话里）没有执行上下文，execId 留空 → 行不可点 */
        Long execId = r.origin() == null ? null : r.id();
        return new HeartbeatDtos.TaskView(r.id(), r.parentId(), r.kind(), r.status(), r.title(),
                r.sessionId(), r.agentId(), "coding".equals(r.origin())?null:execId, r.createdAt(), r.updatedAt(),
                "coding".equals(r.origin())?r.payload():null);
    }

    private static String codingOwner(jakarta.servlet.http.HttpServletRequest request){
        Object actor=request.getAttribute(cc.ginpika.minis.web.sso.SsoInterceptor.ATTR);
        return actor instanceof cc.ginpika.minis.web.sso.SsoUser user && cc.ginpika.minis.coding.CodingFacade.allowsActorAt(user.getId(),request.getRemoteAddr())?user.getId():null;
    }

    private static HeartbeatDtos.EditView toEdit(ExecStore.EditRow r) {
        return new HeartbeatDtos.EditView(r.id(), r.tool(), r.args(), r.summary(), r.createdAt());
    }

    private static HeartbeatDtos.RagRecallView toRagRecall(RagRecallStore.RecallRow r) {
        return new HeartbeatDtos.RagRecallView(r.id(), r.channel(), r.tool(), r.source(),
                r.agentId(), r.query(), r.result(), r.resultChars(), r.createdAt());
    }

    /** 用量行 → 视图：字符量按 {@link #CHARS_PER_TOKEN} 折成 token 估算（只作量级参考） */
    private static HeartbeatDtos.UsageTotals toTotals(ExecStore.UsageRow r) {
        return new HeartbeatDtos.UsageTotals(r.runs(), r.done(), r.failed(), r.avgMs(),
                r.maxMs(), r.toolRounds(), r.chars() / CHARS_PER_TOKEN);
    }

    /** 逐份职责的用量：聚合结果按 planId 对上名册，名册里没跑过的补一条空用量 */
    private List<HeartbeatDtos.PlanUsage> planUsage(long since, String agentId,
                                                    List<PlanStore.PlanRow> roster) {
        List<HeartbeatDtos.PlanUsage> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ExecStore.PlanUsageRow r : execs.planUsage(since, agentId)) {
            seen.add(r.planId());
            out.add(new HeartbeatDtos.PlanUsage(r.planId(), planName(roster, r.planId()),
                    r.runs(), r.done(), r.failed(), r.avgMs(), r.maxMs(), r.toolRounds(),
                    r.chars() / CHARS_PER_TOKEN, r.lastAt()));
        }
        for (PlanStore.PlanRow p : roster) {
            if (!seen.contains(p.id())) {
                out.add(new HeartbeatDtos.PlanUsage(p.id(), p.name(), 0, 0, 0, 0, 0, 0, 0, null));
            }
        }
        return out;
    }

    /** planId → 展示名；计划已被撤掉时留空（历史留痕仍在，不应凭空消失） */
    private static String planName(List<PlanStore.PlanRow> roster, String planId) {
        return roster.stream().filter(p -> p.id().equals(planId)).findFirst()
                .map(PlanStore.PlanRow::name).orElse(null);
    }

    private HeartbeatDtos.BeatView toBeat(HeartbeatService.BeatSummary summary) {
        if (summary == null) {
            return new HeartbeatDtos.BeatView(false, null, 0, null, 0);
        }
        return new HeartbeatDtos.BeatView(true, summary.runId(), summary.beat(), summary.trigger(),
                summary.agents());
    }

    private void requirePeer(String agentId) {
        if (peers.peer(agentId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在: " + agentId);
        }
    }

    /** 取某 Agent 名下的一份职责；不属于该 Agent 时按不存在处理 */
    private PlanStore.PlanRow requirePlan(String agentId, String planId) {
        PlanStore.PlanRow plan = plans.planById(planId).orElse(null);
        if (plan == null || !agentId.equals(plan.agentId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "职责不存在: " + planId);
        }
        return plan;
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

    /** JSON 字符串数组文本 → 清单；空/坏数据回退空列表 */
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

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 发一个 SSE 事件（data 为 JSON 字符串）；写失败即客户端已断开，标记连接作废并返回 false，
     * 不再抛错——断开不打断生成，本轮照常跑完落库。
     */
    private boolean sendQuiet(SseEmitter emitter, AtomicBoolean alive, String name, Object payload) {
        if (!alive.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(om.writeValueAsString(payload)));
            return true;
        } catch (Exception e) {
            alive.set(false);
            return false;
        }
    }

    /** 客户端断开导致写流失败：常态，静默收尾（否则 tomcat 会记一整条 ERROR 堆栈） */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("圆桌流客户端已断开，写失败按断开收尾：{}", e.getMessage());
    }
}
