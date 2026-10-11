package cc.ginpika.minis.agent.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cc.ginpika.minis.agent.chat.ChatTraceCodec;
import cc.ginpika.minis.ai.core.tool.ToolResult;
import cc.ginpika.minis.ai.core.tool.ToolValidator;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.config.HeartbeatProps;
import cc.ginpika.minis.stats.TokenUsageService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 执行引擎：跑完「一次独立上下文的执行」——组装好的 messages 进去，结论出来，过程全部留痕。
 *
 * <p>三种执行共用它，靠 {@code kind} 区分（见 {@link ExecStore}）：心跳主会话登场、任务计划被派出、
 * 子代理被当场交代。它们的差别只在「上下文怎么组装」与「工具调用落到谁身上」——
 * 前者由调用方自己造 messages，后者由调用方给的 {@link TaskRunner.Dispatch} 决定。
 *
 * <p><b>循环不在这里</b>：模型往返与工具批已收进 {@link AgentRuntime}（与首页对话共用同一条内核）。
 * 本类只负责三件事：组装请求体、把「逐轮进度落进 {@code task}」接到内核的过程回调上、
 * 把结论与过程落库（含任务产物与记忆投影）。
 *
 * <p>它是 {@link TaskRunner} 的默认实现（STEP 1 起不再自称「唯一引擎」，只是其中一种）：
 * 契约见接口，调用方按接口注入，后续后台队列 / 沙箱实现可以并存。
 *
 * <p>没有状态：给模型的 messages 每次现造，引擎自己不缓存任何东西（这正是「LLM 无状态、
 * 状态在库里」的落点）。工具往返上限见 {@code minis.heartbeat.max-tool-rounds}，
 * 最后一轮撤掉 tools 逼模型给出结论（见 {@link AgentRuntime}）。
 */
@Component
public class AgentRunner implements TaskRunner {

    private final HeartbeatProps props;
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
    /** 校验节点（STEP 4）：写入 / 执行类工具落地前过一道参数自检（只读放行） */
    private final ToolValidator validator;

    public AgentRunner(HeartbeatProps props, SettingsService settings, AgentRuntime runtime,
                       ExecStore execs, TaskStore tasks, ChangeSetStore changes, ObjectMapper om,
                       MemoryWriter memory, ToolValidator validator) {
        this.props = props;
        this.settings = settings;
        this.runtime = runtime;
        this.execs = execs;
        this.tasks = tasks;
        this.changes = changes;
        this.om = om;
        this.memory = memory;
        this.validator = validator;
    }

    /** 跑一次执行：落 exec 行 → 多轮工具往返 → 落结论与过程 */
    @Override
    public Outcome run(Request req) {
        return run(req, openExec(req));
    }

    /**
     * 预登记一次执行：把「这次执行」那行任务建出来（带上下文与来源，状态 running），返回它的 id。
     *
     * <p>异步派活（对话侧的子代理）走这条路——调用方要<b>立刻</b>拿到 id 回一张受理回执，
     * 不能等这次执行整个跑完才拿到；后台跑起来与回执无关，两边只在那一行上相遇。
     */
    @Override
    public long openExec(Request req) {
        return tasks.beginExec(req.kind(), req.runId(), req.planId(), req.parentId(),
                req.sessionId(), req.agentId(), req.title(), req.payload(), req.messages().toString());
    }

    /** 跑一次已登记的执行（execId 由 {@link #openExec} 给出）：多轮工具往返 → 落结论与过程 */
    @Override
    public Outcome run(Request req, long execId) {
        ModelTarget target = settings.resolveChatModel(req.modelId());
        if (!target.configured()) {
            execs.finishExec(execId, "failed", null, null, null, "LLM 未配置");
            return new Outcome(execId, "failed", null, "LLM 未配置");
        }

        /* 请求体：model + messages（内核就地往它追加工具往返）+ tools；最后一轮由内核用去掉 tools 的副本 */
        ObjectNode body = om.createObjectNode();
        body.put("model", target.model());
        body.set("messages", req.messages().deepCopy());
        if (req.toolSchemas() != null && !req.toolSchemas().isEmpty()) {
            body.set("tools", req.toolSchemas());
        }
        AgentRuntime.RunState state = new AgentRuntime.RunState();
        int maxRounds = Math.max(0, props.getMaxToolRounds());

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
                maxRounds, listener, state));

        if (result.end() != AgentRuntime.End.ANSWERED) {
            execs.finishExec(execId, "failed", state.reasoning(),
                    ChatTraceCodec.writeTrace(om, state.trace()),
                    ChatTraceCodec.writeRounds(om, state.toolRounds()), "未产出结论");
            return new Outcome(execId, "failed", null, "未产出结论");
        }
        String output = result.content();
        execs.finishExec(execId, "done", state.reasoning(),
                ChatTraceCodec.writeTrace(om, state.trace()),
                ChatTraceCodec.writeRounds(om, state.toolRounds()), null);
        artifact(execId, req.title(), output);
        memory.onExecDone(execId, req.kind(), req.title(), output);
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
