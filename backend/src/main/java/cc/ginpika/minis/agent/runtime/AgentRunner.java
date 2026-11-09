package cc.ginpika.minis.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import cc.ginpika.minis.agent.chat.ChatTraceCodec;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.ai.runtime.ModelLoop;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 执行引擎：跑完「一次独立上下文的执行」——组装好的 messages 进去，结论出来，过程全部留痕。
 *
 * <p>三种执行共用它，靠 {@code kind} 区分（见 {@link ExecStore}）：心跳主会话登场、任务计划被派出、
 * 子代理被当场交代。它们的差别只在「上下文怎么组装」与「工具调用落到谁身上」——
 * 前者由调用方自己造 messages，后者由调用方给的 {@link AgentRunner.Dispatch} 决定。
 *
 * <p><b>循环不在这里</b>：模型往返与工具批已收进 {@link AgentRuntime}（与首页对话共用同一条内核）。
 * 本类只负责三件事：组装请求体、把「逐轮进度落进 {@code task}」接到内核的过程回调上、
 * 把结论与过程落库（含任务产物与记忆投影）。
 *
 * <p>没有状态：给模型的 messages 每次现造，引擎自己不缓存任何东西（这正是「LLM 无状态、
 * 状态在库里」的落点）。工具能力持续保留，执行到回答、取消或失败（见 {@link AgentRuntime}）。
 */
@Component
public class AgentRunner {
    /**
     * 一次执行的输入。
     *
     * @param runId    归属批次（{@code heartbeat_run.id}）
     * @param kind     执行来源：心跳 / 计划 / 子代理（见 {@link ExecStore#KIND_HEARTBEAT} 等），
     *                 落进任务行的 {@code origin} 列——读侧按它分类
     * @param planId   {@code kind=plan} 时的编制 id（{@code heartbeat_plan.id}）；其余为 null
     * @param parentId 派它的那个任务（心跳 → 计划 → 子代理 这条链靠它连起来）；顶层执行传 null
     * @param sessionId 归属会话（子代理：结论要投回的那个会话）；无会话的执行传 null
     * @param agentId  归属 Agent（{@code agent.id}）：这次执行是哪个智能体产生的；可为 null
     * @param payload  重跑所需的原始输入（JSON）；无可重跑输入时为 null
     */
    public record Request(String runId, String kind, String planId, String title, String modelId,
                   ArrayNode messages, ArrayNode toolSchemas, Dispatch dispatch, Long parentId,
                   String sessionId, String agentId, String payload) {
    }

    /** 一次执行的结果：execId 可在留痕里回看这次执行的全过程 */
    public record Outcome(long execId, String status, String output, String error) {
    }

    /** 工具分发：把模型请求的一次调用落地成一条 tool 结果（不同调用方带不同的工具面） */
    public interface Dispatch {

        ToolResult run(JsonNode call, long execId);

        /** 多个调用同时到达时，本工具能否并行执行（默认不能：多数工具有顺序语义） */
        default boolean parallelizable(String name) {
            return false;
        }
    }

    private final SettingsService settings;
    /** Agent 内核：本次执行的循环由它跑 */
    private final AgentRuntime runtime;
    private final ExecStore execs;
    /** 任务：执行就是它那行（见 {@link TaskStore#beginExec}）；结论落成产物、写操作收进变更集 */
    private final TaskStore tasks;
    /** 变更集：写操作的「可 diff / 可回滚」结构（每次写工具调用收一条 item） */
    private final ChangeSetStore changes;
    private final ObjectMapper om;
    private final MemoryWriter memory;
    /** 记忆提炼入口（memory-v6 §7.3）：执行结论也进长期记忆——子代理 / 心跳的产出不再只躺在事件流里 */
    private final MemoryIngest memoryIngest;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.ai.memory.WorkingSetService workingSet;
    @org.springframework.beans.factory.annotation.Autowired
    private cc.ginpika.minis.ai.memory.store.MemoryItemStore memoryItems;
    /** 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（只读放行） */
    private final ToolValidator validator;

