package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.ai.knowledge.ingest.ResultTexts;
import cc.ginpika.minis.ai.knowledge.retrieval.FastSearchService;
import cc.ginpika.minis.ai.knowledge.store.ChunkStore;
import cc.ginpika.minis.config.ContextProps;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 知识库（STEP 2「RAG-first」）：每轮进模型前，按本次用户输入先做一次三路召回，
 * 把命中的 chunk 与图谱结构证据注入 system——模型不必先主动调 {@code search_vector_chunks}
 * 才看得到知识库。
 *
 * <p>与本类同名的工具（{@code KnowledgeToolbox.ragTool}）走的是同一条检索链路
 * （{@link FastSearchService#ragRecall}）与同一套渲染（{@link ResultTexts}），差别只在触发时机：
 * 这里是我们先注入、工具是模型主动查。两者不冲突——注入的片段不够用时，模型仍可继续调工具。
 *
 * <p>默认<b>关</b>（见 {@link ContextProps}）：关时直接返回空串，{@link ContextBuilder} 原样保留
 * system 基底，请求体与抽取前逐字一致。召回失败一律吞掉降级为「本轮不注入」，绝不因检索抖动打断对话。
 */
@Component
@Order(-10)
public class KnowledgeProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProvider.class);

    /** 单轮注入的 chunk 上限（渲染侧 {@link ResultTexts} 另有自己的上限，取更小值即可） */
    private static final int TOP_CHUNKS = 6;

    private final FastSearchService fast;
    private final ContextProps props;

    public KnowledgeProvider(FastSearchService fast, ContextProps props) {
        this.fast = fast;
        this.props = props;
    }

    @Override
    public List<ContextFragment> fragments(ContextRequest req) {
        return ContextFragment.evidence("local_knowledge", systemSuffix(req));
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        /* 只服务前台会话：子代理 / 潜意识各有自己的检索链路（见 STEP 6-③） */
        if (req.surface() != ContextRequest.Surface.CHAT) {
            return "";
        }
        if (!props.isInjectKnowledge()) {
            return "";
        }
        String query = req.userQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        FastSearchService.RagRecall recall;
        try {
            recall = fast.ragRecall(query);
        } catch (RuntimeException e) {
            log.warn("知识检索注入失败（本轮不注入）：{}", e.getMessage());
            return "";
        }
        List<ChunkStore.Row> chunks = recall.chunks();
        if (chunks.isEmpty()) {
            return "";
        }
        List<ChunkStore.Row> top = chunks.size() <= TOP_CHUNKS ? chunks : chunks.subList(0, TOP_CHUNKS);
        StringBuilder sb = new StringBuilder(
                "【知识库自动召回】按这次提问先检索到以下片段，可直接用作依据；不够时仍可用"
                        + " search_vector_chunks 等工具继续查：\n");
        sb.append(ResultTexts.ragChunksText(top));
        String triples = ResultTexts.graphTriplesText(recall.graph());
        if (!triples.isEmpty()) {
            sb.append("\n\n").append(triples);
        }
        return sb.toString();
    }
}
