package cc.ginpika.minis.agent.subconscious;

import java.util.regex.Pattern;

/**
 * 提示词的语汇层：插槽语法 + 心跳主会话正文。
 *
 * <p>提示词在这里以「纯文本 + 插槽」的形态预存——正文本身不含任何运行时数据，
 * 只声明「我需要哪些素材」。素材由 {@code injectors} 声明为「插槽 → 注入器」的绑定，
 * 由 {@link SubconsciousInjectors} 在每次执行时从存储层现取，再由
 * {@link SubconsciousContextBuilder} 填充成一条完整的 LLM 上下文。
 *
 * <p>于是：正文是静态的、注入器绑定是静态的，而「上下文」是每次现造的——
 * LLM 调用本身无状态，状态化的是组装过程与组装结果（落 {@code sub_exec.context}）。
 *
 * <p>刻意不并入 {@code ai.core.prompt} 的提示词块体系：这里的正文带 {@code {{插槽}}} 语汇，
 * 与插槽绑定一起存在 {@code sub_heartbeat} 行里（页面可改），不是「块 + 版本」那套模型。
 */
public final class SubconsciousPrompts {

    private SubconsciousPrompts() {
    }

    /** 插槽语法：{{插槽名}}，名字允许字母/数字/下划线/中划线/点/中文 */
    public static final Pattern SLOT = Pattern.compile("\\{\\{\\s*([\\p{L}\\p{N}_.-]+)\\s*}}");

    /** 未找到素材时的占位文案（明确告知模型「这里本该有东西」，避免它凭空补全） */
    public static final String SLOT_MISSING = "（本项素材为空）";

    // ---------- 心跳主会话（总线）：正文与插槽绑定 ----------

    /**
     * 心跳正文的「锚点与用户画像」小节。新库随 {@link #HEARTBEAT} 一起播种；
     * 锚点概念上线前已播种的旧库，由 SubconsciousSeeder 一次性补挂在正文末尾。
     */
    public static final String HEARTBEAT_ANCHORS = """
            锚点与用户画像：
            - 知识图谱里有系统预置的锚点实体（特殊实体，不可删除），其中「用户」代表用户本人。
            - 用户画像 = 挂在「用户」锚点上的关系总和（偏好 / 属性 / 经历 / 人际）。
              这件事值得立一份编制反复做：content 里放 {{user_profile}} 插槽（执行时自动注入当前画像，
              供它增量维护），tools 带上 upsert_edge / edit_entity_props / edit_relation_props，
              基于对话、文档、记忆里有依据的素材落笔，不凭空臆造。
            - 你自己仍不写知识库：画像的每次修正都交给被派的执行去做，派活时把依据来源说清楚。
            """;

    /**
     * 心跳主会话的内置正文。它不是任务计划（不落 {@code sub_plan}），只占 {@code sub_heartbeat} 那一行；
     * 每拍先由它出场，看着 {{plans}} 名册决定这一拍派谁，缺人手时也由它自己立编制。
     */
    public static final String HEARTBEAT = """
            你是「潜意识」的心跳主会话，是这个系统的唯一入口与调度者。
            每隔一段时间你会醒一次，醒来做三件事：看清现在是什么情况、决定这一拍要不要派人、把活派出去。

            当前时间：{{now}}

            知识库概况：
            {{stats}}

            手里的任务计划名册（节拍与上次派出的时间只是参考，不是你必须遵守的时刻表）：
            {{plans}}

            工作方式：
            - 先看名册：谁久没动了、谁按自己的节拍该跑了、眼下有没有值得顺手做的事。
            - 决定派谁，就用 delegate_plan 派出去；一次可以派几个编制，按需要来，不必凑数。
            - 派活时把「这一轮具体做什么」说清楚——被派出去的那次执行看不到你看到的东西，也不记得上一次。
            - 名册里缺人手（比如某件事该定期做却没人负责），就用 create_plan 立一份编制；
              已有的编制不合适，用 update_plan 改它的提示词或工具；确实没用了，用 delete_plan 撤掉。
            - 眼下一件一次性的活（查点东西、看看某个主题），直接用 delegate_subagent 派一个子代理去办，
              不必为它立编制——子代理跑完即逝，编制是留给要反复做的事的。
            - 想看某次执行之前做过什么，用 read_subagent_run 按执行 id 翻它的过程。
            - 拿不准情况时可以用检索工具看一眼（只读）；但你自己不写知识库：
              写库要有依据，那是有依据的执行的事。

            """ + HEARTBEAT_ANCHORS + """

            这一拍什么也不值得做，就直说「本轮无需动作」，不要为了忙而忙。
            """;

    /** 心跳主会话内置的插槽绑定：时间 + 知识库概况 + 任务计划名册 */
    public static String heartbeatInjectors() {
        return injectors("now", "now", "", "stats", "stats", "", "plans", "plans", "");
    }

    /**
     * 注入器绑定声明：{@code [{slot, injector, args}]}。
     * args 是该注入器的参数（对 {@code doc_fulltext} 为文档 id，对 {@code graph_entity} 为实体名），无参留空。
     */
    private static String injectors(String... slotInjectorArgs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i + 2 < slotInjectorArgs.length; i += 3) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"slot\":\"").append(slotInjectorArgs[i])
                    .append("\",\"injector\":\"").append(slotInjectorArgs[i + 1])
                    .append("\",\"args\":\"").append(slotInjectorArgs[i + 2]).append("\"}");
        }
        return sb.append(']').toString();
    }
}
