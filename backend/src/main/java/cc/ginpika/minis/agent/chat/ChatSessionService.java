package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;
import cc.ginpika.minis.agent.context.ChatProtocol;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.agent.runtime.TaskCheckpointService;
import cc.ginpika.minis.ai.memory.MemoryIngest;
import cc.ginpika.minis.ai.memory.store.MemoryRetractionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * 会话域：会话的增删改查、转录读取、回退，以及围绕它们的那几条规则。
 *
 * <p>会话管理与执行编排分开：
 * 这里不调模型、不跑工具、不参与循环；回退时同步撤回依赖被丢弃消息的记忆与工作状态。
 *
 * <p>规则集中在这里，不再散落于循环的各个调用点：
 * <ul>
 *   <li>配置的部分更新（仅更新请求里非 null 的项）；</li>
 *   <li>回退只认「本会话的一条 user 提问」，回答或别的会话的 id 一律拒；</li>
 *   <li>打开会话即清未读。</li>
 * </ul>
 *
 * <p><b>刻意不含</b>：运行时状态（在途生成 / 停止标志，见 {@link ChatLiveStore}）与对话循环
 * （见 {@link ChatTurnRunner}）。会话在这里是「事实」，不是「流程」。
 */
@Service
public class ChatSessionService {

    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    /** 列表要带上「该会话此刻是否在生成」，供前端在答完时弹通知 */
    private final ChatLiveStore live;
    /** 删除会话时一并清掉它的待办清单（plan-of-record），免得留下孤儿行 */
    private final TodoService todo;
    private final ObjectMapper om;
    private final TurnStore turns;
    private final MemoryIngest memoryIngest;
    private final MemoryRetractionStore memoryRetractions;
    private final TaskCheckpointService checkpoints;
    private ProjectWorkflowService projectWorkflows;
    private cc.ginpika.minis.project.ProjectProgressStore projectProgress;
    @org.springframework.beans.factory.annotation.Autowired
    public void setProjectProgress(cc.ginpika.minis.project.ProjectProgressStore progress) { projectProgress=progress; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setProjectWorkflows(ProjectWorkflowService workflows) { this.projectWorkflows=workflows; }

    public ChatSessionService(HubSessionStore sessions, ChatMessageStore messages,
                              ChatLiveStore live, TodoService todo, ObjectMapper om,
                              TurnStore turns, MemoryIngest memoryIngest,
                              MemoryRetractionStore memoryRetractions, TaskCheckpointService checkpoints) {
        this.sessions = sessions;
        this.messages = messages;
        this.live = live;
        this.todo = todo;
        this.om = om;
        this.turns = turns;
        this.memoryIngest = memoryIngest;
        this.memoryRetractions = memoryRetractions;
        this.checkpoints = checkpoints;
    }

    /** 会话列表（最近在前）：带「此刻是否在生成」标记 */
    public List<ChatDtos.SessionItem> list() {
        return sessions.listAll().stream()
                .map(row -> toItem(row, live.isRunning(row.id())))
                .toList();
    }

    /** 侧栏的最近消息摘要；可见范围与会话列表相同，读取不会清除未读标记。 */
    public Map<String, String> previews() {
        return messages.latestPreviews();
    }

    /** 新建一个空会话（无项目：记忆归属退化为 Agent 私有） */
    public ChatDtos.NewSession create() {
        return create(null);
    }

    /**
     * 新建会话并绑定项目（memory-v6 §7.1 / D1 修复）：项目是记忆归属与跨项目隔离的依据——
     * 有项目的会话，提炼出的记忆落 {@code PROJECT×L1}；无项目则落 {@code AGENT×L2}。
     */
    public ChatDtos.NewSession create(String projectId) {
        if (blankToNull(projectId)!=null) { projectWorkflows.validateProject(projectId); }
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null, blankToNull(projectId));
        return new ChatDtos.NewSession(hubId, blankToNull(projectId));
    }

    /** 绑定 / 解绑会话所属项目（切换后记忆读侧立即按新作用域生效；旧项目条目不删除、只是不可见） */
    public ChatDtos.NewSession setProject(String hubId, String projectId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        String pid = blankToNull(projectId);
        projectWorkflows.bind(hubId, pid);
        return new ChatDtos.NewSession(hubId, pid);
    }

