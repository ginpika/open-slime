package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.context.ContextBuilder;
import cc.ginpika.minis.agent.context.ContextRequest;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.member.AgentPromptService;
import cc.ginpika.minis.ai.core.MemoryIdentity;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 组装单聊上下文与逐 Turn 观测视图；预览和真实请求共用同一条路径。 */
@Service
public class ChatContextService {
    private final HubSessionStore sessions;
    private final ChatMessageStore messages;
    private final ObjectMapper om;
    private final SettingsService settings;
    private final PromptManager prompts;
    private final AgentPromptService peerPrompts;
    private final ToolManager tools;
    private final ProjectStore projects;
    private final ContextBuilder context;
    private final ContextProps contextProps;
    private final History history;
    private static final long SAFETY_TOKENS = 1024;

    public ChatContextService(HubSessionStore sessions,
            ChatMessageStore messages,
            ObjectMapper om,
            SettingsService settings,
            PromptManager prompts,
            AgentPromptService peerPrompts,
            ToolManager tools,
            ProjectStore projects,
            ContextBuilder context,
            ContextProps contextProps,
            History history) {
        this.sessions = sessions;
        this.messages = messages;
        this.om = om;
        this.settings = settings;
        this.prompts = prompts;
        this.peerPrompts = peerPrompts;
        this.tools = tools;
        this.projects = projects;
        this.context = context;
        this.contextProps = contextProps;
        this.history = history;
    }

    public ChatDtos.RawContext rawContext(String hubId) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        ModelTarget target = settings.resolveChatModel(row.modelId());
        ContextRequest req = contextRequest(row, target, row.promptId(),
                settings.chatContextWindow(row.modelId()));
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
        return context.build(contextRequest(row, target, promptId, contextWindow));
    }

    private ContextRequest contextRequest(HubRow row, ModelTarget target, String promptId,
                                          int contextWindow) {
        // 最近一条用户输入作为知识与记忆召回查询；工具面不随措辞变化。
        String userQuery = latestUserText(row);
        String systemBase = systemFor(row, promptId);
        /* 工具面全量常驻：CHAT 面的工具一次全给模型——不再按「一句话里的关键词」挑域。
           理由：按措辞裁剪会让模型在长任务中途突然失去工具（上一句说了「跑命令」、下一句「继续」
           就没了），也会让提示前缀缓存永远命不中。执行权仍由各工具自己的授权把关
           （如终端的目标启用 / 绑定名单）。 */
        ArrayNode toolSchemas = tools.toolsOf(ToolSurface.CHAT);
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
                ? prompts.chatSystem(promptId)
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
