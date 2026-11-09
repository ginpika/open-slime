package cc.ginpika.minis.agent.chat;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 子代理工具的宿主：会话侧只认识这个口子，不认识子代理模块本身。
 *
 * <p>对话工具面（CHAT）里挂了两个子代理工具——
 * 派活（delegate_subagent）与翻过程（read_subagent_run），执行要落到「子代理」模块，
 * 由 {@link ChatToolDispatcher} 在工具分发时转交过来。
 * 抽成接口是为了让依赖方向单向：子代理模块依赖 chat 包（会话编排与工具执行口），
 * 而 chat 包不反过来依赖子代理模块。
 *
 * <p>与 {@link HeartbeatTool}（任务计划与心跳那套）分开：子代理是匿名的临时 worker，
 * 计划是常驻编制，两者的工具语义不该混在一个宿主里。
 */
public interface SubagentTool {

    /**
     * 执行一次子代理工具调用（<b>心跳侧</b>），返回给模型的 tool 消息内容；非子代理工具返回「未知工具」。
     *
     * <p>这里派活是<b>阻塞</b>的：心跳主会话是后台的总线，它派活是为了拿结论继续裁量这一拍还派谁，
     * 交不出结论它就白跑一拍。对话侧不走这里（见 {@link #runChatTool}）。
     *
     * @param call         模型发起的那次调用
     * @param agentId      归属 Agent（{@code agent.id}）：这次派活是替哪个 Agent 干的（落进子代理任务行）
     * @param runId        这次执行归属的批次（{@code heartbeat_run.id}）；调用方已有批次就复用，
     *                     传 null 表示由子代理自己开一批
     * @param trigger      批次的来源标签（beat / manual / chat），仅在 runId 为 null 时用于开新批
     * @param parentTaskId 派它的那个任务（心跳主会话 / 计划）；用来把子代理挂到任务树上，顶层传 null
     */
    String runTool(String agentId, JsonNode call, String runId, String trigger, Long parentTaskId);

    /**
     * 执行一次子代理工具调用（<b>对话侧</b>），返回给模型的 tool 消息内容。
     *
     * <p>这里派活是<b>非阻塞</b>的：{@code delegate_subagent} 当场只回一张受理回执（含执行 id），
     * 真正的 LLM 往返交给后台线程；跑完把结论作为一条新 assistant 消息投回 {@code hubId} 会话
     * 并打未读点。于是主会话不必挂在那儿等子代理——它先答能答的、或直接结束本轮，
     * 结论到了再以新消息的形式出现在同一个会话里。
     *
     * <p>{@code read_subagent_run} 仍是当场返回（读一条已落库的记录，没有可等待的过程）。
     *
     * @param agentId 归属 Agent（{@code agent.id}）：这次派活是替哪个 Agent 干的（落进子代理任务行）
     * @param runId 归属批次；通常由调用方先 {@link #openRun} 开好，null 表示由子代理自己开一批
     * @param hubId 结论投回的会话 id（对话侧必有）；用于把「派给了谁」与「回报给谁」对上
     */
    String runChatTool(String agentId, JsonNode call, String runId, String hubId);

    /** Personal-chat pilot dispatch, using the parent execution scope bound by the runtime. */
    String runScopedChatTool(String agentId, JsonNode call, String runId, String hubId, String actorId);

    /**
     * 本会话还在跑的子代理 → 一段注入主模型每轮上下文的提示块；没有在跑的返回空串。
     *
     * <p>这是「请求方看得见子任务在跑」的落点：派出去之后，请求方每一轮都能看到还有几件活
     * 没回来、各自跑到哪一步，于是它能在答复里主动交代进度、也能自己判断要不要等。
     * 缺了它，派完就当没这回事——回执给的是「已受理」，不是「进行中」。
     */
    String activeBlock(String hubId);

    /**
     * 开一批：调用方（主对话）一轮里派出的多个子代理共属一批，
     * 页面的「最近几批」不会被逐个调用刷屏。
     *
     * @param agentId 归属 Agent（{@code agent.id}）：这一批是替哪个 Agent 派出去的
     */
    String openRun(String agentId, String trigger);

    /**
     * 收一批：状态按批内各次执行的结果定。
     *
     * <p>对话侧派出去的子代理在后台跑，收批因此是<b>延迟</b>的：批里还有没回报的执行时，
     * 这里只记下「该收了」，等最后一件跑完由它来收——否则一批刚派出去就会被判成 failed。
     */
    void closeRun(String runId);
}
