package cc.ginpika.minis.agent.chat;

/**
 * 一批子代理全部跑完（该批收批）时发出的事件。
 *
 * <p>一次用户输入若派出了多个子代理，它们同属一批：每件跑完各自投回一条结论气泡，
 * 但「像秘书一样向用户总结」这件事只在整批都回来之后做一次——否则一个输入会触发
 * 模型总结好几次，把会话页刷屏。批量收批的时机在这里（见 {@code SubagentService#settle}），
 * 交给 {@code ChatService} 补那条整体总结。事件契约由订阅方（chat 包）持有。
 *
 * @param hubId 结论投回的会话
 * @param count 这一批真正跑起来的子代理数（直接写进总结指令，让模型知道要覆盖几件）
 */
public record SubagentBatchDoneEvent(String hubId, int count) {
}
