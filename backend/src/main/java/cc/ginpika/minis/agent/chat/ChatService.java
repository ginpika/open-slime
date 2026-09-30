package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.ai.knowledge.ingest.KnowledgeToolbox;
import cc.ginpika.minis.ai.terminal.TerminalService;
import cc.ginpika.minis.ai.llm.OpenAiChatClient;
import cc.ginpika.minis.ai.memory.MemoryWriter;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.runtime.ToolBatch;
import cc.ginpika.minis.ai.core.prompt.PromptManager;
import cc.ginpika.minis.ai.core.prompt.block.SubagentSummaryBlock;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import cc.ginpika.minis.ai.core.tool.subconscious.CreatePlanTool;
import cc.ginpika.minis.ai.core.tool.subconscious.DelegatePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.DelegateSubagentTool;
import cc.ginpika.minis.ai.core.tool.subconscious.DeletePlanTool;
import cc.ginpika.minis.ai.core.tool.subagent.ReadSubagentRunTool;
import cc.ginpika.minis.ai.core.tool.subconscious.UpdatePlanTool;
import cc.ginpika.minis.config.ModelTarget;
import cc.ginpika.minis.config.SettingsService;
import cc.ginpika.minis.stats.TokenUsageService;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore;
import cc.ginpika.minis.agent.chat.store.ChatMessageStore.MessageRow;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.chat.store.HubSessionStore.HubRow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 会话核心编排：消息正本落 SQLite，直连 OpenAI Compatible /chat/completions。
 *
 * <p>归属：M0 单用户，不区分 owner。历史全量带入上下文（个人场景会话长度有限）。
 *
 * <p>知识库工具：search_vector_chunks（三路召回 chunk：关键词+向量+图谱，附带图谱结构证据）与
 * 知识图谱查询（search_graph_entities 按关键词/语义查找实体、query_entity_relations 展开某实体的一跳关系、
 * query_entity_path 查询两实体间的最短路径、explore_subgraph 以若干实体为 seed 做 PPR 扩散
 * 一次取回多跳相关子图）；
 * 另提供图谱写入与编辑（upsert_node / upsert_edge 新增实体与关系；
 * edit_entity_props / edit_relation_props 增改删实体/关系的 KV 属性；
 * delete_graph_item 删除实体（连带其关系）或一条关系），
 * 以及文档工具（search_documents_by_title 按关键词匹配标题查文档清单；
 * read_document 按 id + 每页字符数 + 页码实时分页读正文，让模型自己决定读多少、读哪里；
 * create_document 把一段 markdown 正文新建为知识库文档，按标题/段落自动分块入库）。
 * 模型可在回答前调用，命中的 chunk / 图谱片段作为 tool 消息注入本轮上下文指导推理。
 * 每轮的思考与每次工具调用会作为结构化分段（trace）随 assistant 消息落库，供前端分阶段折叠回放。
 *
 * <p>历史回放默认只带 role + content；会话开启 context_trace（「…」菜单开关）时，assistant 的历史
 * 按原生协议回放（见 {@link #replayAssistant}）——该轮的工具往返（assistant 的
 * tool_calls 与按 tool_call_id 配对的 tool 结果，落库于 chat_message.tool_rounds）连同
 * reasoning_content 一并回放（超限的思考 / tool 结果 / 调用参数截断留痕，见 {@link #clipRound}），
 * 协议闭合、不向 content 注入任何标记文本。
 *
 * <p><b>@Primary 的来历</b>：多智能体那条流（{@code agent.multiagent.MaChatService}）继承本类，
 * 于是容器里有了两个 ChatService 类型的 bean。标本类为默认候选，按 ChatService 注入的既有
 * 消费方（ChatController 等）照旧拿到首页对话这一份；多智能体那一份只按它自己的类型注入。
 */
@Service
@Primary
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final int TITLE_MAX = 20;

    /** 工具往返轮数上限的合法区间：会话级可配（hub_session.max_rounds，「…」菜单），
     *  写入时收敛一下，避免手改请求把轮数设成 0（一轮都跑不了）或天文数字 */
    private static final int MIN_MAX_ROUNDS = 1;
    private static final int MAX_MAX_ROUNDS = 100;

    /** context_trace 开启时单段历史思考回灌上下文的字符上限（避免思维链过长挤爆上下文窗口） */
    private static final int CONTEXT_REASONING_LIMIT = 2000;

    /** context_trace 开启时单条历史 tool 结果 / 调用参数回灌上下文的字符上限：当轮已被模型消费过，
     *  跨请求回放只是留痕（记得调过什么、拿到了什么），超长正文（整页文档 / 新建文档的全文参数）
     *  截断即可——与 Claude Code 等主流 agent 压缩历史工具往返（microcompaction）同一思路 */
    private static final int CONTEXT_TOOL_LIMIT = 1200;

    /** 由潜意识模块执行的工具：任务计划那一套（派计划 + 立改撤编制） */
    private static final Set<String> PLAN_TOOLS = Set.of(
            DelegatePlanTool.NAME, CreatePlanTool.NAME, UpdatePlanTool.NAME, DeletePlanTool.NAME);

    /** 由子代理模块执行的工具：派一个匿名子代理 + 按 id 翻任意一次执行的过程 */
    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            DelegateSubagentTool.NAME, ReadSubagentRunTool.NAME);

    private final SettingsService settings;
    private final OpenAiChatClient client;
    protected final HubSessionStore sessions;
    protected final ChatMessageStore messages;
    protected final ObjectMapper om;
    protected final PromptManager prompts;
    protected final ToolManager tools;
    protected final KnowledgeToolbox toolbox;
    private final SubconsciousTool subconscious;
    private final SubagentTool subagent;
    /** 命令行终端：多智能体那条流要按成员绑定的工作空间收窄授权，故与 tools 一样对子类放开 */
    protected final TerminalService terminal;
    private final MemoryWriter memory;
    /** 在途生成仓：列表里带上「该会话此刻是否在生成」，供前端在答完时弹通知 */
    private final ChatLiveStore live;

    /* 以下成员与下面几个 protected 方法同为一组「可覆盖点」：只为多智能体那条流
       （agent.multiagent.MaChatService）而放宽，它换掉会话/消息的落点与上下文的组装方式，
       但整条流循环（流式、工具往返、trace、停止标志）一字不改地复用。既有行为零变化。 */

    /** 进行中的流式会话 → 停止标志：前端「停止」按钮按会话 id 置位，
     *  生成线程在增量/轮次边界检测到即中断（与 ClientGone 同为异常解栈，终止上游 LLM 读取） */
    private final ConcurrentHashMap<String, AtomicBoolean> activeStops = new ConcurrentHashMap<>();

    public ChatService(SettingsService settings, OpenAiChatClient client,
                       HubSessionStore sessions, ChatMessageStore messages,
                       ObjectMapper om, PromptManager prompts, ToolManager tools,
                       KnowledgeToolbox toolbox, SubconsciousTool subconscious,
                       SubagentTool subagent, TerminalService terminal, MemoryWriter memory,
                       ChatLiveStore live) {
        this.settings = settings;
        this.client = client;
        this.sessions = sessions;
        this.messages = messages;
        this.om = om;
        this.prompts = prompts;
        this.tools = tools;
        this.toolbox = toolbox;
        this.subconscious = subconscious;
        this.subagent = subagent;
        this.terminal = terminal;
        this.memory = memory;
        this.live = live;
    }

    public List<ChatDtos.SessionItem> list() {
        return sessions.listAll().stream()
                .map(row -> toItem(row, live.isRunning(row.id())))
                .toList();
    }

    public ChatDtos.NewSession create() {
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null);
        return new ChatDtos.NewSession(hubId);
    }

    /** 更新会话配置（「…」菜单在该会话的选择）：仅更新请求中非 null 的项，返回更新后的完整配置 */
    public ChatDtos.SessionConfig updateConfig(String hubId, ChatDtos.ConfigRequest req) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        String modelId = req == null ? null : req.modelId();
        String promptId = req == null ? null : req.promptId();
        Boolean showCot = req == null ? null : req.showCot();
        Boolean contextTrace = req == null ? null : req.contextTrace();
        Integer maxRounds = req == null || req.maxRounds() == null ? null
                : Math.max(MIN_MAX_ROUNDS, Math.min(MAX_MAX_ROUNDS, req.maxRounds()));
        Boolean showRounds = req == null ? null : req.showRounds();
        sessions.updateConfig(row.id(), modelId, promptId, showCot, contextTrace, maxRounds, showRounds);
        return new ChatDtos.SessionConfig(row.id(),
                modelId != null ? modelId : row.modelId(),
                promptId != null ? promptId : row.promptId(),
                showCot != null ? showCot : row.showCot(),
                contextTrace != null ? contextTrace : row.contextTrace(),
                maxRounds != null ? maxRounds : row.maxRounds(),
                showRounds != null ? showRounds : row.showRounds());
    }

    /** 发问：sessionId 缺省 → 最近会话（无则先建）；modelId/promptId 缺省 → 设置页默认项；
     *  user/assistant 消息均落本地库 */
    public ChatDtos.ChatReply chat(String hubId, String input, String modelId, String promptId) {
        return runChat(hubId, input, modelId, promptId, null);
    }

    /**
     * 流式发问：与 {@link #chat} 同流程，另外把各阶段实时回调给 sink——user 落库后的
     * meta（供前端定位回退区间）、每轮开始的 round（工具轮边界，前端据此清掉上一轮的正文）、
     * 以及模型逐段产出的 delta（reasoning/content，用于实时思维链与逐字渲染）。
     * 返回最终回复，由调用方推 done 事件。
     */
    public ChatDtos.ChatReply chatStream(String hubId, String input, String modelId, String promptId,
                                         StreamSink sink) {
        return runChat(hubId, input, modelId, promptId, sink);
    }

    /**
     * 标记该会话进行中的流式生成不再需要（前端「停止」按钮）：生成线程在下一个增量或
     * 轮次边界中断，停止等待/消费 LLM 响应；已产出的部分回答照常落库。
     *
     * @return 是否存在进行中的流式生成并被标记
     */
    public boolean requestStop(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return false;
        }
        AtomicBoolean flag = activeStops.get(hubId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    /** 用户主动停止且无任何正文产出：向上抛出中断生成，Controller 静默收尾（不推 error 事件） */
    public static final class ChatCancelledException extends RuntimeException {
    }

    /** 停止标志已置位则抛 {@link ChatCancelledException}：沿 readStream 调用栈冒泡，终止上游读取 */
    private static void ensureRunning(AtomicBoolean stopFlag) {
        if (stopFlag != null && stopFlag.get()) {
            throw new ChatCancelledException();
        }
    }

    /** 流式阶段回调（按发生顺序调用）；默认空实现，调用方只覆写关心的阶段 */
    public interface StreamSink {
        /** user 消息已落库：sessionId=实际会话，messageId=该条提问的行 id */
        default void meta(String sessionId, long messageId) {
        }

        /** 进入第 round 轮补全（>0 为工具轮之后的新一轮，前端应清空已渲染的正文） */
        default void round(int round) {
        }

        /** 模型产出的增量片段：reasoning=思维链、content=正文，缺一为 null */
        default void delta(String reasoning, String content) {
        }

        /** 模型请求调用工具：name=工具名，args=调用参数（JSON 字符串） */
        default void tool(String name, String args) {
        }

        /**
         * 又开口说了一段：text 就是这一段话本身。
         *
         * <p><b>只有圆桌用得上</b>（默认空实现，对话页不会收到）：那里成员可以边说边想，
         * 一次发言由好几个段落组成——每段各占一个气泡，所以「一段的边界」得有个独立信号，
         * 不能混在 {@link #delta} 里靠换行去猜。它一旦出现，此前的正文就到此为止。
         */
        default void speech(String text) {
        }
    }

    private ChatDtos.ChatReply runChat(String hubId, String input, String modelId, String promptId,
                                       StreamSink sink) {
        ModelTarget target = settings.resolveChatModel(modelId);
        if (!target.configured()) {
            throw new IllegalStateException(
                    "LLM 未配置：请在设置页添加模型，或在 .env 填写 "
                            + "LLM_BASE_URL / LLM_API_KEY / LLM_CHAT_MODEL");
        }
        HubRow row = resolveOrCreate(hubId);
        /* 流式请求登记停止标志（非流式不参与停止）：requestStop 按会话 id 置位，
            生成线程在增量与轮次边界检测；finally 里注销，避免残留标志误停下一次发送 */
        AtomicBoolean stopFlag = sink == null ? null
                : activeStops.computeIfAbsent(row.id(), k -> new AtomicBoolean(false));
        /* 记住本次使用的模型/提示词（会话级「…」菜单配置），下次进入该会话自动回显 */
        rememberConfig(row, modelId, promptId);
        /* 本次提问的工具往返上限：会话级「…」菜单配置（缺省 30），超限后撤掉 tools 强制出最终回答 */
        int maxRounds = Math.max(MIN_MAX_ROUNDS, row.maxRounds());

        long userMsgId = recordUserInput(row, input);
        if (sink != null) {
            sink.meta(row.id(), userMsgId);
        }

        /* 请求体：system + 全量历史（含刚落库的本次 user）+ 知识库工具（向量检索 + 图谱查询）；
           组装规则与「查看原始上下文」共用同一份（见 requestBody） */
        ObjectNode body = requestBody(row, target, promptId);
        ArrayNode msgs = (ArrayNode) body.get("messages");
        ObjectNode noTools = body.deepCopy();
        noTools.remove("tools");

        /* 思维链跨轮拼接：工具轮的思考与后续轮次用分隔线串成一条链，实时推送与落库同一规则；
         * 另按「每轮思考 / 每次工具调用」序列化为结构化分段（trace），供前端分阶段折叠回放；
         * 工具往返的原生消息（assistant 含 tool_calls + 配对 tool 结果）收进 toolRounds 一并落库，
         * 供后续请求按原生协议原样回放（context_trace 开启时） */
        StringBuilder reasoningChain = new StringBuilder();
        List<ChatDtos.TraceSegment> trace = new ArrayList<>();
        List<JsonNode> toolRounds = new ArrayList<>();
        /* 本次输入派出的子代理归属的批次：懒开、方法末尾统一收；用数组承载，供循环内的 lambda 捕获 */
        String[] subagentBatch = {null};
        boolean[] anyReasoning = {false};
        /* 本轮已流出的部分正文/思考（逐轮重置，最终答复以末轮为准）：用户停止时以部分回答落库 */
        StringBuilder partialContent = new StringBuilder();
        StringBuilder partialReasoning = new StringBuilder();
        OpenAiChatClient.DeltaListener base = sink == null ? null : (r, c) -> {
            ensureRunning(stopFlag); // 用户已停止：抛出解栈，不再等待/消费后续 LLM 输出
            if (r != null) {
                partialReasoning.append(r);
            }
            if (c != null) {
                partialContent.append(c);
            }
            sink.delta(r, c);
        };
        String finalContent = null;
        String finalRawResponse = null;
        String lastRawResponse = null;
        /* 当前轮次（0 基）：循环内随 round 更新；用户中途停止时按它给部分思考分段标轮次 */
        int currentRound = 0;
        try {
            for (int round = 0; round <= maxRounds; round++) {
                currentRound = round;
                ensureRunning(stopFlag); // 上一轮工具执行期间用户可能已停止
                if (sink != null) {
                    sink.round(round);
                }
                /* 部分输出按轮累计：正文以停止所在轮为准（与最终答复取末轮一致） */
                partialContent.setLength(0);
                partialReasoning.setLength(0);
                /* 本轮首个 reasoning 分片：跨轮时补分隔线（首次思考不补），与下方落库拼接保持一致 */
                boolean[] roundReasoning = {false};
                OpenAiChatClient.DeltaListener listener = base == null ? null
                        : (r, c) -> {
                            if (r == null) {
                                base.onDelta(null, c);
                                return;
                            }
                            String out = r;
                            if (!roundReasoning[0]) {
                                roundReasoning[0] = true;
                                if (anyReasoning[0]) {
                                    out = ChatTrace.REASONING_SEPARATOR + r;
                                }
                                anyReasoning[0] = true;
                            }
                            base.onDelta(out, c);
                        };
                /* 最后一轮撤掉 tools，强制模型给出最终回答 */
                OpenAiChatClient.RawResult raw;
                try {
                    raw = client.chatRaw(target, round < maxRounds ? body : noTools, listener,
                            TokenUsageService.SOURCE_CHAT);
                } catch (RuntimeException e) {
                    /* 供应商拒收时把「这次到底发了什么」记一行结构摘要（不含正文）：
                       思考模式的 reasoning_content 缺失那类 400 只给一句模糊错误，
                       没有出站形态根本无从下手（踩过两次） */
                    log.warn("补全被拒：session={} round={}/{} 带工具={} error={}｜出站形态={}",
                            row.id(), round, maxRounds, round < maxRounds, e.getMessage(),
                            digest(round < maxRounds ? body : noTools));
                    throw e;
                }
                lastRawResponse = raw.rawResponse();
                JsonNode message = raw.message();
                /* 累计本轮思维链（工具轮的思考也留痕，供链式回放），并落为一段 reasoning 分段 */
                JsonNode reasoning = message.get("reasoning_content");
                if (reasoning != null && !reasoning.asText().isBlank()) {
                    String text = reasoning.asText();
                    if (reasoningChain.length() > 0) {
                        reasoningChain.append(ChatTrace.REASONING_SEPARATOR);
                    }
                    reasoningChain.append(text);
                    trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING, text, null, null, round));
                }
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = message.path("content").asText(null);
                    if (content == null || content.isBlank()) {
                        /* 兜底：部分推理模型/端点在正文为空时把整段回答放进了 reasoning_content
                         * （deepseek-flash 实测：content=null、reasoning_content 为完整回答），
                         * 此时以本轮思考内容作为回答，避免整轮失败丢弃已生成的内容 */
                        String fallback = message.path("reasoning_content").asText("");
                        if (fallback.isBlank()) {
                            /* 仅出错时记录模型原始返回，便于回溯（含 reasoning_content / 空 content 形态） */
                            log.error("模型返回空 content（本轮无 tool_calls）：session={} round={}/{} messages={} "
                                            + "requestChars={} raw={}",
                                    row.id(), round, maxRounds, msgs.size(),
                                    body.toString().length(), lastRawResponse);
                            throw new IllegalStateException("响应无 choices[0].message.content");
                        }
                        log.warn("模型未返回 content，回退用 reasoning_content 作为回答：session={} round={}/{} "
                                        + "chars={} raw={}",
                                row.id(), round, maxRounds, fallback.length(), lastRawResponse);
                        content = fallback;
                    }
                    finalContent = content;
                    finalRawResponse = raw.rawResponse();
                    break;
                }
                /* 模型请求调用工具：先把这一批的「调用」事件与 trace 分段按顺序推出去（界面立刻看到
                 * 在调什么），再执行——全是子代理时并发跑；最后按原顺序把 tool 结果续在 msgs 上。
                 * 子代理的调用只当场回一张受理回执（真正干活在后台，跑完投回本会话），
                 * 所以这一轮不会被它占住。assistant 消息（含 tool_calls）与 tool 结果都收进
                 * toolRounds 供跨请求原样回放 */
                JsonNode assistantRound = withReasoningForToolCalls(message.deepCopy());
                msgs.add(assistantRound);
                toolRounds.add(assistantRound);
                List<JsonNode> calls = new ArrayList<>();
                toolCalls.forEach(calls::add);
                for (JsonNode call : calls) {
                    String toolName = call.path("function").path("name").asText("");
                    String toolArgs = call.path("function").path("arguments").asText("");
                    trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_TOOL, null, toolName, toolArgs, round));
                    if (sink != null) {
                        sink.tool(toolName, toolArgs);
                    }
                }
                /* 本次输入派出去的子代理同属一批 run：页面「最近几批」不被逐个调用刷屏，
                   整批跑完也只补一条整体总结。首批出现子代理调用时开批，收批统一在方法末尾的
                   finally（不能一开一收在各轮里做，否则跨轮派活会被拆成多批、总结多次） */
                if (subagentBatch[0] == null && calls.stream().anyMatch(ChatService::isSubagentCall)) {
                    subagentBatch[0] = subagent.openRun("chat");
                }
                List<String> contents = ToolBatch.run(calls, tools::isParallelizable,
                        call -> runTool(call, subagentBatch[0], row.id()));
                for (int i = 0; i < calls.size(); i++) {
                    ObjectNode toolMsg = msgs.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", calls.get(i).path("id").asText(""))
                            .put("content", contents.get(i));
                    toolRounds.add(toolMsg);
                }
            }
        } catch (ChatCancelledException cancelled) {
            /* 用户停止：已有部分正文则以部分回答落库（思维链与工具轮留痕一并带上），
               刷新后对话完整保留；尚无任何正文则原样上抛，由 Controller 静默收尾 */
            String partial = partialContent.toString();
            if (partial.isBlank()) {
                throw cancelled;
            }
            String partialReason = partialReasoning.toString();
            if (!partialReason.isBlank()) {
                if (reasoningChain.length() > 0) {
                    reasoningChain.append(ChatTrace.REASONING_SEPARATOR);
                }
                reasoningChain.append(partialReason);
                trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING, partialReason, null, null,
                        currentRound));
            }
            String cancelledReasoning = reasoningChain.length() == 0 ? null : reasoningChain.toString();
            recordReply(row, partial, cancelledReasoning, writeTrace(trace), writeRounds(toolRounds));
            touchSession(row, abbreviate(input));
            return new ChatDtos.ChatReply(row.id(), userMsgId, partial, lastRawResponse, true, null);
        } finally {
            /* 注销停止标志：只移除自己登记的条目（同会话新流已注册新条目时不误删） */
            if (stopFlag != null) {
                activeStops.remove(row.id(), stopFlag);
            }
            /* 收批：派出去的子代理还在后台跑，这里只挂个账，等它们各自回报完才真正收——
               收批同时触发一次整体总结（见 SubagentService#settle / onSubagentBatchDone） */
            if (subagentBatch[0] != null) {
                subagent.closeRun(subagentBatch[0]);
            }
        }
        if (finalContent == null) {
            log.error("工具调用轮数超限：session={} messages={} requestChars={} lastRaw={}",
                    row.id(), msgs.size(), body.toString().length(), lastRawResponse);
            throw new IllegalStateException("工具调用轮数超限（" + maxRounds + "）");
        }

        String reasoningText = reasoningChain.length() == 0 ? null : reasoningChain.toString();
        recordReply(row, finalContent, reasoningText, writeTrace(trace), writeRounds(toolRounds));
        touchSession(row, abbreviate(input));

        return new ChatDtos.ChatReply(row.id(), userMsgId, finalContent, finalRawResponse, true, null);
    }

    /**
     * 查看会话的原始上下文：按与真实发送完全一致的规则组装一份 /chat/completions 请求体
     * （model + system + 全量历史回放 + tools，见 {@link #requestBody}），但不发送、不落库——
     * 展示「此刻在这个会话再发一条消息时，系统发给大模型接口的内容」。
     */
    public ObjectNode rawContext(String hubId) {
        HubRow row = sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        ModelTarget target = settings.resolveChatModel(row.modelId());
        return requestBody(row, target, row.promptId());
    }

    /** 删除会话：连同其消息正本一并清除（不存在的会话返回 404） */
    @Transactional
    public ChatDtos.SessionDeleted delete(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        int removed = messages.deleteBySession(hubId);
        sessions.delete(hubId);
        return new ChatDtos.SessionDeleted(hubId, removed);
    }

    /** 历史：仅 user/assistant 行（system prompt 不入库、不回显）；assistant 带思维链与分段供回放；
     *  子代理结论气泡带 kind 与元信息（标题/摘要/状态/执行 id），content 仍是完整结论（点开看全文） */
    public List<ChatDtos.MessageRow> messages(String hubId) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
        sessions.clearUnread(hubId);
        return messages.listBySession(hubId).stream()
                .filter(m -> "user".equals(m.role()) || "assistant".equals(m.role()))
                .map(m -> new ChatDtos.MessageRow(m.id(), m.role(), m.content(), m.reasoning(),
                        readTrace(m.trace()), m.kind(), readBrief(m.kind(), m.meta()),
                        iso(m.createdAt())))
                .toList();
    }

    /**
     * 一个子代理跑完，把它的结论落成一条气泡消息（{@link cc.ginpika.minis.agent.subagent.SubagentService}
     * 执行完发来的事件）：先把长报告压成「标题 + 摘要」（一次模型调用），再以 {@code kind=subagent}
     * 落库——前端只显示标题与摘要，点开才看 {@code content} 里的全文。
     *
     * <p>这里不打未读点：一次输入可能派出多个子代理，要等整批都回来才算「有结果可看」，
     * 未读点与总结统一由 {@link #onSubagentBatchDone} 在收批时做（见 {@code SubagentService#settle}）。
     */
    @EventListener
    public void onSubagentReport(SubagentReportEvent event) {
        HubRow row = sessions.findById(event.hubId()).orElse(null);
        if (row == null) {
            log.warn("子代理「{}」结论投回失败：会话已不存在 {}", event.title(), event.hubId());
            return;
        }
        boolean done = "done".equals(event.status());
        String report = event.output() == null ? "" : event.output();
        ModelTarget target = settings.resolveChatModel(row.modelId());
        ChatDtos.SubagentBrief brief = brief(target, event, report);
        String body = done ? report : failedText(event.error());
        messages.insert(row.id(), "assistant", body, null, null, null,
                ChatMessageStore.KIND_SUBAGENT, writeBrief(brief));
    }

    /**
     * 一批子代理全部跑完（收批事件）：让主模型以本人（秘书）口吻补一条整体总结，接在这批气泡之后，
     * 并打未读点——首页「最近会话」提示，人正停在该会话里时前端轮询到未读即把这一组刷出来。
     *
     * <p>一次输入派出多个子代理时，总结只做这一次；各件结论已作为上面的气泡消息逐条投回，
     * 因此总结指令不重复报告全文（历史里就带着），只交代要覆盖几件、该怎么归纳。
     * 模型调用失败就跳过总结，未读点照打（气泡本身已可见）。
     */
    @EventListener
    public void onSubagentBatchDone(SubagentBatchDoneEvent event) {
        HubRow row = sessions.findById(event.hubId()).orElse(null);
        if (row == null) {
            log.warn("子代理整批总结投回失败：会话已不存在 {}", event.hubId());
            return;
        }
        ModelTarget target = settings.resolveChatModel(row.modelId());
        String secretary = secretarySummary(row, target, event.count());
        if (secretary != null && !secretary.isBlank()) {
            messages.insert(row.id(), "assistant", secretary, null, null, null, null, null);
        }
        sessions.touch(row.id(), null);
        sessions.markUnread(row.id());
    }

    /** 气泡的标题与摘要：让模型把长报告压成一张卡片；未完成或摘要生成失败时本地兜底，绝不让投回失败 */
    private ChatDtos.SubagentBrief brief(ModelTarget target, SubagentReportEvent event, String report) {
        if (!"done".equals(event.status())) {
            return new ChatDtos.SubagentBrief(event.title(), failedText(event.error()),
                    event.status(), event.execId());
        }
        try {
            String cardJson = client.chat(target, List.of(
                    new OpenAiChatClient.ChatMessage("system",
                            prompts.resolve(SubagentSummaryBlock.KEY)),
                    new OpenAiChatClient.ChatMessage("user", report)),
                    TokenUsageService.SOURCE_CHAT).content();
            JsonNode card = om.readTree(cardJson);
            String title = card == null ? "" : card.path("title").asText("").trim();
            String summary = card == null ? "" : card.path("summary").asText("").trim();
            if (summary.isEmpty()) {
                throw new IllegalStateException("模型未给出摘要");
            }
            return new ChatDtos.SubagentBrief(title.isEmpty() ? event.title() : title, summary,
                    event.status(), event.execId());
        } catch (Exception e) {
            log.warn("子代理「{}」摘要生成失败（退化为本地截取）: {}", event.title(), e.getMessage());
            return new ChatDtos.SubagentBrief(event.title(), fallbackSummary(report),
                    event.status(), event.execId());
        }
    }

    /**
     * 让主模型以本人（秘书）口吻补一条整体总结：输入 = 本会话既有上下文（系统提示词 + 历史，
     * 这批子代理的结论已作为气泡消息排在末尾）+ 一条「这批活都回来了」的指令。
     *
     * <p>不带 tools：这是纯转述，不需要检索。失败返回 null——跳过这条，气泡本身照常显示。
     */
    private String secretarySummary(HubRow row, ModelTarget target, int count) {
        try {
            List<OpenAiChatClient.ChatMessage> msgs = new ArrayList<>();
            msgs.add(new OpenAiChatClient.ChatMessage("system",
                    prompts.chatSystem(row.promptId())));
            for (MessageRow m : history(row)) {
                msgs.add(new OpenAiChatClient.ChatMessage(m.role(), m.content()));
            }
            msgs.add(new OpenAiChatClient.ChatMessage("user",
                    "你刚才派出去的 " + count + " 件活已经全部跑完，各自的结论都已作为上面的消息"
                            + "逐条投回本会话。请以你本人的口吻、像秘书向人汇报一样，用两三句话向用户"
                            + "做一次整体交代：这批活整体查到了什么、核心结论是什么、建议下一步做什么。"
                            + "不要逐条复述，不要罗列检索过程，不要用标题。"));
            return client.chat(target, msgs, TokenUsageService.SOURCE_CHAT).content();
        } catch (RuntimeException e) {
            log.warn("子代理整批总结生成失败（跳过这条）: {}", e.getMessage());
            return null;
        }
    }

    /** 未跑出结论时的说明文案（气泡摘要与正文共用同一句） */
    private static String failedText(String error) {
        return "它没有跑出结论：" + (error == null ? "未知原因" : error);
    }

    /** 本地兜底摘要：去掉 markdown 记号、压平空白后截一段（模型摘要生成失败时用） */
    private static String fallbackSummary(String report) {
        String flat = report.replaceAll("[#*>`\\[\\]()!_-]", " ").replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }

    /** 气泡元信息序列化为 JSON；序列化失败返回 null（前端据此只显示标题，不影响全文可见） */
    private String writeBrief(ChatDtos.SubagentBrief brief) {
        try {
            return om.writeValueAsString(brief);
        } catch (Exception e) {
            log.warn("子代理气泡元信息序列化失败（前端只显示标题）: {}", e.getMessage());
            return null;
        }
    }

    /** 反序列化子代理气泡的元信息；仅 kind=subagent 时有，普通对话或解析失败返回 null */
    private ChatDtos.SubagentBrief readBrief(String kind, String meta) {
        if (!ChatMessageStore.KIND_SUBAGENT.equals(kind) || meta == null || meta.isBlank()) {
            return null;
        }
        try {
            return om.readValue(meta, ChatDtos.SubagentBrief.class);
        } catch (Exception e) {
            log.warn("子代理气泡元信息反序列化失败（该气泡只显示标题）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 回退到某条提问之前：删除该条（含）及其后的全部消息，正本与上下文一并回到提问前，
     * 该条提问由前端放回输入框的未发送状态。
     *
     * <p>定位：messageId 优先；缺省时用 keep=保留的消息条数（发送失败时前端尚无落库 id）。
     */
    public ChatDtos.Rewind rewind(String hubId, Long messageId, Integer keep) {
        sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
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
        return new ChatDtos.Rewind(hubId, messages.deleteFrom(hubId, target.id()));
    }

    // ---------- 会话域门面：外围模块（潜意识 / 子代理）对 chat 数据的写入入口
    // 会话内容的写入收敛在 {@link ChatThreads}（潜意识投递）与本类的聊天流程（人机对话）；
    // 子代理结论投回经 SubagentReportEvent 事件。唯一例外是潜意识注入器的「最近对话」只读投影，
    // 它直接读 ChatMessageStore ----------

    // ---------- 私有 ----------

    /** 思考分段序列化为 JSON 落库；无分段（非推理模型直接回答）返回 null */
    protected String writeTrace(List<ChatDtos.TraceSegment> trace) {
        if (trace.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(trace);
        } catch (Exception e) {
            log.warn("思考分段序列化失败（trace 不落库，仍保留纯文本思维链）: {}", e.getMessage());
            return null;
        }
    }

    /** 反序列化落库的思考分段；无 / 解析失败回退 null（前端退回纯文本思维链展示） */
    private List<ChatDtos.TraceSegment> readTrace(String trace) {
        if (trace == null || trace.isBlank()) {
            return null;
        }
        try {
            return om.readValue(trace, new TypeReference<List<ChatDtos.TraceSegment>>() {
            });
        } catch (Exception e) {
            log.warn("思考分段反序列化失败（回退纯文本思维链）: {}", e.getMessage());
            return null;
        }
    }

    /** 工具轮原生消息（assistant 含 tool_calls + 配对 tool 结果）序列化为 JSON 落库；未调用工具返回 null */
    protected String writeRounds(List<JsonNode> rounds) {
        if (rounds.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(rounds);
        } catch (Exception e) {
            log.warn("工具轮消息序列化失败（不落库，仅本次请求内生效）: {}", e.getMessage());
            return null;
        }
    }

    /** 反序列化落库的工具轮原生消息；无 / 解析失败回退空列表（该轮按普通回答回放） */
    private List<JsonNode> readRounds(String toolRounds) {
        if (toolRounds == null || toolRounds.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(toolRounds, new TypeReference<List<JsonNode>>() {
            });
        } catch (Exception e) {
            log.warn("工具轮消息反序列化失败（该轮按普通回答回放）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 组装一次对话补全的请求体：model + system（身份提示词 + 对话增强 + 在跑的子代理）+
     * 全量历史回放（含调用前刚落库的本次 user）+ CHAT 工具面。
     *
     * <p>真实发送（{@link #runChat}）与「查看原始上下文」（{@link #rawContext}）共用本方法，
     * 保证预览与实际发出去的请求体完全一致。promptId 为本次发送指定的身份版本
     * （null 回退全局绑定）。
     *
     * <p><b>可覆盖点</b>：多智能体那条流覆盖本方法，把 system 换成「成员人格 + 协作规程」、
     * 全部历史压成唯一一条 user「公开记录 + 此刻局面」、工具面换成 PEER
     * （见 {@code agent.multiagent.MaChatService}）。
     */
    protected ObjectNode requestBody(HubRow row, ModelTarget target, String promptId) {
        ObjectNode body = om.createObjectNode();
        body.put("model", target.model());
        ArrayNode msgs = body.putArray("messages");
        /* system 消息 = 身份提示词（会话选定版本 / .env）+ 对话增强，身份在前、增强固定在后；
           末尾再挂上「本会话在跑的子代理」（有才挂）：主模型每轮都看得见派出去的活跑到哪一步了 */
        msgs.addObject().put("role", "system")
                .put("content", withActiveSubagents(prompts.chatSystem(promptId), row.id()));
        for (MessageRow m : history(row)) {
            /* 子代理结论不是主模型自己写的（子代理跑完投回的）：回放时正文前拼一行出处，
               归因跨轮不丢——在跑进度块只在子代理未回报期间出现，这里才是常驻的来源标记 */
            String content = subagentAttributed(m);
            /* 默认只回放 role + content；会话开启 context_trace（「…」菜单开关）时，assistant 的历史
               按原生协议回放：先原样重放该轮的工具往返，再补上回答与思考，让模型跨轮看到自己的推理与检索 */
            if (row.contextTrace() && "assistant".equals(m.role())) {
                replayAssistant(msgs, content, m.reasoning(), readRounds(m.toolRounds()));
            } else {
                msgs.addObject().put("role", m.role()).put("content", content);
            }
        }
        body.set("tools", tools.toolsOf(ToolSurface.CHAT));
        return body;
    }

    /**
     * 子代理结论 → 带来源头的回放正文：kind=subagent 的消息是子代理跑完投回的，不是主模型写的——
     * 回放时在正文前拼一行出处（标题 + 执行 id，取自 meta 里的气泡元信息），模型跨轮仍知道这段话
     * 来自哪件派出去的活，可正确转述出处、必要时按 id 用 read_subagent_run 核对，而不是把它当成
     * 自己说过的话（对齐 OpenClaw 等异步子代理「完成通知带归因」的做法）。
     * 普通消息原样返回；元信息缺失时退化为无标题/id 的通用标记，归因仍在。
     */
    private String subagentAttributed(MessageRow m) {
        if (!ChatMessageStore.KIND_SUBAGENT.equals(m.kind())) {
            return m.content();
        }
        ChatDtos.SubagentBrief brief = readBrief(m.kind(), m.meta());
        if (brief == null) {
            return "〔子代理的回报〕\n" + m.content();
        }
        StringBuilder head = new StringBuilder("〔子代理");
        if (brief.title() != null && !brief.title().isBlank()) {
            head.append("「").append(brief.title()).append('」');
        }
        head.append("的回报");
        if (brief.execId() != null) {
            head.append(" · 执行 id=").append(brief.execId());
        }
        return head.append("〕\n").append(m.content()).toString();
    }

    /**
     * 按原生协议把一条历史 assistant 消息追加到请求 messages（会话开启 context_trace 时使用）：
     * 先原样重放该轮的工具往返（落库的 assistant(tool_calls) 与按 tool_call_id 配对的 tool 结果，
     * 见 chat_message.tool_rounds），再补上本轮最终回答及其 reasoning_content——工具调用与结果
     * 成对闭合、思考按 provider 约定回填，模型看到的就是自己当初的真实消息序列。
     *
     * <p>工具轮缺省时（该轮未调用工具，或早于该功能落库的旧消息）只回放回答本身；回放只求协议
     * 闭合、留痕可读，不求原文全量：各段思考按 CONTEXT_REASONING_LIMIT、tool 结果与调用参数按
     * CONTEXT_TOOL_LIMIT 截断（见 {@link #clipRound}），协议字段（tool_calls / tool_call_id）保持原样。
     */
    private void replayAssistant(ArrayNode msgs, String content, String reasoning, List<JsonNode> toolRounds) {
        for (JsonNode round : toolRounds) {
            msgs.add(withReasoningForToolCalls(clipRound(round)));
        }
        ObjectNode answer = msgs.addObject().put("role", "assistant").put("content", content);
        if (reasoning != null && !reasoning.isBlank()) {
            answer.put("reasoning_content", limit(reasoning, CONTEXT_REASONING_LIMIT));
        }
    }

    /**
     * 出站请求的消息形态摘要（<b>只记结构、不记正文</b>，便于安全打印到日志）：
     * tools 条数、每条消息的 role、是否带 tool_calls、reasoning_content 的「缺 / 空 / 字数」、
     * 以及是不是 tool 结果。供应商 400 时靠它一眼看出缺的是哪个字段。
     */
    protected static String digest(ObjectNode body) {
        StringBuilder sb = new StringBuilder();
        sb.append("tools=").append(body.path("tools").size())
                .append(" msgs=").append(body.path("messages").size()).append(" → ");
        for (JsonNode m : body.path("messages")) {
            JsonNode calls = m.path("tool_calls");
            boolean hasCalls = calls.isArray() && !calls.isEmpty();
            sb.append('[').append(m.path("role").asText("?"));
            if (hasCalls) {
                sb.append(" calls=").append(calls.size());
            }
            JsonNode reason = m.get("reasoning_content");
            if (reason != null) {
                sb.append(" reason=").append(reason.asText().isEmpty()
                        ? "空" : reason.asText().length() + "字");
            } else if (hasCalls) {
                sb.append(" reason=缺");
            }
            if (m.hasNonNull("tool_call_id")) {
                sb.append(" 工具结果");
            }
            sb.append("] ");
        }
        return sb.toString();
    }

    /**
     * 给「带工具调用的 assistant 轮次」补齐 reasoning_content 字段。
     *
     * <p>思考模式（DeepSeek V4 / deepseek-flash 等）有一条硬性协议：请求里只要带 {@code tools}，
     * 凡带 {@code tool_calls} 的 assistant 消息就必须把 {@code reasoning_content} 一并回传；
     * 缺这个字段，续轮直接 400（{@code The reasoning_content in the thinking mode must be passed
     * back to the API.}）——实测对照：缺字段 400，带空串 200。
     *
     * <p>而模型并不保证每个工具轮次都产出思考（deepseek-flash 常常直接给 tool_calls），
     * 所以这里缺了就补空串。补的是同一个对象，落库的 tool_rounds 与回放因此保持一致；
     * 其它供应商收到这个陌生字段会忽略，不受影响。
     *
     * <p>public 是必要的：子代理/计划/心跳那条流（{@code AgentRunner}）在另一个包里，
     * 它同样要带工具往返，同样受这条协议约束。
     */
    public static JsonNode withReasoningForToolCalls(JsonNode round) {
        if (round instanceof ObjectNode obj) {
            JsonNode calls = obj.path("tool_calls");
            JsonNode reasoning = obj.get("reasoning_content");
            if (calls.isArray() && !calls.isEmpty()
                    && (reasoning == null || reasoning.isNull())) {
                obj.put("reasoning_content", "");
            }
        }
        return round;
    }

    /**
     * 工具轮回放截断（历史留痕）：只压缩超限的思考（reasoning_content）、tool 结果（role=tool 的
     * content）与调用参数（tool_calls[].function.arguments），tool_calls / tool_call_id 等
     * 协议字段不动——模型只需要记得自己做过什么，不需要原文全量。
     */
    private static JsonNode clipRound(JsonNode round) {
        if (!(round instanceof ObjectNode obj)) {
            return round;
        }
        JsonNode reasoning = obj.path("reasoning_content");
        if (reasoning.asText().length() > CONTEXT_REASONING_LIMIT) {
            obj.put("reasoning_content", limit(reasoning.asText(), CONTEXT_REASONING_LIMIT));
        }
        if ("tool".equals(obj.path("role").asText())) {
            JsonNode result = obj.path("content");
            if (result.isTextual() && result.asText().length() > CONTEXT_TOOL_LIMIT) {
                obj.put("content", limit(result.asText(), CONTEXT_TOOL_LIMIT));
            }
        }
        JsonNode calls = obj.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                if (!(call.path("function") instanceof ObjectNode fn)) {
                    continue;
                }
                JsonNode args = fn.path("arguments");
                if (args.isTextual() && args.asText().length() > CONTEXT_TOOL_LIMIT) {
                    fn.put("arguments", limit(args.asText(), CONTEXT_TOOL_LIMIT));
                }
            }
        }
        return obj;
    }

    /** 单段文本按 max 截断（超限尾部以省略号标记） */
    private static String limit(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /**
     * system 末尾挂上「本会话在跑的子代理」；没有在跑的活就原样返回。
     *
     * <p>每轮现取现挂（进度一直在变），位置固定在末尾：既不动身份提示词那几段，
     * 也让「正在进行的后台活」紧挨着模型的注意力末端。
     */
    private String withActiveSubagents(String system, String hubId) {
        String active = subagent.activeBlock(hubId);
        return active.isBlank() ? system : system + "\n\n---\n\n" + active;
    }

    /** 执行一次工具调用，返回给模型的 tool 消息内容（知识库工具见 {@link KnowledgeToolbox}；
     *  计划那套转交 {@link SubconsciousTool}，子代理那套转交 {@link SubagentTool}）。
     *  子代理走对话侧入口 {@link SubagentTool#runChatTool}：派活非阻塞，当场只回受理回执，
     *  结论跑完作为一条新消息投回 hubId 这个会话——主会话因此不被子代理占住
     *
     *  <p><b>可覆盖点</b>：多智能体那条流覆盖本方法，多接一条状态机工具的分发
     *  （propose_end / go_silent / interject，见 {@code agent.multiagent.MaChatService}）。
     */
    protected String runTool(JsonNode call, String batchRunId, String hubId) {
        String name = call.path("function").path("name").asText("");
        if (PLAN_TOOLS.contains(name)) {
            return subconscious.runTool(call);
        }
        if (SUBAGENT_TOOLS.contains(name)) {
            return subagent.runChatTool(call, batchRunId, hubId);
        }
        if (TerminalService.handles(name)) {
            return terminal.run(call, TerminalService.SOURCE_CHAT).content();
        }
        return toolbox.run(call, toolSource()).content();
    }

    /**
     * 这次工具调用来自哪条流（RAG 召回留痕的 source）：首页对话为 {@code chat}。
     *
     * <p><b>可覆盖点</b>：多智能体那条流覆盖为 {@code multiagent}，好让潜意识页的「RAG 召回」
     * 子页能按来源分开看。
     */
    protected String toolSource() {
        return "chat";
    }

    /** 是不是由子代理模块执行的调用（决定这一批要不要开 run） */
    private static boolean isSubagentCall(JsonNode call) {
        return SUBAGENT_TOOLS.contains(call.path("function").path("name").asText(""));
    }

    /**
     * 取会话：给定 id 则按 id 取（不存在=404），缺省取最近一个（无则先建）。
     *
     * <p><b>可覆盖点</b>：多智能体那条流覆盖本方法与 {@link #insertNew()}，换成自己的会话表，
     * 但把行值包成同一个 {@link HubSessionStore.HubRow}——于是整条流循环不必知道有两套会话。
     */
    protected HubRow resolveOrCreate(String hubId) {
        if (hubId == null || hubId.isBlank()) {
            return sessions.latest().orElseGet(this::insertNew);
        }
        return sessions.findById(hubId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在: " + hubId));
    }

    /** 新建一个会话并返回它；多智能体那条流覆盖为写自己的会话表（行值仍包成 HubRow） */
    protected HubRow insertNew() {
        String hubId = UUID.randomUUID().toString();
        sessions.insert(hubId, null);
        return sessions.findById(hubId)
                .orElseThrow(() -> new IllegalStateException("hub_session 落库失败: " + hubId));
    }

    // ---------- 可覆盖点：落点与投影（多智能体那条流换掉这几处，流循环本身不动） ----------

    /** 某会话的既存消息（组装上下文与秘书总结共用）；多智能体那条流换成自己的消息表 */
    protected List<MessageRow> history(HubRow row) {
        return messages.listBySession(row.id());
    }

    /** 落一条用户输入（留痕 + 记忆投影），返回消息行 id */
    protected long recordUserInput(HubRow row, String input) {
        long id = messages.insert(row.id(), "user", input, null, null, null, null, null);
        memory.onChatMessage(id, "user", row.title(), input);
        return id;
    }

    /** 落一条助手回复（留痕 + 记忆投影），返回消息行 id */
    protected long recordReply(HubRow row, String content, String reasoning, String trace,
                               String rounds) {
        long id = messages.insert(row.id(), "assistant", content, reasoning, trace, rounds,
                null, null);
        memory.onChatMessage(id, "assistant", row.title(), content);
        return id;
    }

    /** 会话有动静（新消息落库后）：刷新时间戳并按需回填标题 */
    protected void touchSession(HubRow row, String title) {
        sessions.touch(row.id(), title);
    }

    /** 记住本次使用的模型 / 身份版本（会话级配置，下次进入该会话自动回显） */
    protected void rememberConfig(HubRow row, String modelId, String promptId) {
        sessions.updateConfig(row.id(), modelId, promptId, null, null, null, null);
    }

    private static ChatDtos.SessionItem toItem(HubRow row, boolean generating) {
        return new ChatDtos.SessionItem(row.id(), row.title(), row.modelId(), row.promptId(),
                row.showCot(), row.contextTrace(), row.maxRounds(), row.showRounds(), row.unread(),
                generating, iso(row.updatedAt() != null ? row.updatedAt() : row.createdAt()));
    }

    /** 标题截断（首条发言取前若干字）；多智能体那条流算会话标题时同样用它 */
    protected static String abbreviate(String input) {
        String s = input.strip();
        return s.length() <= TITLE_MAX ? s : s.substring(0, TITLE_MAX);
    }

    private static String iso(Long ms) {
        return ms == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString();
    }
}
