package cc.ginpika.minis.agent.roundtable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 成员发言的信封：正文只认一个 JSON 对象 {@code {"speech": "...", "desire": 0}}，其余一律丢弃。
 *
 * <p><b>为什么要有它</b>：表态（propose_end / go_silent / interject）是原生工具调用，而工具调用
 * 在协议上意味着「我还要再补一轮」——那一轮模型为了收尾写的正文，会被父类当成最终回答（末轮为准），
 * 于是它真正说出口的话被顶替掉。把正文结构化成一个字段之后，「哪一段是发言」变成可判定的：
 * 某一轮解出了非空 speech 就记下来，末轮什么都没说（表态后的收尾轮常常如此）就沿用那一句。
 *
 * <p>它同时是「无话可说」唯一清晰的表达方式：{@code {"speech": ""}} 明确表示这一轮不再补充，
 * 而不是让模型写一句「已记录」——那句话会被当成发言。
 *
 * <p><b>{@code desire} 是同一次收尾里顺带报出的「下一轮发言欲望」</b>（0~10，见
 * {@link DesireSpeakerStrategy}）：它不是发言的一部分，而是选人的依据——说完了还很想接着说，
 * 就给高分。它不像 speech 那样按增量解析（见 {@link #desire()}），只在一次发言收尾时才取。
 *
 * <p><b>按增量解析</b>（一字符一字符喂），因为同一套规则要用在两处：流式期间只把 speech 的内容
 * 推给页面（否则用户会看见 JSON 原文），以及落库时取出正文。宽容优先：找不到 speech 键就整段
 * 当发言，绝不让格式问题把一次发言变成失败。
 *
 * <p>本类不是线程安全的，一次发言用一个实例。
 */
final class SpeechEnvelope {

    /** 要认的键（连着引号，避免 content 里出现 speech 这个词误命中） */
    private static final String KEY = "\"speech\"";

    /**
     * 原生工具调用标记（DeepSeek 的 DSML 文本）：出现在正文里说明这一轮本该是工具调用，
     * 只是没被供应商转成结构化 tool_calls。这种正文<b>不是成员说的话</b>，绝不能兜底成发言。
     */
    private static final String TOOL_MARK = "DSML";

    /** 发言欲望的键：值允许带引号（模型有时写成 {@code "desire": "7"}） */
    private static final Pattern DESIRE = Pattern.compile("\"desire\"\\s*:\\s*\"?(-?\\d+)\"?");

    /** 到目前为止收到的原文（兜底时整段返回它） */
    private final StringBuilder raw = new StringBuilder();
    /** 已经解出来的发言 */
    private final StringBuilder speech = new StringBuilder();
    /** 已扫描到的原文位置 */
    private int cursor;
    /** 已推出去的字符数：用于把新解出的部分切片给页面 */
    private int emitted;
    /** 0=找键 1=找冒号 2=找开引号 3=读值 4=读完 */
    private int phase;
    private boolean escaped;
    private boolean seen;

    /** 追加一段增量，返回其中「新解出来的发言」（流式期间直接转推给页面） */
    String append(String delta) {
        raw.append(delta);
        scan();
        String out = speech.substring(emitted);
        emitted = speech.length();
        return out;
    }

    /** 到现在为止解出来的发言 */
    String speech() {
        return speech.toString();
    }

    /**
     * 模型没按信封输出时的兜底：整段原文就是它说的话。
     *
     * <p><b>例外</b>：正文里带原生工具调用标记（{@link #TOOL_MARK}）时返回空——那一段是工具调用，
     * 不是发言。把它兜底成发言，工具调用标记就会被原样念到桌上（踩过这个坑）。
     */
    String fallback() {
        if (raw.indexOf(TOOL_MARK) >= 0) {
            return "";
        }
        return raw.toString();
    }

    /**
     * 这次发言里报出的「下一轮发言欲望」；没报或格式不对返回 null。
     *
     * <p>不做增量解析：欲望只在一次发言收尾时才用得上，那时正文已经收全，一次正则扫过 raw 就够了
     * ——比在字符状态机里再塞一个键简单得多。出现多次取最后一次（后报的为准）。
     */
    Integer desire() {
        return desireOf(raw.toString());
    }

    /** 从一段正文里抠出「下一轮发言欲望」；没写或写坏了返回 null（越界由调用方夹紧） */
    static Integer desireOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DESIRE.matcher(raw);
        Integer found = null;
        while (matcher.find()) {
            try {
                found = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                /* 数值大到溢出 int：当没报过 */
            }
        }
        return found;
    }

    /** 有没有出现过 speech 键（没出现说明模型没按约定输出） */
    boolean seen() {
        return seen;
    }

    /** 一次完整的解析（落库路径用；流式路径请用 {@link #append}） */
    static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        SpeechEnvelope envelope = new SpeechEnvelope();
        envelope.append(raw);
        return envelope.seen() ? envelope.speech() : envelope.fallback();
    }

    private void scan() {
        while (cursor < raw.length()) {
            if (phase == 4) {
                return;
            }
            char c = raw.charAt(cursor);
            if (phase == 0) {
                if (matchesKeyAt(cursor)) {
                    seen = true;
                    phase = 1;
                    cursor += KEY.length();
                    continue;
                }
                if (couldBecomeKey(cursor)) {
                    /* 增量刚好断在键的中间（如刚收到一个引号）：停在原地等下一段，
                       否则键被拆散就永远匹配不上了 */
                    return;
                }
            } else if (phase == 1) {
                if (c == ':') {
                    phase = 2;
                    cursor++;
                    continue;
                }
                if (c == '}' || c == '"') {
                    /* 不是我们要找的那个键：回去继续找 */
                    phase = 0;
                    continue;
                }
            } else if (phase == 2) {
                if (c == '"') {
                    phase = 3;
                    cursor++;
                    continue;
                }
                if (c == '}') {
                    /* 这个键的值不是字符串：不是契约里的 speech，回去继续找 */
                    phase = 0;
                    continue;
                }
            } else if (phase == 3) {
                if (escaped) {
                    escaped = false;
                    speech.append(unescape(c));
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    /* 闭引号：值到此为止，后面的（右花括号等）都丢弃 */
                    phase = 4;
                    cursor++;
                    continue;
                } else {
                    speech.append(c);
                }
            }
            cursor++;
        }
    }

    private boolean matchesKeyAt(int i) {
        return i + KEY.length() <= raw.length() && raw.substring(i, i + KEY.length()).equals(KEY);
    }

    /** 增量断在键的前半截：此时不能跳过这个位置，否则键会被拆散 */
    private boolean couldBecomeKey(int i) {
        return i + KEY.length() > raw.length() && KEY.startsWith(raw.substring(i));
    }

    /** JSON 转义还原（罕见的 Unicode 转义原样保留：反斜杠后的 u 与四位十六进制照抄） */
    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'b' -> '\b';
            case 'f' -> '\f';
            default -> c;
        };
    }
}