    public AgentRunner(SettingsService settings, AgentRuntime runtime,
                       ExecStore execs, TaskStore tasks, ChangeSetStore changes, ObjectMapper om,
                       MemoryWriter memory, MemoryIngest memoryIngest, ToolValidator validator) {
        this.settings = settings;
        this.runtime = runtime;
        this.execs = execs;
        this.tasks = tasks;
        this.changes = changes;
        this.om = om;
        this.memory = memory;
        this.memoryIngest = memoryIngest;
        this.validator = validator;
    }

    /** 跑一次执行：落 exec 行 → 多轮工具往返 → 落结论与过程 */
    public Outcome run(Request req) {
        return run(req, openExec(req));
    }

    /**
     * 预登记一次执行：把「这次执行」那行任务建出来（带上下文与来源，状态 running），返回它的 id。
     *
     * <p>异步派活（对话侧的子代理）走这条路——调用方要<b>立刻</b>拿到 id 回一张受理回执，
     * 不能等这次执行整个跑完才拿到；后台跑起来与回执无关，两边只在那一行上相遇。
     */
    public long openExec(Request req) {
        return tasks.beginExec(req.kind(), req.runId(), req.planId(), req.parentId(),
                req.sessionId(), req.agentId(), req.title(), req.payload(), req.messages().toString());
    }

    /** 跑一次已登记的执行（execId 由 {@link #openExec} 给出）：多轮工具往返 → 落结论与过程 */
    public Outcome run(Request req, long execId) {
        return runScopedToTask(req, execId, null);
    }

    /** A scoped delegation supplies its own request budget and retains its output as a task artifact. */
    public Outcome runDelegated(Request req, long execId, ModelLoop.RequestPreparation preparation) {
        return runScopedToTask(req, execId, java.util.Objects.requireNonNull(preparation));
    }

    private Outcome runScopedToTask(Request req, long execId, ModelLoop.RequestPreparation preparation) {
        var parent = cc.ginpika.minis.ai.core.MemoryScopeCtx.get();
        if (parent == null) { return runScoped(req, execId, preparation); }
        var scope = new cc.ginpika.minis.ai.core.MemoryExec(parent.userId(), parent.agentId(),
                parent.projectId(), parent.sessionId(), execId, parent.surface(), false,
                "task:" + execId, parent.identityOrInferred());
        return cc.ginpika.minis.ai.core.MemoryScopeCtx.run(scope, () -> runScoped(req, execId, preparation));
    }

