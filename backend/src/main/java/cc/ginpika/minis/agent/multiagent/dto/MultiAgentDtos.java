package cc.ginpika.minis.agent.multiagent.dto;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import java.util.List;
import java.util.Map;

/**
 * 多智能体接口的传输对象：名册（配置面）与会话 / 消息 / 话题 / 事件（对话面）。
 *
 * <p>每位成员自己那套「对话」提示词由 {@code /api/multi-agent/peers/{id}/prompts} 读写（结构同
 * 提示词页）。此处 {@code promptId} 是加那套接口之前的遗留字段，已不参与解析，仅为兼容保留。
 */
public final class MultiAgentDtos {

    private MultiAgentDtos() {
    }

    // ---------- 名册（配置面） ----------

    /** 一个成员：头像 + 额外工具 + 绑定工作空间 + 模型 + 次序（提示词在它自己的 {@code /prompts} 接口里） */
    public record PeerView(String id, String name, String promptId, List<String> tools,
                           List<String> terminals, String modelId, int sort, String note,
                           String avatar) {
    }

    /** 新建 / 改写成员的请求体（改时只动非 null 字段；avatar 传空串 = 移除头像） */
    public record PeerRequest(String name, String promptId, List<String> tools,
                              List<String> terminals, String modelId, Integer sort, String note,
                              String avatar) {
    }

    /** 一个可授予智能体的工具（成员页「额外工具」的候选；终端工具由绑定工作空间单独授予，不在此列）。
     *  domain / domainLabel 是工具的业务领域（{@code ToolDomain}）：页面按它分 Tab 归类展示 */
    public record ToolOption(String name, String description, boolean writes,
                             String domain, String domainLabel) {
    }

    public record PeerDeleted(String id) {
    }

    /** 图谱人格锚点：这位成员在知识图谱里的第一人称坐标——锚点实体（types 含 ANCHOR）+ 它的一跳子图。
     *  未绑定、或锚点实体已不在图中时 anchor=null、graph 为空（前端据此显示「新建」入口） */
    public record AnchorView(String peerId, String anchorId, SearchDtos.Node anchor,
                             SearchDtos.Graph graph) {
    }

    // ---------- 会话（对话面） ----------

    /** 多智能体会话：带上当前话题与谁在说话，页面据此显示「讨论到哪了」 */
    public record SessionView(String id, String title, String modelId, String topicId,
                              String topicStatus, String floorPeer, List<String> memberIds,
                              long createdAt, Long updatedAt) {
    }

    /** 建会话时可以挑成员：不传 / 传空 = 全体名册（首页直接开说的那条路就是这种） */
    public record NewSessionRequest(List<String> memberIds) {
    }

    public record NewSession(String sessionId) {
    }

    /** 一条消息：role=user 是人的话，role=peer 是某位成员的发言 */
    public record MessageView(long id, String sessionId, String topicId, String role, String peerId,
                              String peerName, String content, String reasoning,
                              List<ChatDtos.TraceSegment> trace, String createdAt) {
    }

    /** 一条发言当时的原始上下文（组装出的 messages + tools 的 JSON 文本）；人的话没有，为 null */
    public record MessageContext(long messageId, String context) {
    }

    /**
     * 一次发言的请求体：input 是人对圆桌说的话。
     *
     * <p>{@code speakerStrategy} 是这一次要用的应答者策略标识（会话页「⋯」菜单选的，
     * 如 {@code rotation} / {@code desire}）；空 / 不认识就用服务端缺省。
     */
    public record SendRequest(String sessionId, String input, String modelId,
                              String speakerStrategy) {
    }

    /** 一次「人说话 + 成员轮流应答」的结果摘要（正文由 SSE 逐段推） */
    public record TurnResult(String sessionId, String topicId, String topicStatus, int speeches) {
    }

    // ---------- 话题与事件（状态机面） ----------

    /** 一次话题：状态 + 每个参与者的状态 + 发言权归属 */
    public record TopicView(String id, String sessionId, String status, String floorPeer,
                            int resolveRound, Map<String, String> states,
                            List<String> participants, long createdAt, Long closedAt) {
    }
}
