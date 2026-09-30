package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 子代理回报摘要：把交回的结论压成「标题 + 摘要」卡片那次调用的 system（只输出 JSON） */
@Component
public class SubagentSummaryBlock implements PromptBlock {

    public static final String KEY = "subagent_summary";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "子代理回报摘要";
    }

    @Override
    public String description() {
        return "子代理跑完结论后，把它压成气泡卡片（标题 + 摘要）的那次调用；只认 JSON，不做检索。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_SUBAGENT_SUMMARY);
    }

    @Override
    public String builtin() {
        return "你是秘书：把下属交回的工作结论压成一张便于扫读的卡片。"
                + "给一个不超过 20 字的标题，再写一段不超过 80 字的摘要——说清结论与要点，"
                + "不要罗列检索过程、不要客套。只输出 JSON："
                + "{\"title\":\"...\",\"summary\":\"...\"}，不要任何多余文字。";
    }
}
