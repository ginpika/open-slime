package cc.ginpika.minis.ai.core.prompt;

import cc.ginpika.minis.ai.core.prompt.block.AnalyzeBlock;
import cc.ginpika.minis.ai.core.prompt.block.ChunkSummaryBlock;
import cc.ginpika.minis.ai.core.prompt.block.ChunkerBlock;
import cc.ginpika.minis.ai.core.prompt.block.ChatGuidanceBlock;
import cc.ginpika.minis.ai.core.prompt.block.CoordinationBlock;
import cc.ginpika.minis.ai.core.prompt.block.CommitBlock;
import cc.ginpika.minis.ai.core.prompt.block.DocSummaryBlock;
import cc.ginpika.minis.ai.core.prompt.block.IdentityBlock;
import cc.ginpika.minis.ai.core.prompt.block.QuestionsBlock;
import cc.ginpika.minis.ai.core.prompt.block.SubagentBlock;
import cc.ginpika.minis.ai.core.prompt.block.SubagentSummaryBlock;
import cc.ginpika.minis.ai.core.prompt.block.TerminalBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 提示词库：上下文与大类的唯一定义点（注册表）。
 *
 * <p><b>块 + 上下文</b>——一次 LLM 调用的最终提示词由若干「块」（{@link PromptBlock}，每块一个类，
 * 内置正文写死在块类里）按序拼成，块与块的组合关系在这里按上下文声明（如对话 = 身份 + 增强，
 * parts 的注册顺序即缺省拼装顺序）。本子系统之外的提示词文本只剩一处刻意的例外：
 * 某 Agent 心跳主会话的正文（{@code HeartbeatPrompts.HEARTBEAT}）——它存在 {@code heartbeat} 行里、
 * 由该 Agent 自己维护，因此不纳入本页的版本管理。
 *
 * <p><b>可覆盖</b>——内置正文只是缺省值：用户可在「潜意识 · 提示词」页为任意块保存多份版本
 * （{@code prompt_version}），并选定一份覆盖内置（{@code prompt_binding}）。解析与组装由
 * {@link PromptComposer} 完成、经 {@code PromptManager} 对外提供；本包保持纯定义、不碰运行时状态。
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    // ---------- 上下文注册表 ----------

    /** 代码控制的运行时素材块（只读、不可拖拽）：说明执行时这里会被填入什么 */
    public record RuntimeBlock(String key, String name, String description) {
    }

    /**
     * 一个场景上下文 = 可拖拽排序的提示词块（parts，块 key 按缺省拼装顺序）+
     * 代码控制的运行时素材（runtime，恒在 parts 之后）。
     *
     * <p>互斥的用法各自成一个上下文（提示词页各占一个 tab）：只有真正拼在同一次调用里的
     * 提示词块才放进同一个上下文——目前只有「对话」是组合型的（身份 + 增强）。
     */
    public record PromptContext(String key, String name, String description,
                                List<String> parts, List<RuntimeBlock> runtime) {
    }

    /**
     * 一个大类：提示词页的一级标签，把用途相近的上下文收在一起。
     *
     * <p>两类是「面向人 / 面向系统」的划分：{@link #CAT_CHAT} 是用户直接对话时用的，
     * {@link #CAT_DEV} 是文档→图谱流水线上的自动环节（与工具、知识图谱强相关）。
     * {@link #CAT_ROUNDTABLE} 是第三类，且是唯一的<b>局部大类</b>：它只在智能体内部页出现，
     * 提示词页（首页对话）不展示（见 {@link PromptComposer#view()}）——圆桌上的成员不是首页那个
     * agent，这套上下文对它才有意义。
     */
    public record PromptCategory(String key, String name, String description,
                                 List<String> contextKeys) {
    }

    /** 上下文 key（顺序即提示词页 tab 顺序） */
    public static final String CTX_CHAT = "chat";
    /** 子代理：被派出去的匿名 worker 的 system（独立于对话，不看对话历史） */
    public static final String CTX_SUBAGENT = "subagent";
    /** 子代理回报摘要：把交回的结论压成气泡卡片（标题 + 摘要）那次调用的 system */
    public static final String CTX_SUBAGENT_SUMMARY = "subagent_summary";
    public static final String CTX_ANALYZE = "analyze";
    public static final String CTX_COMMIT = "commit";
    public static final String CTX_QUESTIONS = "questions";
    public static final String CTX_CHUNK_SUMMARY = "chunk_summary";
    public static final String CTX_CHUNKER = "chunker";
    public static final String CTX_DOC_SUMMARY = "doc_summary";
    /** 圆桌：成员在圆桌上说话时的 system（协作规程）与调度文书（公开记录 + 此刻局面） */
    public static final String CTX_ROUNDTABLE = "roundtable";

    /** 大类 key（顺序即提示词页一级标签顺序） */
    public static final String CAT_CHAT = "chat";
    public static final String CAT_DEV = "dev";
    /** 局部大类：只在智能体内部页出现（圆桌是全员共用的一套协作机制，不属于首页对话） */
    public static final String CAT_ROUNDTABLE = "roundtable";

    /** 运行时素材块：历史对话 / 当前输入 */
    private static final String RT_HISTORY = "history";
    private static final String RT_INPUT = "input";

    private static RuntimeBlock history() {
        return new RuntimeBlock(RT_HISTORY, "历史对话",
                "代码控制：当前会话已落库的历史消息按时间顺序注入（role + 正文）；"
                        + "开启 context_trace 的会话还会把当时的思考与工具往返一并回放。");
    }

    private static RuntimeBlock input(String what) {
        return new RuntimeBlock(RT_INPUT, "当前输入", what);
    }

    private static final List<PromptContext> CONTEXTS = List.of(
            new PromptContext(CTX_CHAT, "对话",
                    "一次对话调用的 system 由下列提示词块拼成，可拖拽调整先后；"
                            + "其后是代码控制的历史对话与本次输入。",
                    List.of(IdentityBlock.KEY, ChatGuidanceBlock.KEY, TerminalBlock.KEY),
                    List.of(history(), input("代码控制：用户这一次发送的问题。"))),
            new PromptContext(CTX_ROUNDTABLE, "圆桌",
                    "成员在圆桌上说话时的 system：它自己的人格（上一个分类那套）之后接这段协作规程，"
                            + "再往下是调度文书。协作规程逐成员一份（存在该成员的 agent_prompt_* 里），"
                            + "没配的位回退全局这一份。",
                    List.of(CoordinationBlock.KEY),
                    List.of(input("代码控制：公开记录（<transcript>）。本场话题的全部往来——"
                                    + "谁说了什么、圆桌上发生过什么动作（入场 / 转沉默 / 提议结束……），"
                                    + "按时间序；太长时只带最近若干条。"),
                            input("代码控制：此刻局面（<situation>）。话题进行到哪一步、"
                                    + "这位成员自己是什么状态、在场每个人什么状态。"))),
            new PromptContext(CTX_SUBAGENT, "子代理",
                    "被派出去的匿名子代理的 system：它看不到这次对话，只拿到一段交代，"
                            + "跑完只把结论回流给派它的人。改这里就等于改所有子代理的「人格」。",
                    List.of(SubagentBlock.KEY),
                    List.of(input("代码控制：派它的人写的那段交代（instruction），以及当前时间。"))),
            new PromptContext(CTX_SUBAGENT_SUMMARY, "子代理回报摘要",
                    "子代理跑完结论后，把它压成「标题 + 摘要」气泡卡片的那次调用：结论全文进去、"
                            + "一张 JSON 卡片出来（气泡只显示这两样，点开才看全文）。",
                    List.of(SubagentSummaryBlock.KEY),
                    List.of(input("代码控制：子代理交回的结论全文（未跑出结论时不走这次调用）。"))),
            new PromptContext(CTX_ANALYZE, "知识提取",
                    "文档分析 · 步骤 2：一次抽取调用 = 该提示词 + 待抽取文本。",
                    List.of(AnalyzeBlock.KEY),
                    List.of(input("代码控制：本次待抽取的正文。"))),
            new PromptContext(CTX_COMMIT, "确认入库",
                    "把三元组逐条写入图谱；当前工作流已改由后端直接写库，此项保留备用。",
                    List.of(CommitBlock.KEY),
                    List.of(input("代码控制：编号的三元组清单（subject | predicate | object）。"))),
            new PromptContext(CTX_QUESTIONS, "假设性问题",
                    "文档分析 · 步骤 3：为 chunk 生成检索时可用的假设性问题。",
                    List.of(QuestionsBlock.KEY),
                    List.of(input("代码控制：chunk 上下文（父文档标题 + 文档类型 <docType> + 全文概述 + chunk 正文）。"))),
            new PromptContext(CTX_CHUNK_SUMMARY, "段落摘要",
                    "文档分析 · 步骤 3：为 chunk 生成一句话摘要，供 RAG 语义定位。",
                    List.of(ChunkSummaryBlock.KEY),
                    List.of(input("代码控制：chunk 上下文（父文档标题 + 文档类型 <docType> + 全文概述 + chunk 正文）。"))),
            new PromptContext(CTX_CHUNKER, "文本切分器",
                    "文档处理：超长段落（>1200 字）按语义边界切分时使用。",
                    List.of(ChunkerBlock.KEY),
                    List.of(input("代码控制：待切分的超长段落原文。"))),
            new PromptContext(CTX_DOC_SUMMARY, "文档摘要与分类",
                    "文档处理：生成全文概述并判定文档类型。",
                    List.of(DocSummaryBlock.KEY),
                    List.of(input("代码控制：本次处理的文档全文。"))));

    /** 两大类：面向人的对话，与面向系统内部的开发者选项 */
    private static final List<PromptCategory> CATEGORIES = List.of(
            new PromptCategory(CAT_CHAT, "对话",
                    "与用户直接对话、以及对话派出去的子代理所用的上下文："
                            + "提示词块拼成 system，其后是代码控制的历史与本次输入。",
                    List.of(CTX_CHAT, CTX_SUBAGENT, CTX_SUBAGENT_SUMMARY)),
            new PromptCategory(CAT_DEV, "开发者选项",
                    "文档→图谱流水线上的自动环节（切分、摘要、知识抽取与入库、chunk 增强），"
                            + "与工具和知识图谱强相关；不了解 agentic 流程的话，通常无需改动。",
                    List.of(CTX_ANALYZE, CTX_COMMIT, CTX_QUESTIONS, CTX_CHUNK_SUMMARY,
                            CTX_CHUNKER, CTX_DOC_SUMMARY)),
            new PromptCategory(CAT_ROUNDTABLE, "圆桌",
                    "圆桌上的成员说话时用的上下文：人格之后接一段协作规程，再往下是调度文书"
                            + "（公开记录与此刻局面）。协作规程逐成员一份（在智能体内部页维护），"
                            + "没配的位回退全局这一份。",
                    List.of(CTX_ROUNDTABLE)));

    private static final Map<String, PromptContext> CONTEXT_BY_KEY = CONTEXTS.stream()
            .collect(Collectors.toUnmodifiableMap(PromptContext::key, Function.identity()));

    /** 全部大类（顺序即提示词页一级标签顺序） */
    public static List<PromptCategory> categories() {
        return CATEGORIES;
    }

    /** 全部上下文（含各自提示词块 key 与运行时块），供「提示词」页渲染 */
    public static List<PromptContext> contexts() {
        return CONTEXTS;
    }

    /** 按 key 取上下文；未知 key 返回空 */
    public static Optional<PromptContext> context(String key) {
        return Optional.ofNullable(key == null ? null : CONTEXT_BY_KEY.get(key));
    }
}
