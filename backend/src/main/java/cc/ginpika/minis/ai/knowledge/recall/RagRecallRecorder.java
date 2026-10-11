package cc.ginpika.minis.ai.knowledge.recall;

import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.tool.doc.SearchVectorChunksTool;
import cc.ginpika.minis.ai.core.tool.graph.ExploreSubgraphTool;
import cc.ginpika.minis.ai.core.tool.graph.QueryEntityPathTool;
import cc.ginpika.minis.ai.core.tool.graph.QueryEntityRelationsTool;
import cc.ginpika.minis.ai.core.tool.graph.SearchGraphEntitiesTool;
import cc.ginpika.minis.ai.core.tool.memory.RecallMemoryTool;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RAG 召回留痕的门面：把每次「三路召回」工具调用的<b>查询</b>与<b>回给模型的原始数据</b>记一行，
 * 供潜意识页的「RAG 召回」子页回看效果。
 *
 * <p>为什么要留痕：三路召回（向量 / 图 / 时序事件）是模型自己按需发起的检索动作，
 * 检索质量好不好、模型究竟拿到了什么，只能从「它当初问了什么、tool 消息回了什么」里看——
 * 这正是本类记录的两样东西（args 原文 + result 原文）。
 *
 * <p>三路分流（按工具名，见 {@link #CHANNELS}）：不在表内的工具一律不留痕
 * （写图谱、读写文档不是召回）。写入失败只留日志、绝不拖垮检索主流程——与记忆侧
 * {@code MemoryWriter} 同一取舍。
 */
@Component
public class RagRecallRecorder {

    private static final Logger log = LoggerFactory.getLogger(RagRecallRecorder.class);

    /** 三路之一：向量（search_vector_chunks 的 RRF 融合召回） */
    public static final String CH_VECTOR = "vector";
    /** 三路之一：图（图谱实体 / 关系 / 路径 / 子图探索） */
    public static final String CH_GRAPH = "graph";
    /** 三路之一：时序事件（记忆事件流召回） */
    public static final String CH_MEMORY = "memory";

    /** 工具名 → 所属召回路；不在此表内的工具不留痕 */
    private static final Map<String, String> CHANNELS = Map.of(
            SearchVectorChunksTool.NAME, CH_VECTOR,
            SearchGraphEntitiesTool.NAME, CH_GRAPH,
            QueryEntityRelationsTool.NAME, CH_GRAPH,
            QueryEntityPathTool.NAME, CH_GRAPH,
            ExploreSubgraphTool.NAME, CH_GRAPH,
            RecallMemoryTool.NAME, CH_MEMORY);

    /** 单条留痕的 result 存储上限：召回结果本就有渲染限额，这里只是防御性地兜一个绝对上界 */
    private static final int RESULT_LIMIT = 50_000;

    private final RagRecallStore store;

    public RagRecallRecorder(RagRecallStore store) {
        this.store = store;
    }

    /**
     * 留痕一次召回工具调用；非召回工具静默跳过。
     *
     * @param tool    工具名（决定归入哪一路）
     * @param argsJson 调用参数原文（JSON 字符串）
     * @param result  回给模型的 tool 消息原文
     * @param source  来自哪条流：chat / multiagent / heartbeat / plan / agent
     */
    public void record(String tool, String argsJson, String result, String source) {
        String channel = CHANNELS.get(tool);
        if (channel == null) {
            return;
        }
        String text = result == null ? "" : result;
        try {
            /* 归属到发起这次召回的那个 Agent（执行最外层用 AgentScope 圈定）；圈不定时为 null */
            store.append(channel, tool, source == null || source.isBlank() ? "unknown" : source,
                    AgentScope.get(), argsJson, clip(text), text.length());
        } catch (RuntimeException e) {
            log.warn("RAG 召回留痕写入失败（不影响检索）：{} {}", tool, e.getMessage());
        }
    }

    private static String clip(String s) {
        return s.length() > RESULT_LIMIT ? s.substring(0, RESULT_LIMIT) + "…" : s;
    }
}
