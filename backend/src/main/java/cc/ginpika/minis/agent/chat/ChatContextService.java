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
    @Autowired
    private ChatContextStages stages;
    private static final long SAFETY_TOKENS = 1024;

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
            ChatContextSources sources) {
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

    ChatContextContinuation continuation(HubRow row) {
        return contextProps.isStageContinuation() ? new ChatContextContinuation(row, om, sources, stages) : null;
    }

    ContextPlan plannedRequestBody(HubRow row, ModelTarget target, String promptId,
                                   int contextWindow, long currentUserId, long turnId,
                                   ChatContextContinuation continuation, boolean todoApproval) {
        String agentId = ChatSessionService.ownerOf(row.agentId());
        var visible = sources.visibleMessageIds(row.id(), agentId, row.projectId());
        String userQuery = sources.visibleUserText(row.id(), agentId, row.projectId(), currentUserId);
        if (currentUserId > 0 && (!visible.contains(currentUserId) || userQuery == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本轮用户消息已无法在当前会话读取");
        }
        var stage = continuation == null ? null
                : continuation.forPlan(turnId, contextProps.getCompressKeepTurns(), visible);
        ContextRequest request = contextRequest(row, target, promptId, contextWindow,
                userQuery == null ? "" : userQuery, todoApproval);
        // The approval targets a numbered item in the same Agent-owned list seen in the prompt.
        return AgentScope.run(todoApproval ? agentId : null, () -> context.plan(request, contextWindow,
                contextProps.getReserveOutputTokens(), currentUserId, visible, stage));
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
