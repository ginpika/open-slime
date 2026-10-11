package cc.ginpika.minis.agent.member;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.ai.knowledge.dto.SearchDtos;
import java.util.List;
import java.util.Map;

/**
 * 多智能体接口的传输对象：名册（配置面）与会话 / 消息 / 话题 / 事件（对话面）。
 *
 * <p>每位成员自己那套「对话」提示词由 {@code /api/agents/peers/{id}/prompts} 读写（结构同
 * 提示词页）。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    // ---------- 名册（配置面） ----------

    /** 一个成员：头像 + builtin 标记 + 额外工具 + 绑定工作空间 + 模型 + 次序（提示词在它自己的 {@code /prompts} 接口里） */
    public record AgentView(String id, String name, boolean builtin, List<String> tools,
                           List<String> terminals, String modelId, int sort, String note,
                           String avatar, String role, String roleColor) {
    }

    /** 新建 / 改写成员的请求体（改时只动非 null 字段；avatar 传空串 = 移除头像） */
    public record AgentRequest(String name, List<String> tools,
                              List<String> terminals, String modelId, Integer sort, String note,
                              String avatar, String role, String roleColor) {
    }

    /**
     * 一个可授予智能体的工具（成员页「可用工具」的候选；终端工具由绑定工作空间单独授予，不在此列）。
     *
     * <p>{@code defaultGranted} 指该工具<b>已默认订阅</b>（只读检索那一批，无需人勾选就始终可用）——
     * 页面据此把它显示成已勾选并标注，好让「默认给哪些」这件事在配置面上可见。
     * {@code domain} / {@code domainLabel} 是工具的业务领域（{@code ToolDomain}）：页面按它分 Tab 归类展示。
     */
    public record ToolOption(String name, String description, boolean writes, boolean defaultGranted,
                             String domain, String domainLabel) {
    }

    public record AgentDeleted(String id) {
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
                              List<ChatDtos.TraceSegment> trace, String createdAt, String kind) {
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

    /**
     * 一次话题：状态 + 每个参与者的状态 + 发言权归属。
     *
     * <p>{@code states} 是轮次参与状态（单枚举，兼容既有前端）；{@code communications} /
     * {@code executions} 是同一份状态折叠出的<b>二维投影</b>（设计一 §四：发言状态 × 执行状态）。
     * 两者同源，不存在打架——二维只是把「不说话但在跑」这类场面表达出来。
     */
    public record TopicView(String id, String sessionId, String status, String floorPeer,
                            int resolveRound, Map<String, String> states,
                            Map<String, String> communications, Map<String, String> executions,
                            List<String> participants, long createdAt, Long closedAt) {
    }

    // ---------- 执行态图（页面「执行态」的数据源） ----------

    /** 一个智能体此刻的执行态：讨论态（communication）× 执行态（execution）+ 名下任务计数 + 角色标签 */
    public record WorkflowAgent(String id, String name, String communication, String execution,
                                boolean working, int pending, int doing, int done,
                                String role, String roleColor) {
    }

    /** 共享任务区里的一条 */
    public record WorkflowTodo(String id, String title, String detail, String status, String ownerPeer,
                               String ownerName, String createdBy, String createdByName, String result,
                               String blockReason, int seq, List<String> dependsOn) {
    }

    /**
     * 人对任务板一次干预（放弃 / 暂时跳过 / 立刻检查）的结果：改完之后的这条。
     * {@code ok=false} 表示没改（条目不存在，或该动作对它不适用）。
     */
    public record TodoPatch(boolean ok, String id, String status, int priority) {
    }

    /** 一张桌子当前的编排全貌：讨论节点 + 各成员的执行态 + 共享任务区 */
    public record WorkflowView(String sessionId, String title, String topicId, String topicStatus,
                               String floorPeer, boolean discussing, boolean advancing,
                               List<WorkflowAgent> agents, List<WorkflowTodo> todos) {
    }

    /**
     * 一次执行的过程留痕：执行态页面点开一条任务时回放「它到底在干嘛」。
     *
     * <p>没有它就只能看着任务卡在那里、不知道 Agent 在想什么——死循环的代价是真金白银。
     */
    public record WorkRunView(long id, String todoId, String peerId, String peerName, String status,
                              String output, String reasoning, List<ChatDtos.TraceSegment> trace,
                              String error, long startedAt, Long finishedAt) {
    }
}
