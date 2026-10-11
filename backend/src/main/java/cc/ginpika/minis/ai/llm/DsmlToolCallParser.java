package cc.ginpika.minis.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * DeepSeek 原生工具调用（DSML 文本）的<b>流式解析器</b>——文本增量状态机。
 *
 * <p><b>为什么不能用一次性正则</b>：工具调用标记会被 SSE 切成任意片段到达
 * （如 {@code "<｜"} / {@code "｜DSML｜"} / {@code "｜tool"} / {@code "_calls"} / {@code ">"} 分属不同的 chunk），
 * 对「完整字符串」做一次性匹配在这类拆分下必然失效。本类是逐字符推进的状态机：
 * <b>同一个实例一路喂 {@code delta.content}，跨 chunk 自动续扫，块闭合时把调用交出来</b>
 * （Cherry Studio / Hermes 的修复都是这个路子）。
 *
 * <p><b>标记变体</b>：DSML 的竖线是 U+FF5C 全角，不是 ASCII 的竖线；模型偶尔也会给 ASCII 竖线或
 * 双写变体，这里一并兼容（竖线数量不限）。
 *
 * <p><b>正文与人话分离</b>：块外的正文原样吐出（流式透出给页面），块内的字符一律不进正文
 * ——于是那段工具调用标记永远不会被上层当成发言。
 *
 * <p>本类不是线程安全的，一次补全用一个实例。
 */
final class DsmlToolCallParser {

    /** DSML 的词干：标记里的那串大写字母。只有精确命中它才认为进入了标记 */
    private static final String MARK = "DSML";
    /** 全角竖线（U+FF5C）：标记用它，不是 ASCII 的竖线 */
    private static final char FULL_BAR = '\uFF5C';

    /** 前缀扫描态：只有可能构成标记前缀的字符会被「暂扣」，其余即时当作正文 */
    private enum Scan {
        /** 普通正文 */
        TEXT,
        /** 刚读到 '<' */
        LT,
        /** '<' 之后读过 '/' */
        SLASH,
        /** 读过竖线（全角 / ASCII / 双写都算） */
        BARS,
        /** 读过空白 */
        SPACES,
        /** 正在逐字匹配 DSML */
        MARK,
        /** 已确认是 DSML 标签，正在读到 '>' */
        TAG,
    }

    private final ObjectMapper om;

    // ---- 扫描状态（跨 feed 保留） ----
    private Scan scan = Scan.TEXT;
    /** 可能构成标记的前缀原文（含 '<'）：一旦确认不是标记，原样回吐为正文 */
    private final StringBuilder prefix = new StringBuilder();
    /** MARK 已匹配到的字符数（1..4） */
    private int markIdx;
    /** 前缀里出现过 '/'（闭合标签） */
    private boolean tagClosing;
    /** 当前标签内部（DSML 之后、'>' 之前的部分） */
    private final StringBuilder tag = new StringBuilder();

    // ---- 语义状态 ----
    /** 标签之间的文本：块内＝参数值 / 空白（不透明），块外不在此累积 */
    private final StringBuilder text = new StringBuilder();
    /** 本次产出、应透出给人看的正文 */
    private final StringBuilder visible = new StringBuilder();
    private boolean inBlock;
    /** 块是由 invoke 隐式开启（没有外层 calls 包裹）——这种块在 invoke 收尾时即关闭 */
    private boolean implicitBlock;
    private String callName;
    private ObjectNode callArgs;
    private String paramName;
    private boolean paramString;
    private final List<ObjectNode> calls = new ArrayList<>();

    DsmlToolCallParser(ObjectMapper om) {
        this.om = om;
    }

    /** 解析收尾的产出：还原出的工具调用 + 最后一段还没吐出的正文 */
    record Result(List<ObjectNode> calls, String visible) {
    }

    /** 喂一段 {@code delta.content}，返回其中「应透出给人看」的正文（块内字符全部扣下） */
    String feed(String delta) {
        visible.setLength(0);
        if (delta != null) {
            for (int i = 0; i < delta.length(); i++) {
                step(delta.charAt(i));
            }
        }
        return visible.toString();
    }

