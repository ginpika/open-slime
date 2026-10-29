package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentDtos;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.agent.roundtable.RoundtableTopicService.Advance;
import cc.ginpika.minis.agent.runtime.ExecutionId;
import cc.ginpika.minis.agent.runtime.event.InterruptKind;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 圆桌讨论运行时：<b>事件驱动的会话级协调器</b>（设计一 §二 / 设计二 §一）。
 *
 * <p>群聊与单聊的请求驱动编排分开：两者共用底层 Agent 执行能力（{@code AgentRuntime} + {@code ChatTurnRunner}），
 * 但拥有不同的生命周期与调度策略。本类就是群聊那一侧的策略：
 * <b>事件从这里进，下一步由它决定；Agent 只负责推理与提出行动。</b>
 *
 * <p>形态上取代了原先「HTTP 线程一口气跑完整轮」：人的一句新话变成一条 {@code USER_MESSAGE} 事件
 * 进入运行时，运行时把这一轮排进会话议程，由同一位单写者<b>逐步</b>驱动（{@link Advance#step()}），
 * 在两次状态跃迁之间处理新到达的事件（取消、插话、场外报告）。
 *
 * <p><b>四类打断</b>（设计一 §三，见 {@link InterruptKind}）：
 * <ul>
 *   <li>{@code INTERRUPT}（{@link #submitUserMessage} 内部触发）——用户补充新要求：会话 revision +1
 *       （旧生成结果因此过期、不覆盖新状态），并尽力停止正在跑的那次生成；</li>
 *   <li>{@code CANCEL}（{@link #cancel}）——用户明确停止；</li>
 *   <li>{@code NOTIFY}（{@link #report}）——场外报告：只写事件日志、进入讨论记录、让相关成员举手，
 *       <b>不取消任何在跑执行</b>；</li>
 *   <li>{@code PREEMPT}——高优先级事件：M0 只预留 {@code priority}，不实现通用抢占算法。</li>
 * </ul>
 *
 * <p><b>执行标识</b>：每次成员发言都绑定一个 {@link ExecutionId}（会话 + runId + 成员 + 版本）。
 * 取消可按会话（{@link #cancel(String)}），也可按执行标识（{@link #cancel(ExecutionId)}）精确停止——
 * 「只取消受影响的那一次，未受影响的继续」。
 */
@Component
public class RoundtableDiscussionRuntime {
    /**
     * 一次编排的可观测快照。
     *
     * @param conversationId 会话 id
     * @param mode           会话模式（单聊 / 群聊）
     * @param running        此刻是否有一次推进正在跑
     * @param topicId        当前话题（无则 null）
     * @param topicStatus    当前话题状态（无则 null）
     * @param floorPeer      当前持有发言权的成员（无则 null）
     * @param communications 成员名 → 发言状态（{@code SPEAKING / LISTENING / SILENT}）
     * @param executions     成员名 → 执行状态（{@code READY / RUNNING / …}）
     */
    public record ConversationSnapshot(String conversationId, String mode, boolean running, String topicId,
                                String topicStatus, String floorPeer,
                                Map<String, String> communications,
                                Map<String, String> executions) {
    }

    private static final Logger log = LoggerFactory.getLogger(RoundtableDiscussionRuntime.class);

    private final RoundtableTopicService topics;
    private final RoundtableChatService chat;
    private final RoundtableRevision revisions;
    private final RuntimeEventBus events;
    private final RoundtableAgenda agenda;
    private final RoundtableLiveBus liveBus;
    private final SpeechPacer pacer;
    private final RoundtableSessionStore sessions;
    private final AgentDirectory directory;
    private final SpeakerPicker picker;
    private final TopicStateMachine machine;
    private final RoundtableMessageStore messages;
    /** 共享任务区：一轮讨论告一段落后判断「还有没有活要干」（自动复工） */
    private final RoundtableTodoStore taskBoard;
    private final RoundtableProgress progress;
    private final CollaborationPlan plans;
    private final CollaborationStore collaboration;

    /** 会话 id → 正在跑的那次推进（供取消 / 插话中断） */
    private final Map<String, Advance> running = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> conversations = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> conversationStops = new ConcurrentHashMap<>();
    /** 会话 id → 正在跑的那次执行的标识（设计二 §三：取消要有独立标识） */
    private final Map<String, ExecutionId> executions = new ConcurrentHashMap<>();
    /** 会话 id → 本轮待处理的事件类型队列（事件驱动的输入：两次跃迁之间被 drain） */
    private final Map<String, Queue<String>> pending = new ConcurrentHashMap<>();
    /** 受阻 / 报告到达时正有一轮在跑：记一笔，等这一轮结束再开一轮（信号绝不丢） */
    private final Set<String> resumeRequested = ConcurrentHashMap.newKeySet();
    /**
     * 会话 → 待续推的定向目标（成员 id 列表）：受阻成员点名只找某（几）位同伴时，接下来开的是
     * <b>定向轮</b>（只有这几位被叫起来依次各答一句），而不是全桌轮。无键 / 空表 = 全桌轮。
     *
     * <p>它要跨「补开的那一轮」活着（受阻报告常在已有轮在跑时到达），所以与
     * {@link #resumeRequested} 配对：一轮跑完还有待续推信号就沿用同一目标再开一轮，
     * 没有再要续推的目标时清掉——下一次受阻会重新写。
     */
    private final Map<String, List<String>> resumeTarget = new ConcurrentHashMap<>();
    /**
     * 会话 → 上一轮被打断的那位：新一轮据此把它排到最前，把没说完的话接着说完。
     *
     * <p>同一会话的推进走串行议程，所以「上一轮的 runTurn 写入 → 下一轮的 runTurn 读取」天然有序；
     * 读完即清。
     */
    private final Map<String, String> resumeAfterInterrupt = new ConcurrentHashMap<>();

    public RoundtableDiscussionRuntime(RoundtableTopicService topics, RoundtableChatService chat,
                                       RoundtableRevision revisions, RuntimeEventBus events,
                                       RoundtableAgenda agenda, RoundtableLiveBus liveBus,
                                       SpeechPacer pacer, RoundtableSessionStore sessions,
                                       AgentDirectory directory, SpeakerPicker picker,
                                       TopicStateMachine machine, RoundtableMessageStore messages,
                                       RoundtableTodoStore taskBoard, RoundtableProgress progress,
                                       CollaborationPlan plans, CollaborationStore collaboration) {
        this.topics = topics;
        this.chat = chat;
        this.revisions = revisions;
        this.events = events;
        this.agenda = agenda;
        this.liveBus = liveBus;
        this.pacer = pacer;
        this.sessions = sessions;
        this.directory = directory;
        this.picker = picker;
        this.machine = machine;
        this.messages = messages;
        this.taskBoard = taskBoard;
        this.progress = progress;
        this.plans = plans; this.collaboration = collaboration;
        /* 全局订阅：执行态把「全员顺利做完」报到桌面（{@link RuntimeEventType#ALL_WORK_DONE}）。
           这条信号常在「当前没有一轮在跑」时到达，按会话订阅只在推进期间有效，接不到它 */
        events.subscribeAll(row -> {
            dispatch(RuntimeEventBus.event(row));
            return true;
        });
    }

    // ---------- 事件入口 ----------

    /**
     * 协调器的统一事件入口：落正本、广播，并按类型决定下一步。
     *
     * <p>这是设计二「事件从这里进、下一步由它决定」的落点。普通通知（工具 / 任务事件）不改调度，
     * 只更新状态；只有用户消息、取消、插话、场外报告会改变编排走向。
     */
    public void onEvent(RuntimeEvent event) {
        /* 先落正本再处理：任何输入都先成为事件，再被折叠成调度决策（设计一 §二） */
        events.publish(event);
    }

    private void dispatch(RuntimeEvent event) {
        if(sessions.archived(event.sessionId()))return;
        switch (event.type()) {
            case USER_MESSAGE -> handleUserMessage(event);
            case CANCEL_REQUESTED -> {
                conversationStops.computeIfAbsent(event.sessionId(), key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                progress.pause(event.sessionId(), "用户已停止，发言后继续");
                resumeRequested.remove(event.sessionId());
                applyStop(event.sessionId());
            }
            case INTERRUPT_REQUESTED -> {
                conversationStops.computeIfAbsent(event.sessionId(), key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                applyStop(event.sessionId());
            }
            case ALL_WORK_DONE -> { if (plans.find(event.sessionId()).isEmpty()) handleAllWorkDone(event.sessionId()); }
            case ISSUE_RESOLVED -> {
                if (plans.find(event.sessionId()).isPresent()) report(event.sessionId(), "runtime",
                        "议题已回传结论，请主控核对并决定原条目如何推进：" + event.text("conclusion", ""), null);
            }
            /* 场外报告 / 请求讨论 / 任务受阻：都是「有人往桌上发信号」——写进记录、唤醒相关成员，
               并自主续推一轮。**受阻尤其不能默默卡着**：那位成员正空载等着讨论给她一个说法。 */
            case AGENT_REPORT -> handleReport(event);
            case DISCUSSION_REQUESTED, TASK_BLOCKED -> {
                var plan = plans.find(event.sessionId()).orElse(null);
                if (plan != null && !parseTargets(event.target()).contains("user")) break;
                // Work-related peer requests become isolated issues; user requests pause the whole task.
                if (event.taskId() == null || parseTargets(event.target()).contains("user")) handleReport(event);
            }
            default -> {
                /* 其余事件（成员发言 / 工具 / 任务）不改变本轮的推进权，状态由订阅方与状态机维护 */
            }
        }
    }

    /** 会话快照（只读）：编排水位 + 话题 + 各成员二维状态 */
    public ConversationSnapshot snapshot(String conversationId) {
        boolean running = this.running.containsKey(conversationId) || pending.containsKey(conversationId)
                || conversations.containsKey(conversationId);
        Optional<AgentDtos.TopicView> topic = topics.currentTopic(conversationId);
        if (topic.isEmpty()) {
            return new ConversationSnapshot(conversationId, RoundtableChatService.MODE, running,
                    null, null, null, Map.of(), Map.of());
        }
        AgentDtos.TopicView t = topic.get();
        return new ConversationSnapshot(conversationId, RoundtableChatService.MODE, running, t.id(),
                t.status(), t.floorPeer(), t.communications(), t.executions());
    }

    // ---------- 命令入口 ----------

    public void resetAfterRewind(String sessionId) {
        resumeRequested.remove(sessionId);
        resumeTarget.remove(sessionId);
        resumeAfterInterrupt.remove(sessionId);
        executions.remove(sessionId);
    }

    /**
     * 人的一句话：构造一条 {@code USER_MESSAGE} 事件交给协调器（{@link #onEvent}）。
     *
     * <p><b>立即返回</b>：不等待执行跑完；本轮结束时由协调器在议程线程上推 {@code done} / {@code error}。
     * 用户消息在投递前就落成事件正本，不等待任何正在运行的模型调用结束（设计一 §三-1）。
     */
    public void submitUserMessage(String sessionId, String text, String modelId,
                                  String speakerStrategy, String runId) {
        submitUserMessage(sessionId, text, modelId, speakerStrategy, runId, false);
    }

    public void submitUserMessage(String sessionId, String text, String modelId,
                                  String speakerStrategy, String runId, boolean redirectWork) {
        onEvent(RuntimeEvent.of(RuntimeEventType.USER_MESSAGE, sessionId, "user", runId,
                Map.of("input", text == null ? "" : text,
                        "modelId", modelId == null ? "" : modelId,
                        "strategy", speakerStrategy == null ? "" : speakerStrategy,
                        "redirectWork", redirectWork)));
    }

    /** 用户明确取消（停止按钮）：CANCEL 语义——停止生成并取消本轮 */
    public boolean cancel(String sessionId) {
        return interrupt(sessionId, InterruptKind.CANCEL, "用户要求停止");
    }

    /**
     * 按执行标识取消：只停指定的那一次执行（设计二 §三）。
     *
     * @return 是否命中并停止了它
     */
    public boolean cancel(ExecutionId executionId) {
        if (executionId == null) {
            return false;
        }
        ExecutionId current = executions.get(executionId.conversationId());
        if (current == null || !current.equals(executionId)) {
            return false;
        }
        return interrupt(executionId.conversationId(), InterruptKind.CANCEL, "按执行标识取消");
    }

    /**
     * 打断：写一条打断事件（{@code INTERRUPT_REQUESTED} / {@code CANCEL_REQUESTED}），
     * 尽力停止正在跑的那次生成，并取消本轮推进（其后续跃迁不再发生）。
     *
     * <p>{@link InterruptKind#NOTIFY} 不走这里（它只写事件、不取消），见 {@link #report}。
     *
     * @return 是否有正在进行的生成 / 推进被打断
     */
    public boolean interrupt(String sessionId, InterruptKind kind, String reason) {
        RuntimeEventType type = kind == InterruptKind.CANCEL
                ? RuntimeEventType.CANCEL_REQUESTED : RuntimeEventType.INTERRUPT_REQUESTED;
        events.publish(RuntimeEvent.of(type, sessionId, "user", null,
                Map.of("kind", kind.name(), "reason", reason)));
        return applyStop(sessionId);
    }

    /**
     * 场外 Agent 报告（NOTIFY，设计一 §三场景 B）：另一个任务里的成员 / 子代理发现了新证据。
     *
     * <p>它<b>不取消任何正在运行的模型</b>，只做三件事：写事件正本、把报告写进讨论记录（下一轮成员的
     * 公开记录里读得到）、若来源恰好是本桌成员则让它举手（下一轮优先发言）。
     * 紧急的安全措施应由确定性的策略直接触发，不能为了等大模型判断而延迟。
     */
    public void report(String sessionId, String source, String content, String runId) {
        report(sessionId, source, content, runId, null);
    }

    /**
     * 场外 Agent 报告（可定向）：{@code targetsCsv} 是逗号分隔的目标——成员 id 与保留值
     * {@link RoundtableTodoStore#BLOCK_WITH_USER}（用户）。非空时这次报告只找这几位：成员走定向轮
     * （依次各答一句），含「用户」则全桌暂停等用户发言，其余成员不受打扰。
     * 留空 = 广播给全桌，与 {@link #report(String, String, String, String)} 同义。
     */
    public void report(String sessionId, String source, String content, String runId, String targetsCsv) {
        onEvent(RuntimeEvent.of(RuntimeEventType.AGENT_REPORT, sessionId, source, targetsCsv, null, runId,
                Map.of("content", content == null ? "" : content)));
    }

    // ---------- 一次推进（单写者） ----------

    /** 处理 USER_MESSAGE：打断上一轮 → 排进议程逐步驱动 */
    private void handleUserMessage(RuntimeEvent event) {
        handleUserMessage(event,false);
    }
    private void handleUserMessage(RuntimeEvent event,boolean userRecorded) {
        String sessionId = event.sessionId();
        sessions.requireWritable(sessionId);
        var assignment = plans.find(sessionId).orElse(null);
        if (!userRecorded && !Boolean.parseBoolean(event.text("redirectWork", "false")) && assignment != null
                && assignment.criteria() != null) {
            handleConversation(event, assignment.coordinatorId());
            return;
        }
        progress.resume(sessionId);
        String runId = event.correlationId();
        String text = event.text("input", "");
        String modelId = blankToNull(event.text("modelId", ""));
        String speakerStrategy = blankToNull(event.text("strategy", ""));
        /* 新的一句话 = 新的版本：正在跑的旧生成结果因此会在提交时被版本校验挡下（不覆盖新状态） */
        int revision = revisions.bump(sessionId);
        if (collaboration.issueBySession(sessionId).isEmpty()) {
            var peers = directory.peers(sessionId);
            if (peers.size() >= 2 && plans.find(sessionId).isEmpty()) {
                plans.create(sessionId, peers.getFirst().id());
                messages.insertNotice(sessionId, null, "本任务改由「" + peers.getFirst().name() + "」主控规划；已有待办与成果保留，后续执行和检查由主控派发。", "plan_decision");
            }
            plans.reopen(sessionId);
        }
        /* **先把人的这句话落进记录，再打断上一轮**：顺序即因果——「某某被打断」是这句话引起的，
           先有人的话、再有打断，记录读起来才对。圆桌（≥2 位成员）才有这条状态行；私聊那条流
           自己会落人的话，别重复插（见 RoundtableTopicService#recordUserMessage） */
        boolean recorded = userRecorded || !text.isBlank() && directory.peers(sessionId).size() >= 2;
        if (recorded && !userRecorded) {
            topics.recordUserMessage(sessionId, text);
        }
        /* 打断上一轮（若在跑）：只是尽力停止旧生成，本轮作为新的一次推进排队 */
        interrupt(sessionId, InterruptKind.INTERRUPT, "用户补充了新要求");

        /* 订阅本会话的事件流，把到达的事件排进待处理队列——这是「两次跃迁之间处理事件」的输入。
           本轮开始时队列会被重置，故上一轮遗留的事件不会误伤本轮（见 runTurn）。 */
        Queue<String> queue = new ConcurrentLinkedQueue<>();
        pending.put(sessionId, queue);
        Runnable unsubscribe = events.subscribe(sessionId, null, row -> {
            queue.add(row.type());
            return true;
        });

        RoundtableTopicService.StreamSink sink = sink(sessionId, runId);
        agenda.submit(sessionId, () -> {
            try {
                if (!progress.allowed(sessionId) || revision != revisions.current(sessionId)) {
                    liveBus.emit(sessionId, runId, "done", new AgentDtos.TurnResult(sessionId, null, null, 0));
                    return;
                }
                AgentDtos.TurnResult result = runTurn(sessionId, text, modelId, speakerStrategy, sink,
                        recorded);
                if (revision == revisions.current(sessionId)) autoResume(sessionId);
                liveBus.emit(sessionId, runId, "done", result);
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("圆桌推进失败：sessionId={} runId={} error={}", sessionId, runId, msg, e);
                liveBus.emit(sessionId, runId, "error", Map.of("error", msg));
            } finally {
                unsubscribe.run();
                pending.remove(sessionId, queue);
                running.remove(sessionId);
                executions.remove(sessionId);
                /* 本轮跑的时候有人受阻 / 报告：现在补开一轮，把那些信号交出去（绝不丢）；
                   没有再要补开的，说明这一轮受阻话题告一段落——清掉定向目标，下一次受阻重新写 */
                if (resumeRequested.remove(sessionId)) {
                    scheduleAutonomous(sessionId);
                } else {
                    resumeTarget.remove(sessionId);
                }
            }
        });
    }

    /** Ordinary speech has no effect on assignment version, cancellation, budget or control decisions. */
    private void handleConversation(RuntimeEvent event, String coordinator) {
        String session = event.sessionId(), input = event.text("input", ""), runId = event.correlationId();
        sessions.updateModel(session, blankToNull(event.text("modelId", "")));
        long instructionId=topics.recordConversation(session, input);
        var peers = directory.peers(session);
        var targets = picker.addressed(input, peers);
        if (targets.isEmpty()) targets = peers.stream().filter(p -> p.id().equals(coordinator)).toList();
        var recipients = List.copyOf(targets);
        if (recipients.isEmpty()) {
            liveBus.emit(session, runId, "error", Map.of("error", "主控已不在任务成员中，请恢复成员或明确调整任务。"));
            return;
        }
        int revision = revisions.current(session);
        var stops = conversationStops.computeIfAbsent(session, key -> new java.util.concurrent.atomic.AtomicInteger());
        int stopVersion = stops.get();
        conversations.compute(session, (key, count) -> {
            if (count == null) count = new java.util.concurrent.atomic.AtomicInteger();
            count.incrementAndGet(); return count;
        });
        agenda.submit(session, () -> {
            try {
                int spoken = 0;
                var out = sink(session, runId);
                for (var peer : recipients) {
                    // Explicit task redirection invalidates queued conversation replies as well.
                    if (revision != revisions.current(session) || stopVersion != stops.get()) break;
                    sessions.requireWritable(session);
                    out.speaker(peer.id(), peer.name());
                    var speech = chat.converse(session, peer.id(), input, new cc.ginpika.minis.agent.chat.ChatStream() {
                        @Override public void delta(String reasoning, String content) { out.delta(reasoning, null); }
                        @Override public void tool(String name, String args) { out.tool(name, args); }
                    });
                    if(speech.stale() || revision!=revisions.current(session) || stopVersion!=stops.get())break;
                    sessions.requireWritable(session);
                    chat.publish(speech, new cc.ginpika.minis.agent.chat.ChatStream() {
                        @Override public void speech(String text) { out.speech(text); }
                    });
                    out.speakerEnd(peer.id(), "ACTIVE");
                    spoken++;
                    var control=speech.control();
                    if(control!=null) {
                        if(!Set.of("resume","replan","stop").contains(control.action()) || control.basis()==null
                                || control.basis().isBlank() || !input.contains(control.basis())
                                || directory.peers(session).stream().noneMatch(p->p.id().equals(peer.id())))
                            throw new IllegalStateException("控制请求与当前用户指令或会话成员不匹配，未执行。");
                        if(!messages.promoteWorkInstruction(session,instructionId))
                            throw new IllegalStateException("用户消息已变化，控制请求未执行。");
                        messages.insertNotice(session,null,"按本条用户指令处理工作控制："+control.action()+"；依据："+control.basis(),"work_control");
                        if("stop".equals(control.action())) {cancel(session);break;}
                        var current=plans.find(session).orElse(null);
                        if("resume".equals(control.action()) && current!=null && progress.allowed(session)
                                && Set.of("executing","checking").contains(current.phase()) && !current.assignments().isEmpty())break;
                        var payload=new java.util.LinkedHashMap<String,Object>(event.payload());
                        payload.put("redirectWork",true);
                        handleUserMessage(RuntimeEvent.of(RuntimeEventType.USER_MESSAGE,session,"user",runId,payload),true);
                        return; // The resumed planning turn owns the original stream's completion.
                    }
                }
                var topic = topics.currentTopic(session).orElse(null);
                liveBus.emit(session, runId, "done", new AgentDtos.TurnResult(session,
                        topic == null ? null : topic.id(), topic == null ? null : topic.status(), spoken));
            } catch (cc.ginpika.minis.agent.chat.ChatStream.ChatCancelledException cancelled) {
                liveBus.emit(session, runId, "done", new AgentDtos.TurnResult(session, null, null, 0));
            } catch (RuntimeException error) {
                liveBus.emit(session, runId, "error", Map.of("error", error.getMessage() == null ? error.toString() : error.getMessage()));
            } finally {
                conversations.computeIfPresent(session, (key, count) -> count.decrementAndGet() == 0 ? null : count);
            }
        });
    }

    /**
     * 场外报告 / 请求讨论：写进讨论记录、让来源举手（若它是本桌成员），并<b>自主续推一轮</b>——
     * 「成员埋头做 → 做完 / 遇阻 → 通知彼此 → 继续讨论」这条回边就落在这里。
     */
    private void handleReport(RuntimeEvent event) {
        String sessionId = event.sessionId();
        String source = event.source();
        String detail = event.text("content", event.text("reason", ""));
        String title = event.text("title", "");
        String content = title.isEmpty() ? detail
                : (detail.isEmpty() ? title : "「" + title + "」" + detail);
        String word = switch (event.type()) {
            case DISCUSSION_REQUESTED -> "讨论请求";
            case TASK_BLOCKED -> "受阻报告";
            default -> "报告";
        };
        /* 确保有一张「可以说话的桌子」：话题可能已经收场（closed），那样受阻就无处可去——
           受阻 / 请讨论是必须被回应的信号，所以这里按需重开一张桌子（历史记录仍按会话保留，上下文不丢） */
        String topicId = topics.currentTopic(sessionId).map(AgentDtos.TopicView::id).orElse(null);
        if (topicId == null) {
            List<AgentRow> peers = directory.peers(sessionId);
            if (peers.size() < 2) {
                return;   // 只有一位成员：没有圆桌可讨论（那是私聊）
            }
            RoundtableTopicStore.TopicRow fresh =
                    machine.open(sessionId, RoundtableTopicStore.ORIGIN_PEER, source);
            machine.seatAll(fresh.id(), peers.stream().map(AgentRow::id).toList());
            topicId = fresh.id();
        }
        String who = directory.nameOf(source);
        /* 定向目标：报告带了 target（受阻成员自己做的「找谁」决策），逗号分隔、可多个，还可含保留值
           「user」（找用户拍板）。这正是「受阻后先决策：找哪（几）位 / 找用户 / 找所有人」的落点。 */
        List<String> targets = parseTargets(event.target() == null ? event.text("with", "") : event.target());
        var plan = plans.find(sessionId).orElse(null);
        if (plan != null && Set.of("executing", "checking").contains(plan.phase()) && !targets.contains("user")) {
            messages.insertNotice(sessionId, topicId, "供主控在派工回报后核对：" + content, "plan_report");
            return;
        }
        if (plan != null && !targets.contains("user")) targets = List.of(plan.coordinatorId());
        List<AgentRow> peersTargets = new ArrayList<>();
        for (String id : targets) {
            if (RoundtableTodoStore.BLOCK_WITH_USER.equals(id)) {
                continue;
            }
            directory.peers(sessionId).stream()
                    .filter(p -> p.id().equals(id)).findFirst().ifPresent(peersTargets::add);
        }
        if (targets.contains(RoundtableTodoStore.BLOCK_WITH_USER)) {
            progress.pause(sessionId, content.isEmpty() ? "等待用户决定" : content);
            resumeRequested.remove(sessionId);
            applyStop(sessionId);
            /* 找用户拍板：不叫起任何成员——把请托者转到「等待用户」，全桌暂停，等用户发言 */
            resumeTarget.remove(sessionId);
            messages.insertNotice(sessionId, topicId,
                    who + " 需要你拍板" + (content.isEmpty() ? "" : "：" + content)
                            + " —— 全桌已暂停，等你发言。",
                    RoundtableMessageStore.KIND_RALLY);
            if (directory.peer(source).isPresent()) {
                machine.askUser(topicId, source, word);
            }
            return;
        }
        if (!peersTargets.isEmpty()) {
            resumeTarget.put(sessionId, peersTargets.stream().map(AgentRow::id).toList());
            messages.insertNotice(sessionId, topicId,
                    "已定向请 " + namesOfPeers(peersTargets) + " 回应 " + who + " 的" + word
                            + (content.isEmpty() ? "" : "：" + content)
                            + "（只你们沟通一轮，其余成员无需参与）",
                    RoundtableMessageStore.KIND_RALLY);
        } else {
            resumeTarget.remove(sessionId);
            /* 落一条**系统提示**（不再另发一条与成员自己那条「【执行】受阻」重复的发言）：
               受阻 / 请讨论必须被看见——「全桌已收到、其他成员开始处理」正是一眼要看到的那句反馈，
               前端据此渲染成醒目的高亮提示条。它照常进公开记录（模型侧读成 {@code <event>}），
               所以下一轮其他成员也看得见——「到讨论里沟通」才算真正发生 */
            messages.insertNotice(sessionId, topicId,
                    "全桌已收到 " + who + " 的" + word + "，其他成员开始处理"
                            + (content.isEmpty() ? "" : "：" + content),
                    RoundtableMessageStore.KIND_RALLY);
            if (directory.peer(source).isPresent()) {
                /* 内部仍让她举手：下一轮她优先被叫到。这只是调度信号，不再渲染成「举手抢答」动作行 */
                machine.interject(topicId, source, word);
            }
        }
        /* 开一轮讨论；若此刻正有一轮在跑，就记一笔，等它结束再开（信号绝不丢） */
        if (running.containsKey(sessionId) || pending.containsKey(sessionId)) {
            resumeRequested.add(sessionId);
        } else {
            scheduleAutonomous(sessionId);
        }
    }

    /**
     * 全员顺利做完（{@link RuntimeEventType#ALL_WORK_DONE}）→ 把全桌重新叫到一起「集结」。
     *
     * <p>这是「顺利推进」这条回边的落点：遇阻走 {@link #handleReport}（成员空载等一个说法），
     * 顺利做完走这里（全桌空载等一个方向）。没有它，最后一条做完就再没人被叫起来，
     * 交付断在那里——「干完会自动叫起来接着讨论」此前是句空话。
     *
     * <p>与 {@link #handleReport} 一样确保「有一张能说话的桌子」：话题可能已经收场（closed），
     * 那样全桌就无处集结、也无从谈交付，所以按需重开一张（历史记录仍按会话保留，上下文不丢）。
     * 落一条系统提示让「进入集结」这件事被一眼看见，再推一轮自主讨论：成员在那里要么
     * {@code add_todo} + {@code start_work} 开新一轮，要么全体 {@code propose_end} 收场（交付）。
     */
    private void handleAllWorkDone(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String topicId = topics.currentTopic(sessionId).map(AgentDtos.TopicView::id).orElse(null);
        if (topicId == null) {
            List<AgentRow> peers = directory.peers(sessionId);
            if (peers.size() < 2) {
                return;   // 只有一位成员：没有圆桌可集结（那是私聊）
            }
            RoundtableTopicStore.TopicRow fresh =
                    machine.open(sessionId, RoundtableTopicStore.ORIGIN_PEER, null);
            machine.seatAll(fresh.id(), peers.stream().map(AgentRow::id).toList());
            topicId = fresh.id();
        }
        messages.insertNotice(sessionId, topicId,
                "全员已完成本轮工作（无人受阻），进入推进集结：一起决定是交付收场，还是落下一轮工作。",
                RoundtableMessageStore.KIND_RALLY);
        /* 集结是全桌的事：清掉可能残留的定向目标，确保开的是全桌轮 */
        resumeTarget.remove(sessionId);
        /* 开一轮讨论；若此刻正有一轮在跑，就记一笔，等它结束再开（信号绝不丢） */
        if (running.containsKey(sessionId) || pending.containsKey(sessionId)) {
            resumeRequested.add(sessionId);
        } else {
            scheduleAutonomous(sessionId);
        }
    }

    /**
     * 自主续推：没有「人的新话」，仅因外部事件把进行中的话题再推一轮。
     *
     * <p>它是「成员自己埋头做 → 做完 / 遇阻 → 通知彼此 → 接着讨论」闭环的驱动点，也是人的一句话
     * 不再是唯一驱动源的落点（设计一 §二）。
     *
     * <p>已有一轮在跑（或已排队）时不动作——那条事件会由在跑的那一轮 {@link #drain} 处理；
     * 没有进行中的话题时不动作——不凭空重开一张已经收场的桌子。
     */
    private void scheduleAutonomous(String sessionId) {
        if (!progress.allowed(sessionId) || topics.currentTopic(sessionId)
                .map(t -> "waiting".equals(t.status())).orElse(false)) return;
        if (running.containsKey(sessionId) || pending.containsKey(sessionId)) {
            return;
        }
        if (topics.currentTopic(sessionId).isEmpty()) {
            return;
        }
        List<AgentRow> peers = directory.peers(sessionId);
        if (peers.size() < 2) {
            return;
        }
        Queue<String> queue = new ConcurrentLinkedQueue<>();
        pending.put(sessionId, queue);
        Runnable unsubscribe = events.subscribe(sessionId, null, row -> {
            queue.add(row.type());
            return true;
        });
        String runId = UUID.randomUUID().toString();
        RoundtableTopicService.StreamSink sink = sink(sessionId, runId);
        SpeakerStrategy strategy = picker.resolve(null);
        /* 有定向目标（受阻成员点名找某几位同伴）就开定向轮：只这几位被叫起来依次各答一句，
           其余成员不受打扰；没有则照旧开全桌轮。目标此刻已不在名册里就退回全桌轮，
           别让一处悬空引用把讨论卡住 */
        List<AgentRow> targets = new ArrayList<>();
        List<String> targetIds = resumeTarget.get(sessionId);
        if (targetIds != null) {
            for (String id : targetIds) {
                directory.peer(id).ifPresent(targets::add);
            }
        }
        var control = plans.find(sessionId).orElse(null);
        if (control != null) {
            targets.clear();
            directory.peers(sessionId).stream().filter(p -> p.id().equals(control.coordinatorId())).findFirst().ifPresent(targets::add);
            if (targets.isEmpty()) {
                progress.pause(sessionId, "主控已不在任务成员中，请恢复成员后继续");
                unsubscribe.run(); pending.remove(sessionId, queue);
                messages.insertNotice(sessionId, null, "主控已不在任务成员中，自动推进已暂停。", "runtime_paused");
                return;
            }
        }
        agenda.submit(sessionId, () -> {
            try {
                if (!progress.step(sessionId)) {
                    messages.insertNotice(sessionId, null, progress.state(sessionId).reason(), "runtime_paused");
                    return;
                }
                Advance adv = targets.isEmpty()
                        ? topics.openAutonomous(sessionId, peers, strategy, sink)
                        : topics.openDirected(sessionId, peers, targets, sink);
                if (adv == null) {
                    return;
                }
                running.put(sessionId, adv);
                try {
                    while (progress.allowed(sessionId)) {
                        if (!progress.step(sessionId)) {
                            adv.cancel();
                            messages.insertNotice(sessionId, adv.topicId(), progress.state(sessionId).reason(), "runtime_paused");
                            break;
                        }
                        if (!adv.step()) break;
                        drain(sessionId, adv);
                        if (adv.cancelled()) {
                            break;
                        }
                    }
                    drain(sessionId, adv);
                } finally {
                    running.remove(sessionId, adv);
                }
                adv.finish();
                if (!adv.cancelled()) autoResume(sessionId);
                liveBus.emit(sessionId, runId, "done", new AgentDtos.TurnResult(sessionId, adv.topicId(),
                        topics.currentTopic(sessionId).map(AgentDtos.TopicView::status).orElse(null),
                        adv.spoken()));
            } catch (RuntimeException e) {
                log.warn("自主续推失败：sessionId={} error={}", sessionId, e.toString());
            } finally {
                unsubscribe.run();
                pending.remove(sessionId, queue);
                running.remove(sessionId);
                executions.remove(sessionId);
                /* 本轮跑的时候有人受阻 / 报告：现在补开一轮，把那些信号交出去（绝不丢）；
                   没有再要补开的，说明这一轮受阻话题告一段落——清掉定向目标，下一次受阻重新写 */
                if (resumeRequested.remove(sessionId)) {
                    scheduleAutonomous(sessionId);
                } else {
                    resumeTarget.remove(sessionId);
                }
            }
        });
    }

    /**
     * 一轮讨论告一段落后，只要任务区还有没做的条目，就让各成员继续开工。
     *
     * <p>这是「不中断」的兜底：成员埋头做、受阻发起讨论、讨论把任务区改好之后，
     * 不该要求人（或某位成员）记得再喊一次 {@code start_work}——有活就接着干。
     *
     * <p><b>不再因「有 blocked 条目」而停手</b>：受阻条目本来就领不到（claimable 只取 pending），
     * 它挡不住别的活；此前的刹车会让「讨论完但仍有一条没处理干净」的场面彻底停摆
     * （重启后再插话也一样），交付就此断掉——那是比空转更糟的失败。
     */
    private void autoResume(String sessionId) {
        var plan = plans.find(sessionId).orElse(null);
        if (plan != null) {
            if (Set.of("executing", "checking").contains(plan.phase()) && progress.allowed(sessionId)) {
                events.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, sessionId, plan.coordinatorId(),
                        Map.of("todoId", plan.todoId(), "note", "主控本次规划结束，按已授权派工执行")));
            }
            if ("planning".equals(plan.phase()) && progress.allowed(sessionId) && !resumeRequested.contains(sessionId)
                    && collaboration.issues().stream().noneMatch(i -> sessionId.equals(i.parentSessionId()) && "open".equals(i.status()))) {
                String reason = "主控本次未明确派工、发起议题或确认交付，已暂停；请检查计划后发言继续。";
                progress.pause(sessionId, reason);
                messages.insertNotice(sessionId, null, reason, "runtime_paused");
            }
            return;
        }
        if (!progress.allowed(sessionId) || topics.currentTopic(sessionId)
                .map(t -> "waiting".equals(t.status())).orElse(false)) return;
        boolean hasPending = taskBoard.openOnes(sessionId).stream()
                .anyMatch(t -> Set.of("pending", "reviewing").contains(t.status()));
        if (hasPending) {
            events.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, sessionId, "runtime",
                    Map.of("note", "讨论告一段落，任务区仍有待办", "by", "runtime")));
        }
    }

    /**
     * 一次推进：开 / 续话题 → 逐步驱动状态跃迁（每步之间处理事件）→ 收尾。
     *
     * <p>会话里只剩一位成员时不走圆桌这一套，交给 {@link RoundtableTopicService#sendMessage}
     * 的一对一私聊分支（不落话题、不跑状态机）。
     */
    private AgentDtos.TurnResult runTurn(String sessionId, String text, String modelId,
                                         String speakerStrategy, RoundtableTopicService.StreamSink sink,
                                         boolean userRecorded) {
        SpeakerStrategy strategy = picker.resolve(speakerStrategy);
        RoundtableSessionStore.SessionRow session = sessions.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("多智能体会话不存在: " + sessionId));
        if (modelId != null) {
            sessions.updateModel(sessionId, modelId);
        }
        java.util.List<AgentRow> peers = directory.peers(sessionId);
        if (peers.isEmpty()) {
            throw new IllegalStateException("这张桌子上还没有成员：请先在「多智能体 · 成员」页添加成员，"
                    + "或新建一个选了成员的会话");
        }
        if (peers.size() == 1) {
            return topics.sendMessage(sessionId, text, modelId, speakerStrategy, sink);
        }

        /* 队列由 handleUserMessage 创建并由该次推进负责清理，不能在这里替换。 */
        executions.put(sessionId, ExecutionId.of(sessionId, null, null, revisions.current(sessionId)));

        /* 上一轮若因人插话被打断，把它排到最前：让没说完的话接着说完（读完即清） */
        String resumePeer = resumeAfterInterrupt.remove(sessionId);
        var plan = plans.find(sessionId).orElse(null);
        AgentRow controller = plan == null ? null : peers.stream().filter(p -> p.id().equals(plan.coordinatorId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("主控已不在任务成员中，请恢复成员后继续"));
        Advance adv = plan == null ? topics.open(sessionId, peers, text, strategy, sink, userRecorded, resumePeer)
                : topics.openController(sessionId, peers, controller, text, sink, userRecorded);
        running.put(sessionId, adv);
        try {
            while (progress.allowed(sessionId)) {
                if (!progress.step(sessionId)) {
                    adv.cancel();
                    messages.insertNotice(sessionId, adv.topicId(), progress.state(sessionId).reason(), "runtime_paused");
                    break;
                }
                if (!adv.step()) break;
                /* 两次状态跃迁之间：处理已到达的事件——取消 / 插话作废本轮 / 场外报告唤醒。
                   「Turn 不是调度边界」正体现在这里：循环的每一圈都是一次独立的状态跃迁。 */
                drain(sessionId, adv);
                if (adv.cancelled()) {
                    break;
                }
            }
            drain(sessionId, adv);
        } finally {
            running.remove(sessionId, adv);
        }
        adv.finish();
        /* 本轮若被打断，记下是谁——下一轮的用户消息到来时优先交给它续话 */
        if (adv.interruptedPeer() != null) {
            resumeAfterInterrupt.put(sessionId, adv.interruptedPeer());
        }
        return new AgentDtos.TurnResult(sessionId, adv.topicId(),
                topics.currentTopic(sessionId).map(AgentDtos.TopicView::status).orElse(null),
                adv.spoken());
    }

    /**
     * 处理本轮已到达的事件（事件驱动的「调度器判断谁受影响」）。
     *
     * <p>普通通知（{@code AGENT_MESSAGE} / 工具与任务事件）不改变调度——它们只更新状态；
     * 只有取消与插话会作废本轮，场外报告只唤醒不取消。
     */
    private void drain(String sessionId, Advance adv) {
        Queue<String> queue = pending.get(sessionId);
        if (queue == null) {
            return;
        }
        String type;
        while ((type = queue.poll()) != null) {
            if (RuntimeEventType.CANCEL_REQUESTED.name().equals(type)
                    || RuntimeEventType.INTERRUPT_REQUESTED.name().equals(type)) {
                /* 取消 / 插话：本轮不再往下推进（正在跑的那次生成由停止标志中断，已说出的部分照常落库） */
                adv.cancel();
            }
            /* 其余（含 AGENT_REPORT）：不改变本轮的推进权，处理已在 handleReport 里完成 */
        }
    }

    // ---------- 私有 ----------

    /** 停止该会话正在跑的生成与推进（取消 / 插话共用） */
    private boolean applyStop(String sessionId) {
        boolean stopped = chat.requestStop(sessionId);
        Advance adv = running.get(sessionId);
        if (adv != null) {
            adv.cancel();
        }
        return stopped || adv != null;
    }

    /**
     * 本轮的事件出口：把成员发言过程的每一次回调广播到会话总线（带 runId 关联），
     * 由订阅者（SSE 连接）各自转发。协调器因此不握着任何 HTTP 连接——事件名与载荷的形状与从前逐字一致。
     */
    private RoundtableTopicService.StreamSink sink(String sessionId, String runId) {
        return new RoundtableTopicService.StreamSink() {
            @Override
            public void topic(String topicId, String status) {
                liveBus.emit(sessionId, runId, "topic",
                        Map.of("sessionId", sessionId, "topicId", topicId, "status", status));
            }

            @Override
            public void speaker(String peerId, String peerName) {
                liveBus.emit(sessionId, runId, "speaker",
                        Map.of("peerId", peerId, "peerName", peerName));
            }

            @Override
            public void delta(String reasoning, String content) {
                if (reasoning != null) {
                    liveBus.emit(sessionId, runId, "reasoning", Map.of("delta", reasoning));
                }
                if (content != null) {
                    liveBus.emit(sessionId, runId, "content", Map.of("delta", content));
                }
            }

            /** 成员又开口说了一段（一次 speak）：前端据此开一个新气泡 */
            @Override
            public void speech(String text) {
                /* 按字数等一会儿再推：整段话一次到，不节流的话几条会一起砸出来 */
                pacer.pace(text);
                liveBus.emit(sessionId, runId, "speech", Map.of("text", text));
            }

            @Override
            public void tool(String name, String args) {
                liveBus.emit(sessionId, runId, "tool", Map.of("name", name, "args", args));
            }

            @Override
            public void speakerEnd(String peerId, String state) {
                liveBus.emit(sessionId, runId, "speakerEnd",
                        Map.of("peerId", peerId, "state", state));
            }

            @Override
            public void state(AgentDtos.TopicView topic) {
                liveBus.emit(sessionId, runId, "state", topic);
            }

            @Override
            public void topicEnd(String topicId) {
                liveBus.emit(sessionId, runId, "topicEnd", Map.of("topicId", topicId));
            }

            @Override
            public void action(String text) {
                liveBus.emit(sessionId, runId, "action", Map.of("text", text));
            }
        };
    }

    /** 把逗号分隔的目标串拆成列表（去空、去重、保序）；空串返回空表 */
    private static List<String> parseTargets(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : csv.split(",")) {
            String s = t.strip();
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 目标成员的展示名（用「、」连接），用于「已定向请 A、B 回应」这类提示 */
    private static String namesOfPeers(List<AgentRow> peers) {
        StringBuilder sb = new StringBuilder();
        for (AgentRow p : peers) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(p.name());
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
