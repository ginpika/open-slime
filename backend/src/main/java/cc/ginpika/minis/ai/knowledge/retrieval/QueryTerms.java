package cc.ginpika.minis.ai.knowledge.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简"方言"解析（M0，无中文分词）：
 * 英文/代码 token 走 unicode61（词级）；连续中文串走 trigram 子串（≥3 字）。
 * 中文长串不做全文 phrase（易 0 命中），改取"头/中/尾 8 字窗口"做 AND 子串召回。
 */
public final class QueryTerms {

    private static final Pattern EN = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern CN =
            Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+");

    public record Parsed(String raw, List<String> enTokens, List<String> cnRuns,
                         List<String> cnPhrases) {
        public boolean hasKeyword() {
            return !enTokens.isEmpty() || !cnPhrases.isEmpty();
        }
    }

    private QueryTerms() {
    }

    public static Parsed parse(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        List<String> en = new ArrayList<>();
        Matcher em = EN.matcher(text);
        while (em.find()) {
            String t = em.group();
            if (t.length() >= 2) {
                en.add(t);
            }
        }
        List<String> runs = new ArrayList<>();
        Matcher cm = CN.matcher(text);
        while (cm.find()) {
            runs.add(cm.group());
        }
        List<String> phrases = new ArrayList<>();
        for (String run : runs) {
            if (run.length() >= 3) {
                phrases.addAll(windows(run));
            }
        }
        return new Parsed(text, en, runs, phrases);
    }

    /** 头/中/尾 8 字窗口；≤8 字原样单 phrase */
    private static List<String> windows(String run) {
        int n = run.length();
        if (n <= 8) {
            return List.of(run);
        }
        List<String> out = new ArrayList<>();
        out.add(run.substring(0, 8));
        out.add(run.substring(n - 8));
        if (n >= 20) {
            int mid = n / 2 - 4;
            out.add(run.substring(mid, mid + 8));
        }
        return out;
    }
}
