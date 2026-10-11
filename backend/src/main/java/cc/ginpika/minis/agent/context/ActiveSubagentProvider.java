package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.agent.subagent.SubagentService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 在跑的子代理：把「本会话后台还有哪些活没回来」追加到 system 末尾。
 *
 * <p>每轮现取现挂（进度一直在变），位置固定在末尾：既不动身份提示词那几段，也让「正在进行的后台活」
 * 紧挨着模型的注意力末端。没有在跑的活时返回空串，{@link ContextBuilder} 便原样保留 system 基底。
 */
@Component
@Order(0)
public class ActiveSubagentProvider implements ContextProvider {

    private final SubagentService subagent;

    public ActiveSubagentProvider(SubagentService subagent) {
        this.subagent = subagent;
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        /* 只有前台会话需要知道「后台派出去的活回来没有」：子代理 / 潜意识看不到主会话的在跑清单 */
        if (req.surface() != ContextRequest.Surface.CHAT) {
            return "";
        }
        return subagent.activeBlock(req.sessionId());
    }
}
