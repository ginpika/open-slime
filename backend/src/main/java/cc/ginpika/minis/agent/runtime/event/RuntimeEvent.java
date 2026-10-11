package cc.ginpika.minis.agent.runtime.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 运行时事件（设计一 §六的事件协议）：<b>系统推进的基本单位</b>。
 *
 * <p>任何用户输入、成员发言、工具结果、任务状态变化、打断请求，都先成为一条 {@code RuntimeEvent}
 * 再进入运行时；调度器据事件判断「这影响了谁、哪些任务要暂停 / 取消 / 恢复 / 重新调度」。
 * Agent 只负责推理与提出行动，不决定整个系统何时停止、谁可以抢占谁。
 *
 * <p>与「消息」的区别：消息是内容，事件是<b>发生了什么</b>。一条事件可以只更新状态、也可以唤醒
 * 若干 Agent，还可以触发抢占——由订阅方按类型与优先级处理。
 *
 * @param eventId       全局唯一幂等键（重复投递同一条事件不会重复执行副作用）
 * @param type          事件类型（见 {@link RuntimeEventType}）
 * @param sessionId     归属会话（运行时按会话路由与订阅）
 * @param source        事件来源：{@code user} / 成员 id / {@code tool} / {@code runtime}
 * @param target        定向目标（成员 id；多个用逗号分隔）；null = 广播给该会话的所有订阅者
 * @param taskId        关联任务（可空）
 * @param priority      优先级（数值越大越优先）；构造时默认取类型的缺省值
 * @param correlationId 关联 id（通常是「人一次发言」的 runId，或话题 id），把同一因果链串起来
 * @param createdAt     发生时间（毫秒）
 * @param payload       事件数据（结构化；落库时序列化为 JSON）
 */
public record RuntimeEvent(String eventId, RuntimeEventType type, String sessionId, String source,
                           String target, String taskId, int priority, String correlationId,
                           long createdAt, Map<String, Object> payload) {

    /** 空载荷 */
    private static final Map<String, Object> EMPTY = Map.of();

    /** 新建一条事件（自动生成 id 与时间；优先级取类型缺省） */
    public static RuntimeEvent of(RuntimeEventType type, String sessionId, String source,
                                  String target, String taskId, String correlationId,
                                  Map<String, ?> payload) {
        return new RuntimeEvent(UUID.randomUUID().toString(), type, sessionId, source, target,
                taskId, type.priority(), correlationId, System.currentTimeMillis(),
                payload == null ? EMPTY : new LinkedHashMap<String, Object>(payload));
    }

    /** 广播事件（无定向目标） */
    public static RuntimeEvent of(RuntimeEventType type, String sessionId, String source,
                                  String correlationId, Map<String, ?> payload) {
        return of(type, sessionId, source, null, null, correlationId, payload);
    }

    /** 广播事件（无定向目标、无关联 id）：最简形态 */
    public static RuntimeEvent of(RuntimeEventType type, String sessionId, String source,
                                  Map<String, ?> payload) {
        return of(type, sessionId, source, null, null, null, payload);
    }

    /** 覆写优先级（例如用户明确取消高于普通讨论消息） */
    public RuntimeEvent withPriority(int value) {
        return new RuntimeEvent(eventId, type, sessionId, source, target, taskId, value,
                correlationId, createdAt, payload);
    }

    /** 一条载荷项的字符串取值（缺失 / null 返回 fallback） */
    public String text(String key, String fallback) {
        Object v = payload.get(key);
        return v == null ? fallback : String.valueOf(v);
    }
}
