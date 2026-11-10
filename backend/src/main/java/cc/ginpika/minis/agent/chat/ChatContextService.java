package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextFragment;
import cc.ginpika.minis.agent.context.ContextPlan;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.ai.core.AgentScope;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.chat.RequestTodoCompletionTool;
import cc.ginpika.minis.agent.chat.turn.History;
import cc.ginpika.minis.agent.chat.turn.Turn;
import cc.ginpika.minis.agent.chat.turn.TurnStore;
import cc.ginpika.minis.config.ContextProps;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.project.ProjectStore;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 组装单聊上下文及 Turn 预览；实际出站请求另有逐尝试记录。 */
@Service
public class ChatContextService {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChatContextService.class);
    private cc.ginpika.minis.agent.member.AgentStore agents;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAgents(cc.ginpika.minis.agent.member.AgentStore agents){this.agents=agents;}
    public String modelIdFor(String agentId,String requested){
        if(requested!=null && !requested.isBlank()){return requested;}
        return agents==null?requested:agents.peer(ChatSessionService.ownerOf(agentId))
                .map(cc.ginpika.minis.agent.member.AgentStore.AgentRow::modelId).orElse(null);
    }
    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    private final ObjectMapper om;
    private final SettingsService settings;
    private final AgentPromptService peerPrompts;
    private final ToolManager tools;
    private final ProjectStore projects;
    private final ContextBuilder context;
    private final ContextProps contextProps;
    private final History history;
    private final ChatContextSources sources;
    private final TurnStore turnStore;
    private final ChatScenes scenes;
    @Autowired
    private ChatContextStages stages;
    @Autowired(required = false)
    private ChatTodoApprovals todoApprovals;
    private static final long SAFETY_TOKENS = 1024;
    /** R5：历史正文预算随窗口放大的分母——百万级窗口按 1/32（约 31K）放宽，小窗口仍由下限兜底。 */
    static final int WINDOW_HISTORY_DIVISOR = 32;

    public ChatContextService(HubSessionStore sessions,
            ChatMessageStore messages,
            ObjectMapper om,
            SettingsService settings,
            AgentPromptService peerPrompts,
            ToolManager tools,
            ProjectStore projects,
            ContextBuilder context,
            ContextProps contextProps,
            History history,
            ChatContextSources sources,
            TurnStore turnStore,
            ChatScenes scenes) {
        this.sessions = sessions;
        this.messages = messages;
        this.om = om;
        this.settings = settings;
        this.peerPrompts = peerPrompts;
        this.tools = tools;
        this.projects = projects;
        this.context = context;
        this.contextProps = contextProps;
        this.history = history;
        this.sources = sources;
        this.turnStore = turnStore;
        this.scenes = scenes;
    }

    public ChatDtos.RawContext rawContext(String hubId) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        String modelId=modelIdFor(row.agentId(),row.modelId());
        ModelTarget target = settings.resolveChatModel(modelId);
        int window = settings.chatContextWindow(modelId);
        if (ephemeralContextEnabled() && "ai".equals(row.mode())) {
            ContextPlan plan = plannedRequestBody(row, target, row.promptId(), window, 0, 0,
                    continuation(row), todoApprovalsEnabled());
            ContextPlan.Fitted preview = plan.fit(plan.body());
            List<ChatDtos.TurnSpan> spans = preview.selection().selected().stream()
                    .filter(entry -> entry.source().startsWith("history/"))
                    .map(entry -> new ChatDtos.TurnSpan(entry.from(), entry.to(),
                            entry.source().substring("history/".length()), replyId(entry)))
                    .toList();
            return new ChatDtos.RawContext(preview.body(), spans);
        }
        ContextRequest req = contextRequest(row, target, row.promptId(), window, null, false);
        ObjectNode body = context.build(req);
        return new ChatDtos.RawContext(body, turnSpans(body, req));
    }

    public ChatDtos.TurnCalls turnCalls(String hubId, long replyMessageId) {
        String json = messages.contextOf(hubId, replyMessageId);
        List<ChatDtos.LlmCall> calls = ChatTraceCodec.readCalls(om, json);
        return new ChatDtos.TurnCalls(replyMessageId, calls == null ? List.of() : calls);
    }

    private List<ChatDtos.TurnSpan> turnSpans(ObjectNode body, ContextRequest req) {
        ArrayNode msgs = (ArrayNode) body.get("messages");
        List<History.TurnMessages> groups = history.turnMessages(req);
        int total = 0;
        for (History.TurnMessages g : groups) {
            total += g.messages().size();
        }
        int cursor = msgs.size() - total; // 跳过前置的 system（由 ContextBuilder 首条挂上）
        List<ChatDtos.TurnSpan> spans = new ArrayList<>();
        for (History.TurnMessages g : groups) {
            int from = cursor;
            int to = from + g.messages().size();
            Turn.Reply reply = g.turn().reply();
            Long replyId = reply == null ? null : reply.messageId();
            spans.add(new ChatDtos.TurnSpan(from, to, g.turn().trigger().source().name(), replyId));
            cursor = to;
        }
        return spans;
    }

    public ObjectNode requestBody(HubRow row, ModelTarget target, String promptId,
                                 int contextWindow) {
        return context.build(contextRequest(row, target, promptId, contextWindow, null, false));
    }

    public boolean ephemeralContextEnabled() {
        return contextProps.isEphemeralChat();
    }

    boolean todoApprovalsEnabled() {
        return ephemeralContextEnabled() && contextProps.isTodoApprovals();
    }

    /** 单聊当前活动任务段（R1）：Turn 开始前捕获。 */
    Long activeSceneId(String hubId) {
        return scenes == null ? null : scenes.activeSceneId(hubId);
    }

    ChatContextContinuation continuation(HubRow row) {
        return continuation(row, null);
    }

    ChatContextContinuation continuation(HubRow row, Long sceneId) {
        if (!contextProps.isStageContinuation()) {
            return null;
        }
        return sceneId == null || sceneId <= 0
                ? new ChatContextContinuation(row, om, sources, stages)
                : new ChatContextContinuation(row, sceneId, om, sources, stages);
    }

    ContextPlan plannedRequestBody(HubRow row, ModelTarget target, String promptId,
                                   int contextWindow, long currentUserId, long turnId,
                                   ChatContextContinuation continuation, boolean todoApproval) {
        String agentId = ChatSessionService.ownerOf(row.agentId());
        /* R1：本轮的任务段——Turn 已记录时读它；预览（turnId=0）用当前活动段。
           任务段活动时只装配该段内容；普通段只装配边界之后的非任务段内容。 */
        Long sceneId = turnId > 0 ? turnStore.sceneOf(row.id(), turnId) : activeSceneId(row.id());
        ChatScenes.SceneRow scene = sceneId == null ? null : scenes.find(row.id(), sceneId);
        long boundary = scenes.segmentBoundary(row.id());
        var visible = sources.segmentMessageIds(row.id(), agentId, row.projectId(), sceneId, boundary);
        String userQuery = sources.visibleUserText(row.id(), agentId, row.projectId(), currentUserId);
        if (currentUserId > 0 && (!visible.contains(currentUserId) || userQuery == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮用户消息已无法在当前会话读取");
        }
        var stage = continuation == null ? null
                : continuation.forPlan(turnId, contextProps.getCompressKeepTurns(), visible);
        ContextRequest request = contextRequest(row, target, promptId, contextWindow,
                userQuery == null ? "" : userQuery, todoApproval);
        List<ContextFragment> extras = sceneExtras(scene);
        /* R9：审批启用时装配小型结构化状态（待确认 / 已批准 / 已拒绝 / 已失效），
           模型判断以当前状态为准。 */
        if (todoApproval && todoApprovals != null) {
            String summary = todoApprovals.summary(row.id(), agentId);
            if (summary != null && !summary.isBlank()) {
                extras.add(ContextFragment.system(ContextFragment.Layer.TASK, "todo_approvals",
                        ContextFragment.Trust.AUTHORITATIVE_STATE, summary));
            }
        }
        ContextPlan.Window bounds = workingWindow(contextWindow, sceneId, row.id());
        if (log.isInfoEnabled()) {
            log.info("[ctx] assemble session={} turn={} scene={}({}) rev={} visible={} windowTurns={} "
                            + "windowCap={} continuation={} approval={}",
                    row.id(), turnId, sceneId == null ? 0 : sceneId,
                    scene == null || scene.name() == null ? "-" : scene.name(),
                    bounds.scopeRevision(), visible.size(), bounds.maxTurns(), bounds.tokenCap(),
                    stage != null, todoApproval);
        }
        // The approval targets a numbered item in the same Agent-owned list seen in the prompt.
        return AgentScope.run(todoApproval ? agentId : null, () -> context.plan(request, contextWindow,
                contextProps.getReserveOutputTokens(), currentUserId, visible, stage, extras, bounds));
    }

    /** R1：任务段的临时角色与确认约束——必带片段，仅在本任务段内生效，不改动全局 Agent 提示词。 */
    private List<ContextFragment> sceneExtras(ChatScenes.SceneRow scene) {
        List<ContextFragment> extras = new ArrayList<>();
        if (scene == null) {
            return extras;
        }
        StringBuilder text = new StringBuilder();
        if (scene.rolePrompt() != null && !scene.rolePrompt().isBlank()) {
            text.append("【当前任务角色】以下临时角色仅在本任务段内生效，结束或切换后退出默认回放，")
                    .append("不改动用户为 Agent 设置的全局提示词：\n").append(scene.rolePrompt()).append('\n');
        }
        if (scene.constraints() != null && !scene.constraints().isBlank()) {
            text.append("【当前任务约束】用户确认的任务约束，仅在本任务段内生效：\n").append(scene.constraints());
        }
        if (!text.isEmpty()) {
            extras.add(ContextFragment.system(ContextFragment.Layer.TASK, "scene_role",
                    ContextFragment.Trust.INSTRUCTION, text.toString().stripTrailing()));
        }
        return extras;
    }

    /** R5：默认工作窗口（独立灰度）；R7：场景与授权代号随 Selection 进 Manifest。 */
    private ContextPlan.Window workingWindow(int contextWindow, Long sceneId, String hubId) {
        long revision = sessions.contextScopeRevision(hubId);
        long scene = sceneId == null ? 0 : sceneId;
        if (!contextProps.isBoundedWorkingWindow()) {
            return new ContextPlan.Window(0, 0, scene, revision);
        }
        long cap = historyTokenCap(contextWindow, contextProps.getReserveOutputTokens(),
                contextProps.getWorkingWindowSharePercent(), contextProps.getWorkingWindowTokenCap());
        return new ContextPlan.Window(Math.max(0, contextProps.getWorkingWindowTurns()), cap, scene, revision);
    }

    /**
     * R5：历史正文预算 = min(可用输入预算 × 占比, max(下限, 窗口 / {@value #WINDOW_HISTORY_DIVISOR}))。
     *
     * <p>小窗口（≤256K）按下限兜底，与试点初始值一致；大窗口（1M）按窗口的 1/32 自动放宽，
     * 但仍不超过占比上限——避免 8K 固定值在百万级窗口上只占 0.8%。
     */
    static long historyTokenCap(int contextWindow, long reserveOutputTokens, int sharePercent, long floor) {
        long safety = Math.max(1024L, (contextWindow + 19L) / 20L);
        long allowance = Math.max(0L, contextWindow - Math.max(0L, reserveOutputTokens) - safety);
        long scaled = Math.max(Math.max(0L, floor), Math.max(0L, contextWindow / WINDOW_HISTORY_DIVISOR));
        return Math.min(allowance * Math.max(0, sharePercent) / 100, scaled);
    }

    private static Long replyId(ContextPlan.Entry entry) {
        return entry.refs().stream().filter(ref -> "reply".equals(ref.kind()))
                .map(ContextFragment.Ref::value)
                .map(ref -> Long.valueOf(ref.substring("message:".length())))
                .findFirst().orElse(null);
    }

    private ContextRequest contextRequest(HubRow row, ModelTarget target, String promptId,
                                          int contextWindow, String plannedQuery, boolean todoApproval) {
        // 最近一条用户输入作为知识与记忆召回查询；工具面不随措辞变化。
        String userQuery = plannedQuery == null ? latestUserText(row) : plannedQuery;
        String systemBase = systemFor(row, promptId);
        /* 工具面全量常驻：CHAT 面的工具一次全给模型——不再按「一句话里的关键词」挑域。
           理由：按措辞裁剪会让模型在长任务中途突然失去工具（上一句说了「跑命令」、下一句「继续」
           就没了），也会让提示前缀缓存永远命不中。执行权仍由各工具自己的授权把关
           （如终端的目标启用 / 绑定名单）。 */
        ArrayNode toolSchemas = tools.toolsOf(ToolSurface.CHAT);
        if (plannedQuery != null) {
            toolSchemas = toolSchemas == null ? om.createArrayNode() : toolSchemas.deepCopy();
            toolSchemas.add(ChatContextSources.schema(om));
            if (contextProps.isStageContinuation()) {
                toolSchemas.add(ChatContextContinuation.schema(om));
            }
            if (todoApproval) {
                toolSchemas.add(RequestTodoCompletionTool.schema(om));
            }
        }
        /* 身份上下文（memory-v6 §11）：创作类项目 → FICTION（写侧永不入画像；读侧仍注入真实画像但标注） */
        boolean narrative = row.projectId() != null && !row.projectId().isBlank()
                && projects.isNarrative(row.projectId());
        return new ContextRequest(target.model(), systemBase, row.id(),
                row.agentId(), row.projectId(), null,
                row.contextTrace(), userQuery,
                historyBudget(contextWindow, systemBase, toolSchemas), toolSchemas,
                ContextRequest.Surface.CHAT, MemoryIdentity.ofNarrative(narrative));
    }

    private String systemFor(HubRow row, String promptId) {
        String agentId = ChatSessionService.ownerOf(row.agentId());
        return AgentDirectory.isBuiltin(agentId)
                ? peerPrompts.builtinSystem(promptId)
                : peerPrompts.system(agentId);
    }

    private int historyBudget(int contextWindow, String systemBase, ArrayNode toolSchemas) {
        long used = contextProps.getReserveOutputTokens() + SAFETY_TOKENS
                + TokenUsageService.estimateTokens(systemBase)
                + TokenUsageService.estimateTokens(toolSchemas == null ? "" : toolSchemas.toString());
        return (int) Math.max(0, contextWindow - used);
    }

    private String latestUserText(HubRow row) {
        List<MessageRow> rows = messages.listBySession(row.id());
        for (int i = rows.size() - 1; i >= 0; i--) {
            if ("user".equals(rows.get(i).role())) {
                return rows.get(i).content();
            }
        }
        return "";
    }
}