    private Outcome runScoped(Request req, long execId, ModelLoop.RequestPreparation preparation) {
        ModelTarget target = settings.resolveChatModel(req.modelId());
        if (!target.configured()) {
            execs.finishExec(execId, "failed", null, null, null, "LLM 未配置");
            return new Outcome(execId, "failed", null, "LLM 未配置");
        }

        /* 请求体：model + messages（内核就地往它追加工具往返）+ tools；工具持续可用直到完成或停止 */
        ObjectNode body = om.createObjectNode();
        body.put("model", target.model());
        body.set("messages", req.messages().deepCopy());
        if (req.toolSchemas() != null && !req.toolSchemas().isEmpty()) {
            body.set("tools", req.toolSchemas());
        }
        AgentRuntime.RunState state = new AgentRuntime.RunState();

        /* 逐轮把这一轮产出就地落库：于是这条执行在跑的过程中也读得到进度——
           派它的人据此知道它跑到哪一步，不必等它出结论。这是本类特有的落点，循环本身在内核里 */
        AgentRuntime.Listener listener = new AgentRuntime.Listener() {
            @Override
            public void progress() {
                execs.updateProgress(execId, state.reasoning(),
                        ChatTraceCodec.writeTrace(om, state.trace()),
                        ChatTraceCodec.writeRounds(om, state.toolRounds()));
            }
        };

        AgentRuntime.Result result = runtime.run(new AgentRuntime.Spec(target,
                TokenUsageService.SOURCE_HEARTBEAT, body,
                new AgentRuntime.ToolDispatch() {
                    @Override
                    public String run(JsonNode call) {
                        return dispatch(req.dispatch(), call, execId);
                    }

                    @Override
                    public boolean parallelizable(String name) {
                        return req.dispatch().parallelizable(name);
                    }
                },
                listener, state, null, preparation));

        if (result.end() != AgentRuntime.End.ANSWERED) {
            execs.finishExec(execId, "failed", state.reasoning(),
                    ChatTraceCodec.writeTrace(om, state.trace()),
                    ChatTraceCodec.writeRounds(om, state.toolRounds()), "未产出结论");
            return new Outcome(execId, "failed", null, "未产出结论");
        }
        String output = result.content();
        artifact(execId, req.title(), output);
        execs.finishExec(execId, "done", state.reasoning(),
                ChatTraceCodec.writeTrace(om, state.trace()),
                ChatTraceCodec.writeRounds(om, state.toolRounds()), null);
        if (preparation != null) {
            return new Outcome(execId, "done", output, null);
        }
        memory.onExecDone(execId, req.kind(), req.title(), output);
        /* memory-v6 §7.3：事件之外再提炼一次条目——子代理 / 心跳 / 职责的结论因此能进长期记忆
           （作用域由执行入口圈定；没有作用域则自动跳过，见 MemoryIngest#tapExec） */
        memoryIngest.tapExec(req.title(), output);
        // 独立执行确实结束：仅收尾该任务的工作集，不蒸馏派发方整个会话。
        var scope = cc.ginpika.minis.ai.core.MemoryScopeCtx.get();
        if (scope != null && workingSet != null && memoryItems != null) {
            var taskScope = new cc.ginpika.minis.ai.core.MemoryExec(scope.userId(), scope.agentId(),
                    scope.projectId(), null, execId, scope.surface(), false, scope.turnId(), scope.identityOrInferred());
            try {
                memoryItems.closeTask(taskScope);
                workingSet.distill(taskScope);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(AgentRunner.class).warn("任务记忆收尾失败，保留工作状态：{}", e.getMessage());
            }
        }
        return new Outcome(execId, "done", output, null);
    }

    /** 落地一次调用：写库类工具的调用收进变更集（旧表 sub_exec_edit 已退役） */
    private String dispatch(Dispatch dispatch, JsonNode call, long execId) {
        /* 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（只读放行），拒了不落执行 */
        String invalid = validator.validate(call.path("function").path("name").asText(""),
                call.path("function").path("arguments").asText(""));
        if (invalid != null) {
            return invalid;
        }
        ToolResult outcome = dispatch.run(call, execId);
        if (outcome.write()) {
            /* 写操作收进变更集（一次执行一个 changeset，每条写操作一个 item）——
               它就是「写库清单」的正本，页面按 exec 读它（见 ExecStore#editsByExec） */
            changes.record(execId, outcome.name(), outcome.args(), outcome.content());
        }
        return outcome.content();
    }

    /**
     * 结论落成一条任务产物（{@link TaskStore#ART_TEXT}）：任务行说「谁在跑、过程如何」，
     * 产物说「跑出了什么」。
     *
     * <p>执行就是那行任务（见 {@link TaskStore#beginExec}），所以 execId 就是 taskId。
     */
    private void artifact(long execId, String title, String output) {
        if (output == null || output.isBlank()) {
            return;
        }
        tasks.addArtifact(execId, execId, TaskStore.ART_TEXT, title, output, null);
    }

    /** 落库的 messages JSON 反序列化回来做缩进展示；解析失败原样返回（供 read_subagent_run 用） */
    public static String pretty(ObjectMapper om, String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return om.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(om.readValue(json, new TypeReference<List<JsonNode>>() {
                    }));
        } catch (Exception e) {
            return json;
        }
    }
}
