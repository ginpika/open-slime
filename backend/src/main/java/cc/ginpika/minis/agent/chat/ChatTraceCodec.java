package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行留痕的编解码：思考分段（trace）与工具轮原生消息（tool_rounds）的 JSON 序列化 / 反序列化。
 *
 * <p>这套格式原本有两份实现——{@code ChatService} 的 writeTrace / readTrace / writeRounds 与
 * {@code AgentRunner} 的 writeStr——同一个 JSON 形状各写一遍。收在这里，两条流共用一份。
 *
 * <p>纯函数：不持有状态，也不需要成为 bean（故为静态工具，调用方把 {@link ObjectMapper} 传进来）。
 *
 * <p>序列化失败一律降级为 null（不抛）：留痕是旁路，不该拖垮一次生成；反序列化失败由读方退回纯文本。
 */
public final class ChatTraceCodec {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceCodec.class);

    private ChatTraceCodec() {
    }

    /** 思考分段序列化为 JSON；无分段返回 null */
    public static String writeTrace(ObjectMapper om, List<ChatDtos.TraceSegment> trace) {
        return write(om, trace, "思考分段");
    }

    /** 工具轮原生消息序列化为 JSON；无工具轮返回 null */
    public static String writeRounds(ObjectMapper om, List<JsonNode> rounds) {
        return write(om, rounds, "工具轮消息");
    }

    /** 反序列化落库的思考分段；无 / 解析失败回退 null（前端退回纯文本思维链展示） */
    public static List<ChatDtos.TraceSegment> readTrace(ObjectMapper om, String trace) {
        if (trace == null || trace.isBlank()) {
            return null;
        }
        try {
            return om.readValue(trace, new TypeReference<List<ChatDtos.TraceSegment>>() {
            });
        } catch (Exception e) {
            log.warn("思考分段反序列化失败（回退纯文本思维链）: {}", e.getMessage());
            return null;
        }
    }

    /** 一个 Turn 的全部 LLM 调用留痕（请求体 + 响应体）序列化为 JSON；无则返回 null */
    public static String writeCalls(ObjectMapper om, List<ChatDtos.LlmCall> calls) {
        return write(om, calls, "LLM 调用留痕");
    }

    /** 反序列化落库的 LLM 调用留痕；无 / 解析失败回退 null */
    public static List<ChatDtos.LlmCall> readCalls(ObjectMapper om, String calls) {
        if (calls == null || calls.isBlank()) {
            return null;
        }
        try {
            return om.readValue(calls, new TypeReference<List<ChatDtos.LlmCall>>() {
            });
        } catch (Exception e) {
            log.warn("LLM 调用留痕反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static String write(ObjectMapper om, List<?> rows, String what) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("{}序列化失败（不落库，仅本次请求内生效）: {}", what, e.getMessage());
            return null;
        }
    }
}
