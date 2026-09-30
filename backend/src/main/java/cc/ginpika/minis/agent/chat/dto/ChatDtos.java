package cc.ginpika.minis.agent.chat.dto;

import java.util.List;

/** AI 会话请求/响应对（ai/ChatService ↔ 前端） */
public final class ChatDtos {

    /** POST /api/chat body：sessionId 缺省=最近会话或新建；modelId/promptId 缺省=设置页默认项 */
    public record ChatRequest(String sessionId, String input, String modelId, String promptId) {
    }

    /** POST /api/chat/stop body：sessionId=要停止生成的会话 */
    public record StopRequest(String sessionId) {
    }

    /** 停止结果：stopped=是否存在进行中的流式生成并被标记停止（false=该会话当前没有在生成的流） */
    public record StopResult(boolean stopped) {
    }

    /** 在途生成快照（断点续传用）：running=true 表示此刻还在生成；
     *  userText/userMessageId=本次提问原文与落库行 id（供前端续看时贴回列表、定位回退区间）；
     *  content/reasoning/trace=已产出的正文 / 思维链 / 结构化分段；error=失败原因（null=没失败）。
     *  该会话没有在途生成时返回 running=false 的空快照 */
    public record LiveRun(String sessionId, boolean running, String userText, Long userMessageId,
                          String content, String reasoning, List<TraceSegment> trace, String error) {
    }

    /** 补全结果（sessionId=本条消息实际落入的会话；messageId=本次提问落库的行 id，供回退定位；
     *  rawResponse 为流式聚合重组的 /chat/completions 等价 JSON，供调试/思维链扩展） */
    public record ChatReply(String sessionId, Long messageId, String message, String rawResponse,
                            Boolean success, String error) {
    }

    /** POST /api/sessions/{id}/rewind body：messageId=回退到该条提问之前（优先）；
     *  keep=仅保留前 N 条消息（发送失败时前端还没拿到落库 id，按序号兜底） */
    public record RewindRequest(Long messageId, Integer keep) {
    }

    /** 回退结果：removed=被删除的消息行数（含该条提问与其后的回答） */
    public record Rewind(String sessionId, int removed) {
    }

    /** GET/POST /api/sessions 项：id=hub 侧 uuid；modelId/promptId/showCot/contextTrace/maxRounds/showRounds
     *  为「…」菜单在该会话的配置（modelId/promptId 为 null 表示未指定、回退设置页默认项）；
     *  unread=潜意识子代理投递过新消息且用户未查看；
     *  generating=该会话此刻有在途生成（前端据此发现「刚答完」并弹通知，见 ChatLiveStore） */
    public record SessionItem(String id, String title, String modelId, String promptId, Boolean showCot,
                              Boolean contextTrace, Integer maxRounds, Boolean showRounds, Boolean unread,
                              boolean generating, String updatedAt) {
    }

    /** 会话配置（「…」菜单在该会话的选择）：modelId/promptId=null 回退设置页默认项；
     *  contextTrace=是否把历史思考与工具调用回灌上下文；maxRounds=单次提问的工具调用轮数上限；
     *  showRounds=思维链分段是否在右侧标轮次 */
    public record SessionConfig(String id, String modelId, String promptId, Boolean showCot,
                                Boolean contextTrace, Integer maxRounds, Boolean showRounds) {
    }

    /** PUT /api/sessions/{id}/config body：仅传需要改的字段，null 项保持原值 */
    public record ConfigRequest(String modelId, String promptId, Boolean showCot, Boolean contextTrace,
                                Integer maxRounds, Boolean showRounds) {
    }

    /** assistant 的一次思考分段：type=reasoning（普通思考，text=思考内容）|
     *  tool（工具调用，name=工具名，args=调用参数 JSON）；前端按段渲染折叠块。
     *  round=本段属于第几轮工具往返（0 基），供前端在每段右侧标轮次；
     *  早于该字段落库的旧数据为 null（前端按「每段思考开启新一轮」回推） */
    public record TraceSegment(String type, String text, String name, String args, Integer round) {
    }

    /** 子代理结论气泡的元信息（kind=subagent 时才有）：title=标题、summary=摘要
     *  （二者由模型把长报告压缩而来，失败时本地截取兜底）、status=done|failed、
     *  execId=执行 id（可在潜意识页按它回看检索过程） */
    public record SubagentBrief(String title, String summary, String status, Long execId) {
    }

    /** GET /api/sessions/{id}/messages 消息行（仅 user/assistant，按行序）；
     *  reasoning=assistant 的思维链纯文本（CoT，可能为 null）；trace=其结构化分段
     *  （可能为 null：非推理模型，或早于分段功能落库的旧消息），仅供回放展示；
     *  kind=消息形态（null=普通对话，subagent=子代理结论气泡）；content 恒为完整正文
     *  （子代理气泡的完整结论也在此，前端点开弹窗看它），subagent 为气泡元信息（普通对话为 null） */
    public record MessageRow(Long id, String role, String content, String reasoning,
                             List<TraceSegment> trace, String kind, SubagentBrief subagent,
                             String createdAt) {
    }

    /** POST /api/sessions 返回 */
    public record NewSession(String id) {
    }

    /** DELETE /api/sessions/{id} 返回：removed=一并删除的消息行数 */
    public record SessionDeleted(String sessionId, int removed) {
    }

    private ChatDtos() {
    }
}
