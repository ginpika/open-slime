package cc.ginpika.minis.ai.core.prompt.skill;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Stateful instructions, composed only by the trusted IDE runtime, never by chat intent. */
@Component
public class CodingSkill implements PromptBlock {
    public static final String KEY = "coding_skill";
    public static final String DEFAULT = load();
    @Override public String key() { return KEY; }
    @Override public String name() { return "编程 Skill"; }
    @Override public String description() { return "仅在编程 IDE 的项目任务中启用；保留调查、实施、验证与交付规程，普通对话不加载。"; }
    @Override public Set<String> contexts() { return Set.of(PromptLibrary.CTX_IDE); }
    @Override public String builtin() { return DEFAULT; }
    @Override public String tag() { return "coding_skill"; }
    private static String load() {
        try { return new ClassPathResource("ai/skills/coding.md").getContentAsString(StandardCharsets.UTF_8).strip(); }
        catch (IOException error) { throw new UncheckedIOException("编程 Skill 不可读", error); }
    }
}