    /**
     * 会话对话归属 Agent：{@code agent_id} 为空 = 内置 Agent。
     *
     * <p>这是<b>「按会话归属」数据的唯一口径</b>：待办清单的写入侧（{@code ChatTurnRunner#agentOf}）
     * 与读取侧（{@code cc.ginpika.minis.web.controller.ChatController#todos}）都委托本方法，两处不再各写一份 fallback——
     * 否则会话一旦在「…」菜单绑了某位成员，写进去的清单会读不回来。
     */
    public static String ownerOf(String agentId) {
        return (agentId == null || agentId.isBlank()) ? AgentDirectory.BUILTIN_ID : agentId;
    }

    /** 某会话的对话归属 Agent（读取该会话清单等按它定位）；会话不存在时回退内置 Agent */
    public String owner(String hubId) {
        return sessions.findById(hubId).map(r -> ownerOf(r.agentId()))
                .orElse(AgentDirectory.BUILTIN_ID);
    }

    /** 更新会话配置（「…」菜单在该会话的选择）：仅更新请求中非 null 的项，返回更新后的完整配置 */
    public ChatDtos.SessionConfig updateConfig(String hubId, ChatDtos.ConfigRequest req) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        String modelId = req == null ? null : req.modelId();
        String promptId = req == null ? null : req.promptId();
        String agentId = req == null ? null : req.agentId();
        Boolean showCot = req == null ? null : req.showCot();
        Boolean contextTrace = req == null ? null : req.contextTrace();
        Integer maxRounds = 0; // Ignore historical client limits; keep the wire/storage shape compatible.
        Boolean showRounds = req == null ? null : req.showRounds();
        sessions.updateConfig(row.id(), modelId, promptId, agentId, showCot, contextTrace, maxRounds,
                showRounds);
        return new ChatDtos.SessionConfig(row.id(),
                modelId != null ? modelId : row.modelId(),
                promptId != null ? promptId : row.promptId(),
                agentId != null ? (agentId.isBlank() ? null : agentId) : row.agentId(),
                showCot != null ? showCot : row.showCot(),
                contextTrace != null ? contextTrace : row.contextTrace(),
                0,
                showRounds != null ? showRounds : row.showRounds());
    }

    /** 删除会话：连同其消息正本与待办清单一并清除（不存在的会话返回 404） */
    @Transactional
    public ChatDtos.SessionDeleted delete(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        int removed = messages.deleteBySession(hubId);
        todo.clear(hubId);
        projectProgress.clear(hubId);
        projectWorkflows.clearProposal(hubId);
        sessions.delete(hubId);
        return new ChatDtos.SessionDeleted(hubId, removed);
    }

    /** 历史：仅 user/assistant 行（system prompt 不入库、不回显）；assistant 带思维链与分段供回放；
     *  子代理结论气泡带 kind 与元信息（标题/摘要/状态/执行 id），content 仍是完整结论（点开看全文）。
     *  打开会话即清未读。 */
    public List<ChatDtos.MessageRow> messages(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        sessions.clearUnread(hubId);
        return messages.listBySession(hubId).stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .map(m -> new ChatDtos.MessageRow(m.id(), m.role(), m.content(), m.reasoning(),
                        ChatTraceCodec.readTrace(om, m.trace()), m.kind(),
                        ChatProtocol.readSubagentBrief(om, m.kind(), m.meta()),
                        iso(m.createdAt())))
                .toList();
    }

    /**
     * 回退到某条提问之前：删除该条（含）及其后的全部消息，正本与上下文一并回到提问前，
     * 该条提问由前端放回输入框的未发送状态。
     *
     * <p>定位：messageId 优先；缺省时用 keep=保留的消息条数（发送失败时前端尚无落库 id）。
     */
    @Transactional
    public ChatDtos.Rewind rewind(String hubId, Long messageId, Integer keep) {
        var session = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        if ("multi-agent".equals(session.mode())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "多 Agent 会话请使用协作页的回退入口");
        if (live.isRunning(hubId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先停止当前回复再回退");
        }
        List<MessageRow> rows = messages.listBySession(hubId);
        MessageRow target;
        if (messageId != null) {
            target = rows.stream().filter(m -> messageId.equals(m.id())).findFirst().orElse(null);
        } else if (keep != null && keep >= 0 && keep < rows.size()) {
            target = rows.get(keep);
        } else {
            target = null;
        }
        /* 只认本会话（rows 即本会话）的一条提问：拿回答或别的会话的 id 一律拒绝，避免误删整段历史 */
        if (target == null || !"user".equals(target.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "回退位置无效（需为本会话的一条提问）: messageId=" + messageId + ", keep=" + keep);
        }
        List<Long> discarded = rows.stream().filter(m -> m.id() >= target.id()).map(MessageRow::id).toList();
        Set<Long> discardedIds = new HashSet<>(discarded);
        List<TurnStore.TurnRow> discardedTurns = turns.turns(hubId).stream()
                .filter(t -> discardedIds.contains(t.triggerMessageId()) || discardedIds.contains(t.replyMessageId()))
                .toList();
        List<Long> taskIds = discardedTurns.stream()
                .map(TurnStore.TurnRow::taskId)
                .filter(Objects::nonNull).distinct().toList();
        List<String> turnIds = discardedTurns.stream().map(t -> Long.toString(t.id())).toList();
        memoryRetractions.retractChat(hubId, discarded, taskIds, turnIds,
                target.createdAt() == null ? 0 : target.createdAt());
        taskIds.forEach(id -> checkpoints.save(id, null));
        int removed = messages.deleteFrom(hubId, target.id());
        // 数据库回退成功后再裁剪内存；写入前的来源校验同时挡住已经在提炼中的旧批次。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override public void afterCommit() { memoryIngest.rewind(hubId, discardedIds); }
                    });
        } else {
            memoryIngest.rewind(hubId, discardedIds);
        }
        return new ChatDtos.Rewind(hubId, removed);
    }

    /**
     * 分支：以某条消息为界，创建一个副本会话——把源会话到这条消息（含）为止的转录整段复制进新会话，
     * 并继承源会话的「…」菜单配置（模型 / 提示词 / Agent / CoT / 轮数等），此后两边的走向各自独立。
     *
     * <p>分支点只认本会话的一条消息（user 或 assistant）；拿别的会话或系统行的 id 一律拒。
     * 源会话本身不动（与回退相反：回退是删，分支是复制）。
     */
    @Transactional
    public ChatDtos.NewSession branch(String hubId, Long messageId) {
        HubRow src = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        if (messageId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少分支位置 messageId");
        }
        boolean found = messages.listBySession(hubId).stream().anyMatch(m -> messageId.equals(m.id())
                && ("user".equals(m.role()) || "assistant".equals(m.role())));
        if (!found) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分支位置无效（需为本会话的一条消息）: " + messageId);
        }
        String newId = UUID.randomUUID().toString();
        String base = (src.title() == null || src.title().isBlank()) ? "未命名会话" : src.title();
        /* 分支继承项目绑定：同一工作域内的分叉不该掉出项目（记忆归属随之一致） */
        sessions.insert(newId, base + " · 分支", src.projectId());
        messages.copyUpTo(hubId, newId, messageId);
        /* 继承会话级配置：模型 / 提示词 / Agent / CoT / 上下文留痕 / 轮次计数 */
        sessions.updateConfig(newId, src.modelId(), src.promptId(), src.agentId(), src.showCot(),
                src.contextTrace(), 0, src.showRounds());
        return new ChatDtos.NewSession(newId, src.projectId());
    }

    private static ChatDtos.SessionItem toItem(HubRow row, boolean generating) {
        return new ChatDtos.SessionItem(row.id(), row.title(), row.modelId(), row.promptId(), row.agentId(),
                row.projectId(), row.showCot(), row.contextTrace(), 0, row.showRounds(),
                row.unread(), generating, iso(row.updatedAt() != null ? row.updatedAt() : row.createdAt()));
    }

    /** 空串 / 空白 → null（项目绑定：空 = 无项目） */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String iso(Long ms) {
        return ms == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString();
    }
}
