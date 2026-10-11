package cc.ginpika.minis.agent.subagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.agent.chat.ChatTrace;
import cc.ginpika.minis.agent.chat.SubagentBatchDoneEvent;
import cc.ginpika.minis.agent.chat.SubagentReportEvent;
import cc.ginpika.minis.agent.chat.SubagentTool;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.agent.runtime.AgentRunner;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.agent.runtime.ExecStore;
import cc.ginpika.minis.agent.runtime.TaskRunner;
import cc.ginpika.minis.agent.runtime.TaskStore;
import cc.ginpika.minis.agent.runtime.ToolBatch;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.SubagentBlock;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 子代理：被当场交代去独立干一件活的<b>匿名 worker</b>——不建档案、跑完即逝。
 *
 * <p>这是主流 AI Agent 里 sub-agent 的语义，四条都齐：
 * <ul>
 *   <li><b>独立上下文</b>——system 是子代理自己的人格（{@code subagent} 提示词位，可在提示词页覆盖），
 *       user 只有派它的人写的那段交代；看不到当前对话，也没有上一轮的记忆。</li>
 *   <li><b>窄工具集</b>——默认只给只读检索那几件（向量检索 / 图谱查询 / 读文档）；
 *       要它写知识库得由调用方显式点名。</li>
 *   <li><b>只回结论</b>——过程落在 {@code task}（origin=agent）里可回看，但不占派活方的上下文，
 *       回流给他的只有一段结论文本。</li>
 *   <li><b>不阻塞主会话</b>——对话侧派活当场只回一张受理回执，真正的 LLM 往返在后台跑，
 *       结论作为一条新消息投回原会话（见 {@link #runChatTool}）。</li>
 *   <li><b>可并行</b>——同一条消息里派出的几个子代理互不相干，由 {@link ToolBatch} 并发跑。</li>
 *   <li><b>共享黑板</b>（STEP 4）——跑完的结论沉淀进本会话的黑板（{@link ScratchpadStore}），
 *       后派的子代理开工先读它：同一会话里先派后派能接力，不重复查同一件事。</li>
 * </ul>
 *
 * <p>与「任务计划」（{@code heartbeat} 模块）的分工：计划是<b>常驻编制</b>——有名字、有正文、
 * 有节拍，由心跳主会话按需派出，能反复用；子代理是<b>临时的兵</b>——一次性，用完即弃。
 * 两者共用同一个执行引擎（{@link AgentRunner}）与同一张执行留痕表，因为「被派出去跑一次」
 * 这件事本身是一样的：计划被派出的那一次，跑的就是一个带着常驻正文的匿名实例。
 */
@Service
public class SubagentService implements SubagentTool {

    private static final Logger log = LoggerFactory.getLogger(SubagentService.class);

    /** 一批里最多派几个子代理：每个都是一轮完整的 LLM 往返，留个阀门防一口气派飞 */
    private static final int MAX_PER_BATCH = 8;

    /** 没给名字时，从交代里截取多少字作展示名 */
    private static final int TITLE_MAX = 18;

    /** 回放过程时正文/思维链的截断上限 */
    private static final int REPLAY_LIMIT = 6000;

    private final TaskRunner runner;
    private final ExecStore store;
    /** 任务：对话侧的异步派活每条落一行，进程重启后可恢复为「已中断、可重跑」 */
    private final TaskStore tasks;
    /** 共享黑板：子代理结论沉淀于此，供后续子代理开工时参考（见 {@link ScratchpadStore}） */
    private final ScratchpadStore scratchpad;
    private final PromptManager prompts;
    private final ToolManager tools;
    private final KnowledgeToolbox toolbox;
    private final ObjectMapper om;
    /** 上下文平面：子代理的 system 由它 + {@code SubagentContextProvider} 组装（见 STEP 6-③） */
    private final ContextBuilder context;
    /** 结论投回的落点：发事件让 ChatService 把「子代理回报」渲染进派它的那个会话 */
    private final ApplicationEventPublisher publisher;

    /** 子代理自己开的批次序号（与心跳的 beat 无关，只是页面上的 #N 展示用） */
    private final AtomicLong batchSeq = new AtomicLong();

    /** 后台派活用的虚拟线程池：只跑「等上游 LLM 返回」这类阻塞活，不占主会话线程 */
    private static final ExecutorService POOL = Executors.newVirtualThreadPerTaskExecutor();

    /** 各批次还欠着几件没回报的异步派活：归零才收批 */
    private final ConcurrentHashMap<String, AtomicInteger> pending = new ConcurrentHashMap<>();

    /** 各批次的投回会话（批次 id → hubId）：收批时据此发「整批跑完」事件让主模型补整体总结 */
    private final ConcurrentHashMap<String, String> batchHub = new ConcurrentHashMap<>();

    /** 已请求收批的批次（调用方的 finally 先到、子代理后跑完时，由后者真正收） */
    private final Set<String> closeRequested = ConcurrentHashMap.newKeySet();

    /** 各会话在跑的子代理（hubId → 执行 id）：每轮据此告诉主模型「还有什么活派出去没回来」 */
    private final ConcurrentHashMap<String, Set<Long>> running = new ConcurrentHashMap<>();

    public SubagentService(TaskRunner runner, ExecStore store, TaskStore tasks,
                           ScratchpadStore scratchpad, PromptManager prompts,
                           ToolManager tools, KnowledgeToolbox toolbox, ObjectMapper om,
                           @Lazy ContextBuilder context, ApplicationEventPublisher publisher) {
        this.runner = runner;
        this.store = store;
        this.tasks = tasks;
        this.scratchpad = scratchpad;
        this.prompts = prompts;
        this.tools = tools;
        this.toolbox = toolbox;
        this.om = om;
        this.context = context;
        this.publisher = publisher;
    }

    // ---------- 工具入口（对话侧 / 心跳侧转交过来） ----------

    /** 心跳侧入口：派活当场等结论——心跳主会话要拿它继续裁量这一拍还派谁（见 {@link #delegateTool}） */
    @Override
    public String runTool(String agentId, JsonNode call, String runId, String trigger,
                          Long parentTaskId) {
        String name = call.path("function").path("name").asText("");
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case DelegateSubagentTool.NAME -> delegateTool(args, agentId, runId, trigger, parentTaskId);
            case ReadSubagentRunTool.NAME -> replayTool(args);
            default -> "未知工具: " + name;
        };
    }

    /** 对话侧入口：派活非阻塞——当场回一张受理回执，结论跑完投回会话（见 {@link #delegateAsync}） */
    @Override
    public String runChatTool(String agentId, JsonNode call, String runId, String hubId) {
        String name = call.path("function").path("name").asText("");
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case DelegateSubagentTool.NAME -> delegateAsync(args, agentId, runId, hubId);
            case ReadSubagentRunTool.NAME -> replayTool(args);
            default -> "未知工具: " + name;
        };
    }

    // ---------- 批次（一次派活所属的一批） ----------

    /** 开一批：一轮里派出的多个子代理共属一批，页面的「最近几批」不会被逐个调用刷屏 */
    public String openRun(String agentId, String trigger) {
        String runId = UUID.randomUUID().toString();
        store.insertRun(runId, agentId, batchSeq.incrementAndGet(), trigger);
        pending.put(runId, new AtomicInteger());
        return runId;
    }

    /**
     * 收一批：状态按批内各次执行的结果定（有没跑出结论的就算 failed）。
     *
     * <p>对话侧的子代理在后台跑，所以这里往往只是「记下该收了」：批里还欠着执行就先按兵不动，
     * 等最后一件回报时由 {@link #settle} 真正收批——否则刚派出去的一批会被当场判成 failed。
     */
    public void closeRun(String runId) {
        closeRequested.add(runId);
        settle(runId);
    }

    /**
     * 这一批跑完了才收：还欠着执行、或调用方还没说要收，都不动。
     *
     * <p>收批也是「整批子代理都回来了」的唯一时点：在这里通知 {@code ChatService} 补一条整体的
     * 秘书式总结——一次输入派出多个子代理时，总结只做一次（每件的结论仍各自落一条气泡）。
     */
    private void settle(String runId) {
        AtomicInteger left = pending.get(runId);
        if (left == null || left.get() > 0 || !closeRequested.contains(runId)) {
            return;
        }
        List<ExecStore.ExecRow> execs = store.execsByRun(runId);
        boolean failed = execs.stream().anyMatch(e -> !"done".equals(e.status()));
        store.finishRun(runId, failed ? "failed" : "done", execs.size(),
                failed ? "有子代理未产出结论" : null);
        pending.remove(runId);
        closeRequested.remove(runId);
        /* 发事件交给后台线程：收批可能发生在对话请求线程的 finally 里（子代理跑得太快时，
           调用方的收批请求反而后到），而整体总结要跑模型，不能把那个线程占住 */
        String hubId = batchHub.remove(runId);
        if (hubId != null && !execs.isEmpty()) {
            int count = execs.size();
            POOL.execute(() -> publisher.publishEvent(new SubagentBatchDoneEvent(hubId, runId, count)));
        }
    }

    // ---------- 派一个子代理：心跳侧（当场等结论） ----------

    /**
     * 派一个子代理独立去干一件活：建一份只属于它的上下文，当场跑完把结论交回来。
     *
     * <p>只在心跳侧用：心跳主会话派活就是为了拿结论继续裁量这一拍。对话侧走
     * {@link #delegateAsync}——那边不能把用户的主会话挂住。
     *
     * @param runId        归属批次；null 表示由子代理自己开一批
     * @param parentTaskId 派它的那个任务（心跳主会话 / 计划）；顶层传 null
     */
    private String delegateTool(JsonNode args, String agentId, String runId, String trigger,
                                Long parentTaskId) {
        String instruction = args.path("instruction").asText("").trim();
        if (instruction.isEmpty()) {
            return "缺少 instruction：子代理看不到当前对话，请把要它查什么、产出什么写清楚。";
        }
        String batchId = runId;
        if (batchId == null) {
            batchId = openRun(agentId, trigger == null ? "chat" : trigger);
        } else if (store.execsByRun(batchId).size() >= MAX_PER_BATCH) {
            return "这一批派出的子代理已达上限（" + MAX_PER_BATCH + " 个）："
                    + "请等它们出结论，或把活合并成更少的几件。";
        }
        List<String> tools = names(args.path("tools"));
        String title = title(args.path("name").asText(""), instruction);

        TaskRunner.Outcome outcome;
        try {
            outcome = runSync(batchId, agentId, title, instruction, tools, parentTaskId,
                    args.toString());
        } catch (RuntimeException e) {
            log.warn("子代理「{}」执行失败：{}", title, e.getMessage());
            return "子代理执行失败：" + e.getMessage();
        }
        if (!"done".equals(outcome.status())) {
            return "子代理执行失败：" + (outcome.error() == null ? "未知原因" : outcome.error());
        }
        return "（子代理执行 id=" + outcome.execId() + "，如需核对它的检索过程可调用 "
                + ReadSubagentRunTool.NAME + "）\n" + outcome.output();
    }

    /**
     * 跑一个子代理（心跳侧，同步等结论）：组装它那份上下文 → 登记执行与任务 → 交给执行引擎 → 收尾。
     *
     * <p>模型跟随设置页默认（子代理不需要挑模型：它是被派出去干活的，不是配置项）。
     * 同步派活与对话侧异步派活同属子代理流，任务树里应当都在——故这里同样落一行任务，
     * 并挂到派它的那个任务下（心跳主会话 / 计划）。
     */
    private TaskRunner.Outcome runSync(String runId, String agentId, String title, String instruction,
                                       List<String> toolNames, Long parentTaskId, String payload) {
        TaskRunner.Request req = request(runId, agentId, title, instruction, toolNames, null,
                parentTaskId, null, payload);
        /* 开一次执行＝建那行任务（带上下文与来源）；收尾也由执行器一并落状态 */
        long execId = runner.openExec(req);
        try {
            return AgentScope.run(agentId, () -> runner.run(req, execId));
        } catch (RuntimeException e) {
            tasks.finish(execId, false);
            throw e;
        }
    }

    /**
     * 组装一次子代理执行：上下文只属于它，工具面按调用方点名（不填 = 默认只读检索）。
     *
     * <p>同步与异步两条路共用它——差别只在「谁来跑、结论怎么回去」，上下文本身没有两套。
     * {@code hubId} 用于注入本会话的共享黑板（子代理的结论沉淀）；心跳侧无会话，传 null。
     * {@code agentId} 写进任务行，标明这次子代理是替哪个 Agent 干的。
     */
    private TaskRunner.Request request(String runId, String agentId, String title, String instruction,
                                       List<String> toolNames, String hubId, Long parentTaskId,
                                       String sessionId, String payload) {
        ArrayNode toolSchemas = toolNames.isEmpty()
                ? tools.readOnlyToolsOf(ToolSurface.CHAT)
                : tools.toolsOf(toolNames, ToolSurface.CHAT);
        return new TaskRunner.Request(runId, ExecStore.KIND_AGENT, null, title, null,
                messages(prompts.resolve(SubagentBlock.KEY), instruction, hubId), toolSchemas,
                (call, execId) -> toolbox.run(call, "agent"), parentTaskId, sessionId, agentId, payload);
    }

    // ---------- 派一个子代理：对话侧（非阻塞） ----------

    /**
     * 派一个子代理去后台干活（对话侧）：建一条任务并落一行执行留痕拿到 id，
     * 立刻回一张受理回执；真正的 LLM 往返交给后台线程，跑完把结论投回会话。
     *
     * <p>这是「主会话不被子代理占住」的落点：回执当场就回，模型这一轮该答什么答什么、
     * 也可以直接结束本轮；结论晚些以一条新消息的形式回到同一个会话里（见 {@link #deliver}）。
     *
     * @param runId 归属批次；null 表示自己开一批（此时也由自己收批，见下）
     * @param hubId 结论投回的会话
     */
    private String delegateAsync(JsonNode args, String agentId, String runId, String hubId) {
        String instruction = args.path("instruction").asText("").trim();
        if (instruction.isEmpty()) {
            return "缺少 instruction：子代理看不到当前对话，请把要它查什么、产出什么写清楚。";
        }
        String batchId = runId;
        if (batchId == null) {
            batchId = openRun(agentId, "chat");
            /* 自己开的一批得自己收：调用方没机会在 finally 里收它，跑完就由这一批的件数归零来收 */
            closeRequested.add(batchId);
        } else if (store.execsByRun(batchId).size() >= MAX_PER_BATCH) {
            return "这一批派出的子代理已达上限（" + MAX_PER_BATCH + " 个）："
                    + "请等它们出结论，或把活合并成更少的几件。";
        }
        long execId = spawn(args, agentId, batchId, hubId, null);
        return receipt(execId, title(args.path("name").asText(""), instruction));
    }

    /**
     * 重跑一条任务（页面上的「重跑」）：按它留下的 payload（原始工具调用参数）重新派一次子代理。
     *
     * <p>典型场景是进程重启把在途任务打断（{@code status=interrupted}）后的手动重跑。新任务挂为
     * 原任务的子任务（{@code parent_id}），于是「重跑过几次」在任务树里可查。
     *
     * @return 新一次执行的 id；任务不存在 / 不是子代理 / 没有可重跑的输入时返回 -1
     */
    public long rerunTask(long taskId) {
        TaskStore.TaskRow task = tasks.task(taskId).orElse(null);
        if (task == null || !TaskStore.KIND_SUBAGENT.equals(task.kind()) || task.payload() == null) {
            return -1;
        }
        /* 幂等（STEP 4）：原任务还在跑就别重跑——重跑按钮点两下不该派两次同样的活。
           中断（interrupted）/ 失败（failed）的任务才可重跑，这正是「可恢复」的落点 */
        if (TaskStore.ST_RUNNING.equals(task.status()) || TaskStore.ST_PENDING.equals(task.status())) {
            return -1;
        }
        JsonNode args = readTree(task.payload());
        if (args.path("instruction").asText("").trim().isEmpty()) {
            return -1;
        }
        String batchId = openRun(task.agentId(), "chat");
        closeRequested.add(batchId);
        return spawn(args, task.agentId(), batchId, task.sessionId(), taskId);
    }

    /**
     * 把一件活真正派出去：建请求 → 先落执行留痕拿到 id（回执里给出的 id 即刻可用）→ 建任务行
     * 并绑上这次执行 → 交后台线程跑。首次派活与重跑共用它，差别只在 parentTaskId。
     *
     * @param parentTaskId 重跑时挂到原任务下（parent_id）；首次派活为 null
     * @return 这次执行的 id
     */
    private long spawn(JsonNode args, String agentId, String batchId, String hubId,
                       Long parentTaskId) {
        String instruction = args.path("instruction").asText("").trim();
        TaskRunner.Request req = request(batchId, agentId,
                title(args.path("name").asText(""), instruction),
                instruction, names(args.path("tools")), hubId, parentTaskId, hubId, args.toString());
        /* 开一次执行＝建那行任务：上下文、来源、归属会话、待重跑输入（工具调用参数原文）都在这一行里 */
        long execId = runner.openExec(req);
        pending.computeIfAbsent(batchId, k -> new AtomicInteger()).incrementAndGet();
        markRunning(hubId, execId);
        if (hubId != null && !hubId.isBlank()) {
            /* 记下这一批投回哪个会话：整批跑完时据此发「收批」事件，由主模型补一条整体总结 */
            batchHub.put(batchId, hubId);
        }
        POOL.execute(() -> {
            try {
                runAsync(req, execId, hubId);
            } finally {
                pending.computeIfAbsent(batchId, k -> new AtomicInteger()).decrementAndGet();
                settle(batchId);
            }
        });
        return execId;
    }

    /** 后台跑一次子代理：无论成败都把回声投回会话——不投，派活方就永远等不到它的消息 */
    private void runAsync(TaskRunner.Request req, long execId, String hubId) {
        TaskRunner.Outcome outcome;
        try {
            outcome = AgentScope.run(req.agentId(), () -> runner.run(req, execId));
        } catch (RuntimeException e) {
            log.warn("子代理「{}」执行失败：{}", req.title(), e.getMessage());
            /* 引擎中途抛错时那行任务还停在 running：这里补一笔，免得页面上永远显示「进行中」 */
            store.finishExec(execId, "failed", null, null, null, e.getMessage());
            outcome = new TaskRunner.Outcome(execId, "failed", null, e.getMessage());
        } finally {
            /* 执行既然结束了就先注销「在跑」：投回结论时还要跑摘要与秘书总结两次模型调用，
               不该让主模型在这段时间里仍被告知这件活没回来 */
            unmarkRunning(hubId, execId);
        }
        /* 结论沉淀进本会话的共享黑板：同一会话里后面再派的子代理开工时会读到它（接力，不重复查） */
        scratchpad.append(hubId, req.title(), execId, outcome.output());
        try {
            deliver(hubId, req.title(), execId, outcome);
        } catch (RuntimeException e) {
            log.warn("子代理「{}」结论投回会话失败：{}", req.title(), e.getMessage());
        }
    }

    /**
     * 结论投回派它的那个会话：把「谁、跑出了什么」发出去，由 {@code ChatService} 落一条结论气泡
     * （只显示标题+摘要，点开看全文）。主模型的秘书式总结不在这里——那要等整批都跑完，
     * 由 {@link #settle} 发收批事件时做一次（一次输入派多个子代理，也只总结一次）。
     *
     * <p>这一条与主对话自己的答复地位相同：后续轮次照常把它带进上下文，
     * 于是「子代理查到的」也能被主模型接着用。
     */
    private void deliver(String hubId, String title, long execId, TaskRunner.Outcome outcome) {
        if (hubId == null || hubId.isBlank()) {
            return;
        }
        publisher.publishEvent(new SubagentReportEvent(hubId, title, execId, outcome.status(),
                outcome.output(), outcome.error()));
    }

    /**
     * 受理回执：活已派出、结论晚些作为一条新消息回来——别在这儿等它。
     *
     * <p>这段话是写给模型看的，它决定模型下一步是「接着答别的 / 收尾」还是「傻等」。
     */
    private String receipt(long execId, String title) {
        return "已受理：子代理「" + title + "」正在后台执行（执行 id=" + execId + "）。"
                + "结论跑完会作为一条新消息投回本会话并打未读点，你不必现在等它，"
                + "可以先答复能答的部分或直接结束本轮。"
                + "如需核对它中途的检索过程，可调用 " + ReadSubagentRunTool.NAME
                + "（id=" + execId + "）。";
    }

    // ---------- 在跑的子代理（供每轮注入请求方上下文） ----------

    /**
     * 本会话在跑的子代理 → 注入主模型每轮上下文的提示块；没有在跑的返回空串。
     *
     * <p>对齐的是「请求方每轮都看得见子任务在跑、跑到哪一步」：模型因此能在答复里主动交代进度、
     * 也能自己判断要不要等，而不是派完就当没这回事。
     *
     * <p>按执行 id 排序——同一会话的进度块不该因为集合顺序而每轮抖动。
     */
    @Override
    public String activeBlock(String hubId) {
        Set<Long> ids = hubId == null ? null : running.get(hubId);
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        List<Long> ordered = new ArrayList<>(ids);
        ordered.sort(null);
        StringBuilder items = new StringBuilder();
        int shown = 0;
        for (Long id : ordered) {
            ExecStore.ExecRow row = store.exec(id).orElse(null);
            if (row == null) {
                continue;
            }
            shown++;
            items.append("\n- id=").append(id)
                    .append('「').append(text(row.title())).append('」').append(stage(row));
        }
        if (shown == 0) {
            return "";
        }
        return "【后台子代理】本会话有 " + shown + " 件活派出去还没回来，结论会各自作为"
                + "一条新消息投回本会话，不必现在等它们：" + items;
    }

    /** 进度摘要：跑了多久、调了几次工具、最近一次调的是什么（靠每轮增量落库，跑到中途也读得到） */
    private String stage(ExecStore.ExecRow row) {
        long secs = Math.max(0, (System.currentTimeMillis() - row.startedAt()) / 1000);
        String elapsed = secs < 60 ? secs + "s" : (secs / 60) + "m" + (secs % 60) + "s";
        int calls = 0;
        String last = null;
        for (ChatDtos.TraceSegment seg : trace(row.trace())) {
            if (ChatTrace.SEG_TOOL.equals(seg.type())) {
                calls++;
                last = seg.name();
            }
        }
        return calls == 0 ? "，已跑 " + elapsed + "，刚开始"
                : "，已跑 " + elapsed + "，已调 " + calls + " 次工具，最近 " + last;
    }

    /** 落库的过程分段反序列化；无 / 解析失败退化为空清单（只影响进度描述的详略） */
    private List<ChatDtos.TraceSegment> trace(String json) {
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
     * 登记一件在跑的活：结论投回后注销。
     *
     * <p>刻意只放内存里：进程重启时这些后台线程本来也没了，登记表为空才是那一刻的真实状态——
     * 若改从库里的 {@code status='running'} 推断，重启后会残留一堆早已不存在的「幻影任务」。
     */
    private void markRunning(String hubId, long execId) {
        if (hubId == null || hubId.isBlank()) {
            return;
        }
        running.computeIfAbsent(hubId, k -> ConcurrentHashMap.newKeySet()).add(execId);
    }

    private void unmarkRunning(String hubId, long execId) {
        Set<Long> ids = hubId == null ? null : running.get(hubId);
        if (ids == null) {
            return;
        }
        ids.remove(execId);
        if (ids.isEmpty()) {
            running.remove(hubId, ids);
        }
    }

    /**
     * 子代理的上下文：system = 子代理人格（{@code systemBase}）+ 本会话共享黑板 + 当前时间；
     * user = 派它的人写的那段交代。没有任何历史——它看不到当前对话、也没有上一轮记忆。
     *
     * <p>system 的拼装交给上下文平面：人格是基底，黑板与当前时间由 {@code SubagentContextProvider}
     * 按 {@link ContextRequest.Surface#SUBAGENT} 面追加（见 STEP 6-③）。这里只把 user 那条交代补上。
     */
    private ArrayNode messages(String systemBase, String instruction, String hubId) {
        ObjectNode body = context.build(new ContextRequest(null, systemBase, hubId,
                false, null, 0, null, ContextRequest.Surface.SUBAGENT));
        ArrayNode messages = (ArrayNode) body.get("messages");
        messages.addObject().put("role", "user").put("content", instruction);
        return messages;
    }

    // ---------- 翻过程 ----------

    /** 按执行 id 取回任意一次执行（子代理 / 任务计划 / 心跳主会话）的完整过程 */
    private String replayTool(JsonNode args) {
        long id = args.path("id").asLong(0);
        ExecStore.ExecRow row = store.exec(id).orElse(null);
        if (row == null) {
            return "找不到执行 id=" + id + " 的记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(kindText(row.kind())).append('：').append(text(row.title()))
                .append("（").append(row.status()).append("）\n");
        sb.append("组装出的上下文：\n").append(clip(AgentRunner.pretty(om, row.context())))
                .append("\n\n");
        if (row.reasoning() != null && !row.reasoning().isBlank()) {
            sb.append("思维链：\n").append(clip(row.reasoning())).append("\n\n");
        }
        List<ExecStore.EditRow> edits = store.editsByExec(id);
        if (!edits.isEmpty()) {
            sb.append("对知识库的写入（").append(edits.size()).append(" 次）：\n");
            for (ExecStore.EditRow e : edits) {
                sb.append("- ").append(e.tool()).append(' ').append(clip(e.args())).append(" → ")
                        .append(clip(e.summary())).append('\n');
            }
            sb.append('\n');
        }
        sb.append("结论：\n").append(text(row.output()));
        return sb.toString();
    }

    // ---------- 助手 ----------

    /** 展示名：调用方给了就用它，否则从交代里截一段（让运行记录里认得出这是件什么活） */
    private static String title(String given, String instruction) {
        String name = given == null ? "" : given.trim();
        if (!name.isEmpty()) {
            return name;
        }
        String flat = instruction.replaceAll("\\s+", " ").trim();
        return flat.length() > TITLE_MAX ? flat.substring(0, TITLE_MAX) + "…" : flat;
    }

    /** 工具调用参数里的字符串数组 → 去重后的清单（坏数据退化为空清单=默认只读检索） */
    private static List<String> names(JsonNode node) {
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

    private static String kindText(String kind) {
        return switch (kind == null ? "" : kind) {
            case ExecStore.KIND_HEARTBEAT -> "心跳主会话";
            case ExecStore.KIND_PLAN -> "任务计划";
            default -> "子代理";
        };
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > REPLAY_LIMIT ? s.substring(0, REPLAY_LIMIT) + "…" : s;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
