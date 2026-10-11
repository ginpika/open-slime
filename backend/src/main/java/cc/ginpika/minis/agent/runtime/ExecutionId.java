package cc.ginpika.minis.agent.runtime;

/**
 * 一次执行的标识（设计二 §三）：取消要有<b>独立的执行标识与取消语义</b>。
 *
 * <p>为什么不能只按会话取消：同一会话里可以先后 / 并发地跑多次执行，只按 sessionId 置停止标志
 * 会把「取消这一次」变成「取消该会话的一切」。有了执行标识，就能精确地只停指定的那一次——
 * 这与设计一「只取消受影响的工作、未受影响的继续」也一致。
 *
 * @param conversationId 归属会话
 * @param runId          这一次交互的关联 id（人的一句话 / 一次调度）
 * @param peerId         执行者（成员）；单聊的请求驱动执行可为空
 * @param revision       开始执行时的会话版本（提交前用它做版本校验，见设计一 §五-1）
 */
public record ExecutionId(String conversationId, String runId, String peerId, int revision) {

    public static ExecutionId of(String conversationId, String runId, String peerId, int revision) {
        return new ExecutionId(conversationId, runId, peerId, revision);
    }
}
