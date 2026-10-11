package cc.ginpika.minis.agent.context;

import cc.ginpika.minis.agent.subagent.ScratchpadStore;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 上下文源 · 子代理面（STEP 6-③）：子代理「独立上下文」里除人格与交代之外的全部素材——
 * 本会话共享黑板（此前同伴的发现）+ 当前时间。
 *
 * <p>子代理是匿名 worker：看不到当前对话，也没有上一轮记忆，system 只有两部分——它自己的人格
 * （{@code SubagentBlock} 提示词位，作为 {@code systemBase} 由调用方备好）与这里追加的素材。
 * 黑板让它开工先读前人查过什么（同一会话里先派后派接力，不重复查）；当前时间让它知道「现在是何时」。
 *
 * <p>黑板宿主取 {@code request.sessionId()}（即派它的那个会话），心跳侧无会话时为 null → 只挂时间。
 * 只服务 {@link ContextRequest.Surface#SUBAGENT} 面：前台会话 / 潜意识不读它。
 */
@Component
@Order(0)
public class SubagentContextProvider implements ContextProvider {

    /** 黑板：新子代理开工时注入多少条前人发现、每条截多长——只作参考，不喧宾夺主 */
    private static final int SCRATCHPAD_READ = 5;
    private static final int SCRATCHPAD_CLIP = 600;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ScratchpadStore scratchpad;

    public SubagentContextProvider(ScratchpadStore scratchpad) {
        this.scratchpad = scratchpad;
    }

    @Override
    public String systemSuffix(ContextRequest req) {
        if (req.surface() != ContextRequest.Surface.SUBAGENT) {
            return "";
        }
        String blackboard = blackboard(req.sessionId());
        String time = "当前时间：" + STAMP.format(LocalDateTime.now());
        return blackboard.isBlank() ? time : blackboard + "\n\n" + time;
    }

    /**
     * 本会话此前的子代理发现（共享黑板）：注入新子代理的 system，让它知道前人查过什么、别重复查。
     *
     * <p>取最近 {@link #SCRATCHPAD_READ} 条、每条截到 {@link #SCRATCHPAD_CLIP} 字——只作参考，
     * 不喧宾夺主（结论全文仍在各自的气泡与执行留痕里）。无会话 / 无记录时返回空串。
     */
    private String blackboard(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return "";
        }
        List<ScratchpadStore.Entry> entries = scratchpad.recent(hubId, SCRATCHPAD_READ);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【此前同伴的发现】本会话之前派出的子代理已经查过下面这些，"
                + "可作参考、别重复查（拿不准可以自行核对，但不必从零再来）：");
        for (ScratchpadStore.Entry e : entries) {
            sb.append("\n- ").append(e.author() == null || e.author().isBlank() ? "（无名）" : e.author())
                    .append("：").append(clip(e.content(), SCRATCHPAD_CLIP));
        }
        return sb.toString();
    }

    /** 按 max 字截断（黑板条目只作参考，超长结论截断即可） */
    private static String clip(String s, int max) {
        String flat = s == null ? "" : s.strip();
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }
}
