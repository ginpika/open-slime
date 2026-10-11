package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.ai.memory.recall.MemoryRecall;
import cc.ginpika.minis.config.ContextProps;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 记忆（STEP 2）：每轮进模型前，按本次用户输入召回相关记忆注入 system，
 * 让模型默认就知道「用户说过什么、我答应过什么」，而不必先主动调 {@code recall_memory}。
 *
 * <p>复用记忆召回的读侧（{@link MemoryRecall#recall}）与渲染（{@link MemoryRecall#hitsText}），
 * 与 recall_memory 工具同一份语义：时间窗收敛候选、窗口内向量打分、拿不到查询向量即退化为时间序，
 * 且默认挡掉后台产出（心跳 / 计划 / 子代理）——注入的只会是「对话里真发生过的」。
 *
 * <p>默认<b>关</b>（见 {@link ContextProps}）：关时直接返回空串，零行为变化；召回失败吞掉降级为
 * 「本轮不注入」，不因记忆服务抖动打断对话；没有命中时不注入空壳（避免给 system 加噪声）。
 */
@Component
@Order(-5)
public class MemoryProvider implements ContextProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryProvider.class);

    /** 单轮注入的记忆条数上限 */
    private static final int TOP_MEMORIES = 5;

    private final MemoryRecall recall;
    private final ContextProps props;

    public MemoryProvider(MemoryRecall recall, ContextProps props) {
        this.recall = recall;
        this.props = props;
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        /* 只服务前台会话：子代理 / 潜意识各有自己的召回链路（见 STEP 6-③） */
        if (req.surface() != ContextRequest.Surface.CHAT) {
            return "";
        }
        if (!props.isInjectMemory()) {
            return "";
        }
        String query = req.userQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        List<MemoryRecall.Hit> hits;
        try {
            hits = recall.recall(query, null, null, null, null, TOP_MEMORIES);
        } catch (RuntimeException e) {
            log.warn("记忆注入失败（本轮不注入）：{}", e.getMessage());
            return "";
        }
        String text = MemoryRecall.hitsText(hits);
        if (text.isBlank()) {
            return "";
        }
        return "【相关记忆】按这次提问召回的历史片段（用户说过什么 / 我答应过什么）：\n" + text;
    }
}
