package cc.ginpika.minis.agent.chat;

import java.util.List;

/**
 * 单个子代理跑完、结论要投回会话时发出的事件。
 *
 * <p>投回会话这件事（生成摘要气泡）需要会话上下文与模型调用，那是 {@code SubagentDeliveryService} 的职责；
 * 子代理这一侧只负责执行，跑完把「谁、跑出了什么」交出去，因此用事件解耦——否则
 * {@code SubagentService} 与 SubagentDeliveryService 会互相依赖成环。事件契约由订阅方（chat 包）持有。
 *
 * <p>只负责一件：为这个子代理落一条结论气泡（标题+摘要，点开看全文）。
 * 「整批都跑完后让主模型补一条秘书式总结」是另一件事，见 {@link SubagentBatchDoneEvent}。
 *
 * @param sourceRefs 本次执行获授权使用的来源引用（R3）：报告卡片据此记录持久依赖，
 *                   来源失效后报告退出模型范围。非作用域执行为空。
 */
public record SubagentReportEvent(String hubId, String title, long execId, String status,
                                  String output, String error, boolean contextScoped,
                                  List<String> sourceRefs) {
    public SubagentReportEvent(String hubId, String title, long execId, String status,
                               String output, String error) {
        this(hubId, title, execId, status, output, error, false, List.of());
    }

    public SubagentReportEvent(String hubId, String title, long execId, String status,
                               String output, String error, boolean contextScoped) {
        this(hubId, title, execId, status, output, error, contextScoped, List.of());
    }
}
