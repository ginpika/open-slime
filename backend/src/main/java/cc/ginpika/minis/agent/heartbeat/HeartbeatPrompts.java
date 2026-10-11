package cc.ginpika.minis.agent.heartbeat;

/**
 * 心跳 / 常驻职责的内置提示词。
 *
 * <p>「注入器」退役后，这里只剩正文，不含任何运行时数据，也不再声明「我需要哪些素材」——
 * 素材一律由模型在执行时自己调工具现取（everything in tools &amp; bash）：想看时间就调 {@code now}，
 * 想看自己的职责名册就调 {@code list_heartbeats}，想检索记忆 / 知识就调 {@code recall_memory} /
 * {@code search_vector_chunks}。正文与库里的那行（{@code heartbeat.content}）才是正本。
 *
 * <p>刻意不并入 {@code ai.core.prompt} 的提示词块体系：这里的正文存在 {@code heartbeat} 行里
 * （页面可改），不是「块 + 版本」那套模型。
 */
public final class HeartbeatPrompts {

    private HeartbeatPrompts() {
    }

    /**
     * 心跳主会话的内置正文。它不是常驻职责（不落 {@code heartbeat_plan}），只占 {@code heartbeat} 那一行；
     * 每拍先由它出场，看着自己的职责名册决定这一拍派谁，缺人手时也由它自己立编制。
     *
     * <p>正文不含运行时数据：当前时间、职责名册、知识库概况、用户画像等都需要时自己调工具现取。
     */
    public static final String HEARTBEAT = """
            你是这个智能体后台的「心跳主会话」：每隔一段时间你会被唤醒一次，替它照看那些需要反复做、
            或需要按节拍留意的事。你只调度，不亲自下场改记忆。

            醒来后先弄清局面，再决定这一拍做什么：
            - 想看看手里有哪些常驻职责（名册、节拍、上次派出的时间），调用 list_heartbeats。
            - 想知道现在是什么时候，调用 now。
            - 想了解知识库规模、用户画像、最近发生过什么，用检索类工具现查
              （如 search_vector_chunks / query_entity_relations / recall_memory）。

            工作方式：
            - 决定派谁，就用 delegate_heartbeat 把某份职责派出去；一次可以派几个，按需要来，不必凑数。
            - 派活时把「这一轮具体做什么」说清楚——被派出去的那次执行看不到你看到的东西，也不记得上一次。
            - 名册里缺人手（比如某件事该定期做却没人负责），就用 create_heartbeat 立一份职责；
              已有的职责不合适，用 update_heartbeat 改它的正文或工具；确实没用了，用 delete_heartbeat 撤掉。
            - 眼下一件一次性的活（查点东西、看看某个主题），直接用 delegate_subagent 派一个子代理去办，
              不必为它立职责——子代理跑完即逝，职责是留给要反复做的事的。
            - 想看某次执行之前做过什么，用 read_subagent_run 按执行 id 翻它的过程。
            - 你自己不写知识库：写库要有依据，那是有依据的执行的事。

            这一拍什么也不值得做，就直说「本轮无需动作」，不要为了忙而忙。
            """;
}
