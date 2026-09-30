package cc.ginpika.minis.ai.knowledge.ingest;

import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.ChatMessage;
import cc.ginpika.minis.ai.llm.OpenAiChatClient.Completion;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.ChunkerBlock;
import cc.ginpika.minis.stats.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文档分块器（输入约定为 markdown，三级降级策略）：
 * <ol>
 *   <li>优先按 markdown 标题（ATX {@code #}~{@code ######}，忽略代码围栏内）切节：
 *       节尽量整块、块以标题行开头、段落保持完整，贪心累积到约 500~800 字符；</li>
 *   <li>标题节超过 1200 字符时，节内段落贪心打包（每块重复标题行打头），必要时放宽到 ≤1200；</li>
 *   <li>单段仍超 1200（如超长代码块）时调用 LLM 按语义切分（拼接保真校验，未配置/失败退硬切）。</li>
 * </ol>
 */
@Component
public class Chunker {

    private static final Logger log = LoggerFactory.getLogger(Chunker.class);
    /** 期望块下限：当前块小于此值时优先合并（允许放宽到 HARD_MAX），避免碎块 */
    private static final int TARGET = 500;
    /** 正常上限 */
    private static final int SOFT_MAX = 800;
    /** 放宽上限 */
    private static final int HARD_MAX = 1200;
    /** ATX 标题行：1~6 个 # 后跟空白（无空格的 #tag 不算） */
    private static final Pattern HEADING = Pattern.compile("#{1,6}\\s.*");

    private final OpenAiChatClient client;
    private final PromptManager prompts;
    private final ObjectMapper objectMapper;

    public Chunker(OpenAiChatClient client, PromptManager prompts, ObjectMapper objectMapper) {
        this.client = client;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    // ---------- 一级：markdown 标题节切 ----------

    public List<String> split(String content) {
        List<Section> sections = parse(content);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (Section sec : sections) {
            if (sec.length() > HARD_MAX) {
                // 标题节太长：整节放不进放宽上限 → 二级降级
                flush(out, cur);
                out.addAll(splitLongSection(sec));
            } else if (cur.length() == 0) {
                cur.append(sec.text());
            } else if (cur.length() + 2 + sec.length() <= SOFT_MAX
                    || (cur.length() < TARGET && cur.length() + 2 + sec.length() <= HARD_MAX)) {
                cur.append("\n\n").append(sec.text());
            } else {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append(sec.text());
            }
        }
        flush(out, cur);
        return out;
    }

    // ---------- 二级：节内段落贪心（块以标题行打头） ----------

    private List<String> splitLongSection(Section sec) {
        List<String> out = new ArrayList<>();
        String prefix = String.join("\n", sec.headings());
        StringBuilder cur = new StringBuilder();
        for (String para : sec.paragraphs()) {
            if (para.length() > HARD_MAX) {
                // 单段超出放宽上限 → 三级降级（LLM/硬切）
                flush(out, cur, prefix);
                out.addAll(semanticSplit(para));
            } else if (cur.length() == 0) {
                cur.append(para);
            } else if (cur.length() + 2 + para.length() <= SOFT_MAX
                    || (cur.length() < TARGET && cur.length() + 2 + para.length() <= HARD_MAX)) {
                cur.append("\n\n").append(para);
            } else {
                flush(out, cur, prefix);
                cur.append(para);
            }
        }
        flush(out, cur, prefix);
        return out;
    }

    // ---------- 三级：LLM 语义切分（保真校验，硬切兜底） ----------

    private List<String> semanticSplit(String para) {
        if (client.configured()) {
            try {
                List<String> pieces = askLlm(para);
                if (!pieces.isEmpty() && faithful(para, pieces)) {
                    List<String> safe = new ArrayList<>();
                    for (String p : pieces) {
                        safe.addAll(hardSplit(p));
                    }
                    return safe;
                }
                log.warn("LLM 语义切分结果不可信，退硬切（{} 字符段落）", para.length());
            } catch (Exception e) {
                log.warn("LLM 语义切分失败，退硬切: {}", e.getMessage());
            }
        } else {
            log.info("LLM 未配置，超长段落（{} 字符）按行硬切", para.length());
        }
        return hardSplit(para);
    }

    /** 保真校验：片段拼接去空白后须与原文完全一致（LLM 不得增删文字） */
    private static boolean faithful(String origin, List<String> pieces) {
        String a = origin.replaceAll("\\s+", "");
        String b = String.join("", pieces).replaceAll("\\s+", "");
        return a.equals(b);
    }

    private List<String> askLlm(String para) throws Exception {
        Completion c = client.chat(List.of(
                new ChatMessage("system", prompts.resolve(ChunkerBlock.KEY)),
                new ChatMessage("user", "请切分以下文本：\n\n" + para)),
                TokenUsageService.SOURCE_CHUNKER);
        return parseJsonArray(c.content());
    }

    /** 解析 LLM 回答中的 JSON 数组（剥可能存在的 ```json 围栏与前后杂字） */
    private List<String> parseJsonArray(String content) throws Exception {
        int l = content.indexOf('[');
        int r = content.lastIndexOf(']');
        if (l < 0 || r <= l) {
            return List.of();
        }
        JsonNode node = objectMapper.readTree(content.substring(l, r + 1));
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText("").strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    // ---------- 硬切兜底：按行累积 ≤HARD_MAX，超长行再剪 ----------

    private static List<String> hardSplit(String para) {
        if (para.length() <= HARD_MAX) {
            return List.of(para);
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : para.split("\n", -1)) {
            if (line.length() > HARD_MAX) {
                flush(out, cur);
                for (int i = 0; i < line.length(); i += HARD_MAX) {
                    out.add(line.substring(i, Math.min(line.length(), i + HARD_MAX)));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + line.length() + 1 > HARD_MAX) {
                flush(out, cur);
            }
            if (cur.length() > 0) {
                cur.append('\n');
            }
            cur.append(line);
        }
        flush(out, cur);
        return out;
    }

    // ---------- 解析：markdown → 标题节 ----------

    /** 一个标题节：标题行链（连续标题合并，可能为空=文首无标题）+ 空行分隔的段落 */
    private record Section(List<String> headings, List<String> paragraphs) {
        int length() {
            return text().length();
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (String h : headings) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(h);
            }
            for (String p : paragraphs) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(p);
            }
            return sb.toString();
        }
    }

    /** 逐行扫描：遇标题行开新节（代码围栏 ``` 内的 # 不算标题），空行分段落 */
    private static List<Section> parse(String content) {
        List<Section> out = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        StringBuilder pbuf = new StringBuilder();
        boolean inFence = false;
        for (String line : content.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
            }
            if (!inFence && HEADING.matcher(line).matches()) {
                flushParagraph(paragraphs, pbuf);
                if (!paragraphs.isEmpty()) {
                    out.add(new Section(headings, paragraphs));
                    headings = new ArrayList<>();
                    paragraphs = new ArrayList<>();
                }
                headings.add(line);
            } else if (line.isBlank()) {
                flushParagraph(paragraphs, pbuf);
            } else {
                if (pbuf.length() > 0) {
                    pbuf.append('\n');
                }
                pbuf.append(line);
            }
        }
        flushParagraph(paragraphs, pbuf);
        if (!headings.isEmpty() || !paragraphs.isEmpty()) {
            out.add(new Section(headings, paragraphs));
        }
        return out;
    }

    private static void flushParagraph(List<String> paragraphs, StringBuilder pbuf) {
        if (pbuf.length() > 0) {
            String p = pbuf.toString().strip();
            if (!p.isEmpty()) {
                paragraphs.add(p);
            }
            pbuf.setLength(0);
        }
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** 带标题前缀 flush：块以标题行开头（无标题的前言节不加前缀） */
    private static void flush(List<String> out, StringBuilder cur, String prefix) {
        if (cur.length() > 0) {
            out.add(prefix.isEmpty() ? cur.toString() : prefix + "\n\n" + cur);
            cur.setLength(0);
        }
    }
}
