package cc.ginpika.minis.agent.heartbeat.dto;

import java.util.List;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 后台运行层的请求/响应对（heartbeat/* ↔ 前端）：<b>逐 Agent</b> 的心跳、常驻职责、执行留痕与资源使用。
 *
 * <p>「注入器」已退役：心跳与职责的视图不再有绑定项；素材由模型自己调工具现取。
 */
public final class HeartbeatDtos {

    /**
     * 常驻职责（某 Agent 的一份编制）：自带正文 + 节拍 + 交付渠道；channels 见 HeartbeatService.CHANNELS。
     * 由大模型经工具自行立/改/撤；页面只读，唯一的人工写入口是 {@code enabled} 这个刹车。
     * rationale=立它时给的一句「为什么立」，供人隔月回看名册时认由来；
     * enabled=false=已停用（心跳不派它、模型也看不见它，定义与执行留痕都保留）
     */
    public record PlanView(String id, String agentId, String name, String role, String content,
                           List<String> tools, List<String> channels, Long intervalMs, String input,
                           String modelId, int sort, Long lastBeatAt, String rationale,
                           boolean enabled) {
    }

    /** 启用 / 停用一份常驻职责（只挡自动派出，不删定义） */
    public record PlanEnabledRequest(Boolean enabled) {
    }

    /** 一批执行：某 Agent 的一次心跳（beat）/ 页面点名（manual）/ 一次对话委托（chat） */
    public record RunView(String id, String agentId, long beat, String trigger, String status,
                          int agentCount, String note, long startedAt, Long finishedAt) {
    }

    /**
     * 一次执行（独立上下文）：kind 决定 planId 与 title 的含义——
     * heartbeat=心跳主会话登场这一拍；plan=某份编制被派出；agent=一个匿名子代理被当场交代。
     *
     * <p>context=组装出的完整 LLM 上下文（messages JSON，仅详情接口返回）；
     * trace=过程分段（与 chat 同构）；edits=对知识库的写入清单。
     *
     * <p>taskId=这次执行归属的任务（{@code task.id}）；只有子代理列表会填，其余列表与详情为 null。
     */
    public record ExecView(long id, String runId, String kind, String planId, String title,
                           String status, String context, String output, String reasoning,
                           List<ChatDtos.TraceSegment> trace, List<EditView> edits, String error,
                           long startedAt, Long finishedAt, Long taskId) {
    }

    /** 重跑一条任务的结果：taskId=被重跑的原任务，execId=新一次执行的留痕 id */
    public record TaskRunView(long taskId, long execId) {
    }

    /**
     * 一条任务的只读视图（编排层一等公民）：kind 取值见 {@code TaskStore}
     * （session / subagent / plan / code）。
     *
     * <p>parentId=派它的那个任务（心跳 → 职责 → 子代理 这条链），顶层为 null；
     * agentId=归属 Agent；execId=它落到的执行留痕（{@code task.id}，可点开看全过程），
     * 仅「真正的执行」有值——前台会话任务没有执行上下文，此处为 null（行不可点）。
     */
    public record TaskView(long id, Long parentId, String kind, String status, String title,
                           String sessionId, String agentId, Long execId, long createdAt,
                           long updatedAt) {
    }

    /** 写库清单项：一次执行里的某次写库工具调用（args=调用参数，summary=工具返回） */
    public record EditView(long id, String tool, String args, String summary, long createdAt) {
    }

    /** 一次心跳/派活的结果：started=false 表示该 Agent 已有一拍在跑（或目标不存在），本次被跳过 */
    public record BeatView(boolean started, String runId, long beat, String trigger, int agents) {
    }

    /** 某 Agent 心跳的读写请求：各字段给 null 表示不改；tickMs 非正数忽略 */
    public record HeartbeatRequest(String content, String modelId, Boolean enabled, Long tickMs) {
    }

    /** 某 Agent 的心跳：正文（不含运行时数据）+ 模型 + 开关 + 节拍 + 上次真敲的时间 */
    public record HeartbeatView(String agentId, String content, String modelId, boolean enabled,
                                long tickMs, Long lastBeatAt) {
    }

    /** 某 Agent 心跳的运行时状态（全局观测台逐 Agent 列出）：名字 + 开关 / 节拍 / 上次敲 / 是否在跑 */
    public record HeartbeatState(String agentId, String name, boolean enabled, long tickMs,
                                 Long lastBeatAt, boolean running) {
    }

    /**
     * 工具词典的一条（「工具」子页只读展示）：名字 + 描述 + 所属工具面 + 写库/并行/子代理可调标记
     * + 参数 schema。
     */
    public record ToolInfoView(String name, String description, List<String> surfaces,
                               boolean writes, boolean agentsMayCall, boolean parallelizable,
                               ObjectNode parameters) {
    }

    /** 一批执行 + 这一批里的各次执行（观测台按批分组展示） */
    public record RunBundle(RunView run, List<ExecView> execs) {
    }

    /**
     * 观测台的一份快照（首屏拉取与 SSE 推送同一结构）：心跳状态（逐 Agent）+ 最近几批（倒序，各带本批执行）。
     *
     * <p>某 Agent 的后台页只带它自己那一行；全局观测台带全部「已启用」的心跳。
     */
    public record PulseView(List<HeartbeatState> heartbeats, List<RunBundle> recentRuns) {
    }

    // ---------- RAG 召回留痕（各 Agent 的「RAG 召回」子页主体） ----------

    /**
     * 一条 RAG 召回留痕：三路召回工具调用的一次记录。
     *
     * <p>channel=三路之一（vector / graph / memory）；tool=工具名；source=来自哪条流
     * （chat / multiagent / heartbeat / plan / agent）；agentId=归属 Agent；query=调用参数原文；
     * result=回给模型的 tool 消息原文——也就是<b>大模型这次实际拿到的原始数据</b>；
     * resultChars 为截断前的原始字符数。
     */
    public record RagRecallView(long id, String channel, String tool, String source, String agentId,
                                String query, String result, long resultChars, long createdAt) {
    }

    /** 清空 RAG 召回留痕的结果：removed=删除条数 */
    public record RagRecallCleared(int removed) {
    }

    // ---------- 资源使用视图 ----------

    /** 窗口内某一类的用量；estTokens 由上下文/结论/思维链的字符量粗估（量级参考） */
    public record UsageTotals(long runs, long done, long failed, long avgDurationMs,
                              long maxDurationMs, long toolRounds, long estTokens) {
    }

    /** 窗口内某份编制被派出的用量 */
    public record PlanUsage(String planId, String name, long runs, long done, long failed,
                            long avgDurationMs, long maxDurationMs, long toolRounds,
                            long estTokens, Long lastRunAt) {
    }

    /**
     * 某 Agent 后台的资源快照：名册规模 + 窗口内的执行总量（分四档：全部 / 子代理 / 职责 / 心跳主会话）
     * + 逐份编制的用量。
     *
     * <p>数据全部聚自 {@code task} / {@code heartbeat_plan}，不新增表；
     * 名册里还没跑过的编制也会出现（runs=0），页面据此显示「尚未被派过」。
     *
     * <p>idleSkips=自启动以来被预筛跳过的拍数（名册里没有到点的职责，就没叫模型）——
     * 页面用它显示「省下的空转」。
     */
    public record ResourceView(boolean enabled, long tickMs, boolean running, long windowHours,
                               int rosterCount, UsageTotals totals, UsageTotals agents,
                               UsageTotals plans, UsageTotals heartbeat, List<PlanUsage> planUsage,
                               long idleSkips) {
    }

    private HeartbeatDtos() {
    }
}