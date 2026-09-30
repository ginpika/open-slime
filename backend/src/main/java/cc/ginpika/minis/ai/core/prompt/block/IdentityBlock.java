package cc.ginpika.minis.ai.core.prompt.block;

import cc.ginpika.minis.ai.core.prompt.PromptBlock;
import cc.ginpika.minis.ai.core.prompt.PromptLibrary;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 对话身份：对话 system 的第一块。没有代码内置——内置来自 .env 的 LLM_SYSTEM_PROMPT，
 * 故 {@link #builtin()} 返回 null，由 PromptManager 兜底。
 */
@Component
public class IdentityBlock implements PromptBlock {

    public static final String KEY = "identity";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String name() {
        return "对话身份";
    }

    @Override
    public String description() {
        return "对话者是谁——你与用户的关系、语气与偏好。内置来自 .env 的 LLM_SYSTEM_PROMPT；"
                + "每个会话还可在首页「…」菜单单独切换。";
    }

    @Override
    public Set<String> contexts() {
        return Set.of(PromptLibrary.CTX_CHAT);
    }

    @Override
    public String tag() {
        return "persona";
    }

    @Override
    public String builtin() {
        return null;
    }
}