    /** 流结束：回吐可能悬着的前缀、收尾最后一个参数 / 调用，并交出全部工具调用 */
    Result finish() {
        visible.setLength(0);
        if (scan != Scan.TAG) {
            /* 悬着的前缀（如流正好断在 '<' 之后）：它不是标记，按正文吐回 */
            if (inBlock) {
                text.append(prefix);
            } else {
                visible.append(prefix);
            }
            prefix.setLength(0);
            scan = Scan.TEXT;
        }
        flushText();
        if (callName != null) {
            finishCall();   // 块没闭合就断了：尽力把已经读到的调用交出去，别整段丢
        }
        inBlock = false;
        return new Result(List.copyOf(calls), visible.toString());
    }

    // ---------- 逐字符状态机 ----------

    private void step(char ch) {
        switch (scan) {
            case TAG -> {
                if (ch == '>') {
                    finishTag();
                } else {
                    tag.append(ch);
                }
            }
            case TEXT -> {
                if (ch == '<') {
                    tagClosing = false;
                    prefix.setLength(0);
                    prefix.append(ch);
                    scan = Scan.LT;
                } else if (inBlock) {
                    text.append(ch);    // 块内：参数值或标签间空白
                } else {
                    visible.append(ch); // 块外：即时透传，保持流式
                }
            }
            // '<' 之后、DSML 之前：允许 '/'、竖线、空白
            case LT -> advance(ch, true);
            case SLASH, BARS, SPACES -> advance(ch, false);
            case MARK -> {
                if (ch == MARK.charAt(markIdx)) {
                    markIdx++;
                    prefix.append(ch);
                    if (markIdx == MARK.length()) {
                        commitTag();
                    }
                } else {
                    bail(ch);
                }
            }
        }
    }

    /** 前缀推进：允许则吃一个 '/'，再吃竖线 / 空白 / 起始 'D'；都不匹配就判定不是标记 */
    private void advance(char ch, boolean allowSlash) {
        if (allowSlash && ch == '/') {
            tagClosing = true;
            scan = Scan.SLASH;
        } else if (isBar(ch)) {
            scan = Scan.BARS;
        } else if (ch == ' ') {
            scan = Scan.SPACES;
        } else if (ch == MARK.charAt(0)) {
            scan = Scan.MARK;
            markIdx = 1;
        } else {
            bail(ch);
            return;
        }
        prefix.append(ch);
    }

    /** 确认不是标记：把前缀原文当正文吐出，当前字符按正文重扫（它可能又是 '<'） */
    private void bail(char ch) {
        if (inBlock) {
            text.append(prefix);
        } else {
            visible.append(prefix);
        }
        prefix.setLength(0);
        tagClosing = false;
        scan = Scan.TEXT;
        step(ch);
    }

    private void commitTag() {
        prefix.setLength(0);
        tag.setLength(0);
        scan = Scan.TAG;
    }

    // ---------- 标签语义 ----------

    /**
     * 一个 DSML 标签读完（遇到 '>'）：先归位标签间的文本（参数值 / 空白），再按标签名落地。
     *
     * <p>标签名宽容：竖线 / 空白 / 前导 '/' 都不算名字的一部分；同时接受
     * {@code calls|tool_calls}、{@code invoke|function_call}、{@code parameter|param} 几种叫法。
     */
    private void finishTag() {
        String inner = tag.toString();
        tag.setLength(0);
        scan = Scan.TEXT;
        /* 标签之间的文本：待收的参数值优先收掉，其余（块内空白）丢弃 */
        flushText();

        boolean closing = tagClosing;
        tagClosing = false;

        int i = skipBarsSpaces(inner, 0);
        if (i < inner.length() && inner.charAt(i) == '/') {
            closing = true;
            i = skipBarsSpaces(inner, i + 1);
        }
        int start = i;
        while (i < inner.length() && isNameChar(inner.charAt(i))) {
            i++;
        }
        String name = inner.substring(start, i).toLowerCase(Locale.ROOT);
        String rest = inner.substring(i);

        switch (name) {
            case "tool_calls", "toolcalls", "calls" -> {
                if (closing) {
                    finishBlock();
                } else {
                    startBlock();
                }
            }
            case "invoke", "function_call", "functioncall" -> {
                if (closing) {
                    finishCall();
                } else {
                    startCall(rest);
                }
            }
            case "parameter", "param" -> {
                if (!closing) {
                    startParam(rest);
                }
                /* 闭合：值已在 flushText 里收掉，无需再动作 */
            }
            default -> {
                /* 认不出的 DSML 标签：忽略，不改语义 */
            }
        }
    }

