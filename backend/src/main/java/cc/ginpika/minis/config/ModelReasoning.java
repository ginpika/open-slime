package cc.ginpika.minis.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Confirmed effort values for this application's Chat Completions + tools protocol.
 * Unknown models stay unconfigured rather than receiving an unsupported request field.
 * GPT-6 and Codex/Pro variants require a separate Responses integration for tool use.
 */
public final class ModelReasoning {
    private static final List<String> STANDARD = List.of("low", "medium", "high");
    private static final List<String> EXTENDED = List.of("low", "medium", "high", "xhigh");
    private static final List<String> DEEPSEEK = List.of("low", "high", "max");
    private static final Set<String> STANDARD_MODELS = Set.of(
            "o1", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5.1");
    private static final Set<String> EXTENDED_MODELS = Set.of("gpt-5.2", "gpt-5.4", "gpt-5.5");
    private static final Set<String> DEEPSEEK_MODELS = Set.of(
            "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro");

    public static List<String> efforts(String model) {
        if (model == null) return List.of();
        String name = model.strip().toLowerCase(Locale.ROOT);
        // Dated OpenAI snapshots inherit the exact parent model's supported subset.
        String parent = name.replaceFirst("-\\d{4}-\\d{2}-\\d{2}$", "");
        if (STANDARD_MODELS.contains(parent)) return STANDARD;
        if (EXTENDED_MODELS.contains(parent)) return EXTENDED;
        if (DEEPSEEK_MODELS.contains(name)) return DEEPSEEK;
        return List.of();
    }

    private ModelReasoning() { }
}
