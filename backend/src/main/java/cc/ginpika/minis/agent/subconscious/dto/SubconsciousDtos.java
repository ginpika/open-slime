package cc.ginpika.minis.agent.subconscious.dto;

import java.util.List;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 潜意识模块请求/响应对（subconscious/* ↔ 前端）：心跳主会话、任务计划、执行留痕与资源使用 */
public final class SubconsciousDtos {

    /** 插槽绑定：正文里的 {{slot}} 交给哪个注入器从存储层现取内容填充；args 为注入器自己的参数
     *  （如条数、文档 id、实体名），无参数留空 */
    public record InjectorBinding(String slot, String injector, String args) {
    }

    /** 任务计划（一份常驻编制）：自带正文 + 节拍 + 交付渠道；channels 见 SubconsciousService.CHANNELS。
     *  由大模型经工具自行立/改/撤，页面只读；没有「启用」开关：派不派完全由心跳主会话看着名册决定 */
    public record PlanView(String id, String name, String role, String content, List<String> tools,
                           List<InjectorBinding> injectors, List<String> channels,
                           Long intervalMs, String input, String modelId,
                           int sort, Long lastBeatAt) {
    }

    /** 一批执行：一次心跳（beat）/ 页面点名（manual）/ 一次对话委托（chat） */
    public record RunView(String id, long beat, String trigger, String status, int agentCount,
                          String note, long startedAt, Long finishedAt) {
    }

    /**
     * 一次执行（独立上下文）：kind 决定 planId 与 title 的含义——
     * heartbeat=心跳主会话登场这一拍；plan=某份编制被派出；agent=一个匿名子代理被当场交代。
     *
     * <p>context=组装出的完整 LLM 上下文（messages JSON，仅详情接口返回）；
     * trace=过程分段（与 chat 同构）；edits=对知识库的写入清单。
     */
    public record ExecView(long id, String runId, String kind, String planId, String title,
                           String status, String context, String output, String reasoning,
                           List<ChatDtos.TraceSegment> trace, List<EditView> edits, String error,
                           long startedAt, Long finishedAt) {
    }

    /** 写库清单项：一次执行里的某次写库工具调用（args=调用参数，summary=工具返回） */
    public record EditView(long id, String tool, String args, String summary, long createdAt) {
    }

    /** 一次心跳/派活的结果：started=false 表示已有另一轮在跑（或目标不存在），本次被跳过 */
    public record BeatView(boolean started, String runId, long beat, String trigger, int agents) {
    }

    /** 心跳开关与节拍（页面可改，不改配置文件） */
    public record HeartbeatRequest(Boolean enabled, Long tickMs) {
    }

    /** 心跳主会话（总线）的上下文：正文（含 {{插槽}}）+ 插槽绑定 + 模型；没有「本次交代」这一层 */
    public record HeartbeatView(String content, List<InjectorBinding> injectors, String modelId) {
    }

    /** 保存心跳主会话的上下文（单行整体覆盖写） */
    public record HeartbeatContextRequest(String content, List<InjectorBinding> injectors,
                                          String modelId) {
    }

    /** 观测台状态：心跳开关/节拍/是否正在跑 + 渠道、注入器清单（heartbeat 页编辑插槽绑定用） */
    public record StateView(boolean heartbeatEnabled, long tickMs, boolean running,
                            List<String> channels, List<String> injectors) {
    }

    /** 插槽词典的一条：注入器名 + 它取什么、参数怎么给、从哪儿取 */
    public record InjectorInfoView(String name, String summary, String args, String source) {
    }

    /** 插槽词典：全部可用注入器（「插槽」子页只读展示） */
    public record InjectorGuideView(List<InjectorInfoView> injectors) {
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

    /** 观测台的一份快照（首屏拉取与 SSE 推送同一结构）：状态 + 最近几批（倒序，各带本批执行） */
    public record PulseView(StateView state, List<RunBundle> recentRuns) {
    }

    // ---------- RAG 召回留痕（「RAG 召回」子页的主体） ----------

    /**
     * 一条 RAG 召回留痕：三路召回工具调用的一次记录。
     *
     * <p>channel=三路之一（vector / graph / memory）；tool=工具名；source=来自哪条流
     * （chat / multiagent / heartbeat / plan / agent）；query=调用参数原文；
     * result=回给模型的 tool 消息原文——也就是<b>大模型这次实际拿到的原始数据</b>；
     * resultChars 为截断前的原始字符数。
     */
    public record RagRecallView(long id, String channel, String tool, String source, String query,
                                String result, long resultChars, long createdAt) {
    }

    /** 清空 RAG 召回留痕的结果：removed=删除条数 */
    public record RagRecallCleared(int removed) {
    }

    // ---------- 资源使用视图（子代理页的主体） ----------

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
     * 子代理页的一份资源快照：名册规模 + 窗口内的执行总量（分三档：全部 / 子代理 / 计划 / 心跳主会话）
     * + 逐份编制的用量。
     *
     * <p>数据全部聚自 {@code sub_exec} / {@code sub_plan}，不新增表；
     * 名册里还没跑过的编制也会出现（runs=0），页面据此显示「尚未被派过」。
     */
    public record ResourceView(boolean heartbeatEnabled, long tickMs, boolean running,
                               long windowHours, int rosterCount,
                               UsageTotals totals, UsageTotals agents, UsageTotals plans,
                               UsageTotals heartbeat, List<PlanUsage> planUsage) {
    }

    private SubconsciousDtos() {
    }
}