    private void startBlock() {
        finishCall();   // 上一块没收的调用先收掉
        implicitBlock = false;
        inBlock = true;
    }

    private void finishBlock() {
        finishCall();
        implicitBlock = false;
        inBlock = false;
    }

    private void startCall(String rest) {
        finishCall();
        /* 没有外层 calls 包裹时，一个 invoke 自己就是一个块——否则参数值会漏成正文 */
        if (!inBlock) {
            inBlock = true;
            implicitBlock = true;
        }
        callName = parseAttrs(rest).getOrDefault("name", "");
        callArgs = om.createObjectNode();
    }

    private void finishCall() {
        if (callName != null && !callName.isEmpty()) {
            ObjectNode call = om.createObjectNode();
            call.put("id", "call_dsml_" + UUID.randomUUID());
            call.put("type", "function");
            ObjectNode fn = call.putObject("function");
            fn.put("name", callName);
            fn.put("arguments", (callArgs == null ? om.createObjectNode() : callArgs).toString());
            calls.add(call);
        }
        callName = null;
        callArgs = null;
        paramName = null;
        if (implicitBlock) {
            implicitBlock = false;
            inBlock = false;
        }
    }

    private void startParam(String rest) {
        Map<String, String> attrs = parseAttrs(rest);
        paramName = attrs.get("name");
        paramString = "true".equalsIgnoreCase(attrs.getOrDefault("string", ""));
    }

    /** 收掉「开标签到现在」这段文本作为参数值 */
    private void flushText() {
        if (paramName != null) {
            setParam(paramName, text.toString());
            paramName = null;
        }
        text.setLength(0);
    }

    private void setParam(String name, String raw) {
        if (callArgs == null || name == null || name.isEmpty()) {
            return;
        }
        String value = raw.strip();
        callArgs.set(name, paramValue(value));
    }

    /** 参数值：声明 {@code string="true"} 当字符串；否则按 JSON 解析（数字 / 布尔 / 对象），失败退回字符串 */
    private JsonNode paramValue(String value) {
        if (paramString || value.isEmpty()) {
            return om.getNodeFactory().textNode(value);
        }
        try {
            JsonNode parsed = om.readTree(value);
            return parsed == null ? om.getNodeFactory().textNode(value) : parsed;
        } catch (Exception e) {
            return om.getNodeFactory().textNode(value);
        }
    }

    // ---------- 属性（无正则，纯扫描） ----------

    /** 解析标签属性：{@code name="x" string="true"} → 键值表（键与值都去掉首尾空白） */
    private static Map<String, String> parseAttrs(String rest) {
        Map<String, String> attrs = new LinkedHashMap<>();
        int i = 0;
        int n = rest.length();
        while (i < n) {
            i = skipBarsSpaces(rest, i);
            if (i >= n) {
                break;
            }
            int keyStart = i;
            while (i < n && rest.charAt(i) != '=' && rest.charAt(i) != ' '
                    && !isBar(rest.charAt(i))) {
                i++;
            }
            String key = rest.substring(keyStart, i).strip();
            i = skipSpaces(rest, i);
            if (i < n && rest.charAt(i) == '=') {
                i = skipSpaces(rest, i + 1);
                if (i < n && rest.charAt(i) == '"') {
                    int valueStart = ++i;
                    while (i < n && rest.charAt(i) != '"') {
                        i++;
                    }
                    attrs.put(key, rest.substring(valueStart, i));
                    i++;
                } else {
                    int valueStart = i;
                    while (i < n && rest.charAt(i) != ' ' && !isBar(rest.charAt(i))) {
                        i++;
                    }
                    attrs.put(key, rest.substring(valueStart, i));
                }
            } else if (!key.isEmpty()) {
                attrs.put(key, "");
            }
        }
        return attrs;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static int skipBarsSpaces(String s, int i) {
        while (i < s.length() && (isBar(s.charAt(i)) || s.charAt(i) == ' ')) {
            i++;
        }
        return i;
    }

    private static boolean isBar(char ch) {
        return ch == FULL_BAR || ch == '|';
    }

    private static boolean isNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
