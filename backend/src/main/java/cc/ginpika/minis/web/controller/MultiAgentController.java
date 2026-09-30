package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.multiagent.MaChatService;
import cc.ginpika.minis.agent.multiagent.MaTopicService;
import cc.ginpika.minis.agent.multiagent.PeerAnchorService;
import cc.ginpika.minis.agent.multiagent.PeerDirectory;
import cc.ginpika.minis.agent.multiagent.PeerPromptService;
import cc.ginpika.minis.agent.multiagent.SpeechPacer;
import cc.ginpika.minis.agent.multiagent.dto.MultiAgentDtos;
import cc.ginpika.minis.agent.multiagent.store.MaPeerStore.PeerRow;
import cc.ginpika.minis.ai.core.prompt.dto.PromptDtos;
import cc.ginpika.minis.ai.core.tool.AiTool;
import cc.ginpika.minis.ai.core.tool.ToolManager;
import cc.ginpika.minis.ai.core.tool.ToolSurface;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 多智能体接口：名册（配置面）+ 圆桌会话（对话面）+ 话题与事件（状态机面）。
 *
 * <p>与首页对话接口（{@code /api/chat*}）刻意分开：多智能体有自己的会话与消息，
 * 页面上也不复用对话页，所以路径、事件名、返回结构都自成一套，互不影响。
 *
 * <p>流式事件（{@code POST /api/multi-agent/chat/stream}）按发生顺序推：
 * {@code topic}（话题确定）→ 每位成员一组 {@code speaker} / {@code reasoning} · {@code content} ·
 * {@code tool} / {@code speakerEnd} → 收束时 {@code topicEnd} → {@code done}（本轮摘要）；
 * 出错推 {@code error}。
 */
@RestController
@RequestMapping("/api/multi-agent")
public class MultiAgentController {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentController.class);

    /** SSE 流最长保持：与 nginx 的 proxy_read_timeout（300s）对齐 */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final PeerDirectory peers;
    private final MaChatService chat;
    private final MaTopicService topics;
    private final PeerPromptService peerPrompts;
    private final PeerAnchorService peerAnchors;
    private final ToolManager tools;
    private final ObjectMapper om;
    /** 发言节流：整段发言按「人打字要多久」等一会儿再推，免得几位成员的话一起砸到屏幕上 */
    private final SpeechPacer pacer;
    /** 一轮圆桌发言在独立线程里跑（虚拟线程）：边收 LLM 边推前端，不占请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public MultiAgentController(PeerDirectory peers, MaChatService chat, MaTopicService topics,
                                PeerPromptService peerPrompts, PeerAnchorService peerAnchors,
                                ToolManager tools, ObjectMapper om, SpeechPacer pacer) {
        this.peers = peers;
        this.chat = chat;
        this.topics = topics;
        this.peerPrompts = peerPrompts;
        this.peerAnchors = peerAnchors;
        this.tools = tools;
        this.om = om;
        this.pacer = pacer;
    }

    // ---------- 名册（配置面） ----------

    @GetMapping("/peers")
    public List<MultiAgentDtos.PeerView> peers() {
        return peers.peers().stream().map(this::toPeer).toList();
    }

    /**
     * 可授予智能体的工具清单（成员页「可用工具」的候选）。
     *
     * <p>只列「agent 能调、且属对话面」的工具——{@code run_command} / {@code list_terminals}
     * 不在其中：它们不由名字授予，而是绑定工作空间时连同授权名单一起给（见成员页「工作空间」）。
     */
    @GetMapping("/tools")
    public List<MultiAgentDtos.ToolOption> tools() {
        return tools.grantableTools(ToolSurface.CHAT).stream()
                /* 先按业务领域（枚举序：文档 / 图谱 / 记忆 / 其他）、再按名字排序：
                   前端据此顺序即可分 Tab 归类，无需再排 */
                .sorted(Comparator.comparing((AiTool t) -> t.domain().ordinal())
                        .thenComparing(AiTool::name))
                .map(this::toTool)
                .toList();
    }

    @PostMapping("/peers")
    public MultiAgentDtos.PeerView createPeer(@RequestBody MultiAgentDtos.PeerRequest req) {
        PeerRow row = peers.create(req == null ? null : req.name(),
                req == null ? null : req.promptId(), req == null ? null : req.tools(),
                req == null ? null : req.terminals(), req == null ? null : req.modelId(),
                req == null ? null : req.note(), req == null ? null : req.avatar());
        return toPeer(row);
    }

    @PutMapping("/peers/{id}")
    public MultiAgentDtos.PeerView updatePeer(@PathVariable String id,
                                              @RequestBody MultiAgentDtos.PeerRequest req) {
        PeerRow row = peers.update(id, req == null ? null : req.name(),
                req == null ? null : req.promptId(), req == null ? null : req.tools(),
                req == null ? null : req.terminals(), req == null ? null : req.modelId(),
                req == null ? null : req.sort(), req == null ? null : req.note(),
                req == null ? null : req.avatar());
        return toPeer(row);
    }

    @DeleteMapping("/peers/{id}")
    public MultiAgentDtos.PeerDeleted deletePeer(@PathVariable String id) {
        peers.delete(id);
        return new MultiAgentDtos.PeerDeleted(id);
    }

    // ---------- 成员提示词（每个智能体独立一套「对话」提示词） ----------

    /**
     * 这位成员自己那套「对话」提示词的全量视图：结构与提示词页一致（大类 → 上下文 → 提示词位），
     * 只是大类只有「对话」，且版本 / 生效项 / 顺序都是这位成员自己的。
     */
    @GetMapping("/peers/{id}/prompts")
    public PromptDtos.PromptsView peerPrompts(@PathVariable String id) {
        return peerPrompts.view(id);
    }

    /** 为这位成员的某提示词位新增一份版本 */
    @PostMapping("/peers/{id}/prompts/slots/{slot}/versions")
    public PromptDtos.PromptsView createPeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String slot,
                                                          @RequestBody PromptDtos.VersionRequest req) {
        peerPrompts.createVersion(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 改这位成员某份版本的正文 / 名字 */
    @PutMapping("/peers/{id}/prompts/versions/{vid}")
    public PromptDtos.PromptsView updatePeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String vid,
                                                          @RequestBody PromptDtos.VersionRequest req) {
        peerPrompts.updateVersion(id, vid, req);
        return peerPrompts.view(id);
    }

    @DeleteMapping("/peers/{id}/prompts/versions/{vid}")
    public PromptDtos.PromptsView deletePeerPromptVersion(@PathVariable String id,
                                                          @PathVariable String vid) {
        peerPrompts.deleteVersion(id, vid);
        return peerPrompts.view(id);
    }

    /** 选定这位成员某提示词位生效的版本；versionId 为空或 builtin = 回退全局 */
    @PutMapping("/peers/{id}/prompts/slots/{slot}/binding")
    public PromptDtos.PromptsView bindPeerPromptSlot(@PathVariable String id,
                                                     @PathVariable String slot,
                                                     @RequestBody PromptDtos.BindRequest req) {
        peerPrompts.bind(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 保存这位成员某提示词位当前生效的正文：已绑定版本则改它，否则自动分叉一份专属版本 */
    @PutMapping("/peers/{id}/prompts/slots/{slot}/content")
    public PromptDtos.PromptsView savePeerPromptSlotContent(@PathVariable String id,
                                                            @PathVariable String slot,
                                                            @RequestBody PromptDtos.ContentRequest req) {
        peerPrompts.saveContent(id, slot, req);
        return peerPrompts.view(id);
    }

    /** 覆盖这位成员某上下文内提示词位的拼装顺序（拖拽调整位置） */
    @PutMapping("/peers/{id}/prompts/contexts/{context}/order")
    public PromptDtos.PromptsView savePeerPromptContextOrder(@PathVariable String id,
                                                             @PathVariable String context,
                                                             @RequestBody PromptDtos.OrderRequest req) {
        peerPrompts.saveOrder(id, context, req);
        return peerPrompts.view(id);
    }

    // ---------- 图谱人格锚点（成员与知识图谱锚点实体的绑定） ----------

    /** 这位成员的图谱人格锚点视图：锚点实体 + 它的一跳子图；未绑定时 anchor=null */
    @GetMapping("/peers/{id}/anchor")
    public MultiAgentDtos.AnchorView peerAnchor(@PathVariable String id) {
        return peerAnchors.view(id);
    }

    /** 新建并绑定：以成员名落一个锚点实体（types 含 ANCHOR）；已绑定时幂等 */
    @PostMapping("/peers/{id}/anchor")
    public MultiAgentDtos.AnchorView createPeerAnchor(@PathVariable String id) {
        return peerAnchors.create(id);
    }

    /** 解除绑定：只断开成员与锚点的关联，锚点实体与其关系保留在图中 */
    @DeleteMapping("/peers/{id}/anchor")
    public MultiAgentDtos.AnchorView deletePeerAnchor(@PathVariable String id) {
        return peerAnchors.unbind(id);
    }

    // ---------- 圆桌会话（对话面） ----------

    @GetMapping("/sessions")
    public List<MultiAgentDtos.SessionView> sessions() {
        return chat.sessions();
    }

    /** 新建会话：body 里带 memberIds 就只让那几位上桌；不带（null） = 全体名册 */
    @PostMapping("/sessions")
    public MultiAgentDtos.NewSession createSession(
            @RequestBody(required = false) MultiAgentDtos.NewSessionRequest req) {
        return chat.createSession(req == null ? null : req.memberIds());
    }

    @GetMapping("/sessions/{id}")
    public MultiAgentDtos.SessionView session(@PathVariable String id) {
        return chat.session(id);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, String> deleteSession(@PathVariable String id) {
        chat.deleteSession(id);
        return Map.of("sessionId", id);
    }

    /** 会话的消息；带 topicId 时只看那一次话题 */
    @GetMapping("/sessions/{id}/messages")
    public List<MultiAgentDtos.MessageView> messages(@PathVariable String id,
                                                     @RequestParam(required = false) String topicId) {
        return chat.messages(id, topicId);
    }

    /**
     * 某条发言当时的原始上下文（点开一条成员发言，看它当时到底看到了什么：
     * system 里的人格与协作规程、调度器给的那条 user 里的公开记录与此刻局面、以及挂了哪些工具）。
     *
     * <p>与对话页「查看原始上下文」的区别：那个是「此刻再发一条会发什么」，这个是**当时真正发出去的那一份**。
     */
    @GetMapping("/messages/{id}/context")
    public MultiAgentDtos.MessageContext messageContext(@PathVariable long id) {
        return new MultiAgentDtos.MessageContext(id, chat.contextOf(id).orElse(null));
    }

    @GetMapping("/sessions/{id}/topics")
    public List<MultiAgentDtos.TopicView> topics(@PathVariable String id) {
        return topics.topics(id);
    }

    // ---------- 说话（SSE） ----------

    /**
     * 人往圆桌说一句话：这一句会依次引出一串成员发言，增量边跑边推。
     *
     * <p>没有会话 id 时用最近一个会话（没有就新建），与对话页的「直接开说」手感一致。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody MultiAgentDtos.SendRequest req) {
        String input = req == null || req.input() == null ? "" : req.input().strip();
        if (input.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 为空");
        }
        String sessionId = req.sessionId() == null || req.sessionId().isBlank()
                ? chat.sessions().stream().findFirst()
                        .map(MultiAgentDtos.SessionView::id)
                        .orElseGet(() -> chat.createSession(null).sessionId())
                : req.sessionId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        streamExecutor.execute(() -> {
            try {
                MultiAgentDtos.TurnResult result = topics.sendMessage(sessionId, input,
                        req.modelId(), req.speakerStrategy(), new MaTopicService.StreamSink() {
                            @Override
                            public void topic(String topicId, String status) {
                                sendQuiet(emitter, alive, "topic",
                                        Map.of("sessionId", sessionId, "topicId", topicId,
                                                "status", status));
                            }

                            @Override
                            public void speaker(String peerId, String peerName) {
                                sendQuiet(emitter, alive, "speaker",
                                        Map.of("peerId", peerId, "peerName", peerName));
                            }

                            @Override
                            public void delta(String reasoning, String content) {
                                if (reasoning != null) {
                                    sendQuiet(emitter, alive, "reasoning", Map.of("delta", reasoning));
                                }
                                if (content != null) {
                                    sendQuiet(emitter, alive, "content", Map.of("delta", content));
                                }
                            }

                            /** 成员又开口说了一段（一次 speak）：前端据此开一个新气泡，这段话直接上屏 */
                            @Override
                            public void speech(String text) {
                                /* 按字数等一会儿再推：整段话一次到，不节流的话几条会一起砸出来。
                                   客户端已断开就不必再等（等待只为了让对面看着像在打字） */
                                if (alive.get()) {
                                    pacer.pace(text);
                                }
                                sendQuiet(emitter, alive, "speech", Map.of("text", text));
                            }

                            @Override
                            public void tool(String name, String args) {
                                sendQuiet(emitter, alive, "tool", Map.of("name", name, "args", args));
                            }

                            @Override
                            public void speakerEnd(String peerId, String state) {
                                sendQuiet(emitter, alive, "speakerEnd",
                                        Map.of("peerId", peerId, "state", state));
                            }

                            /** 话题快照：前端据此边聊边刷新各成员状态（不必等整轮跑完再拉一次） */
                            @Override
                            public void state(MultiAgentDtos.TopicView topic) {
                                sendQuiet(emitter, alive, "state", topic);
                            }

                            @Override
                            public void topicEnd(String topicId) {
                                sendQuiet(emitter, alive, "topicEnd", Map.of("topicId", topicId));
                            }

                            /** 一条状态机动作行（开话题、入场、转沉默……）：即刻推到页面，不等整轮跑完 */
                            @Override
                            public void action(String text) {
                                sendQuiet(emitter, alive, "action", Map.of("text", text));
                            }
                        });
                sendQuiet(emitter, alive, "done", result);
                emitter.complete();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("圆桌发言失败：sessionId={} error={}", sessionId, msg, e);
                sendQuiet(emitter, alive, "error", Map.of("error", msg));
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 人打断当前发言（页面的「停止」按钮）：正在说话的那位就此打住，已说出的部分照常落库 */
    @PostMapping("/chat/stop")
    public Map<String, Boolean> stop(@RequestBody MultiAgentDtos.SendRequest req) {
        String sessionId = req == null ? null : req.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("stopped", false);
        }
        return Map.of("stopped", topics.interrupt(sessionId, "人手动停止"));
    }

    // ---------- 映射与流助手 ----------

    private MultiAgentDtos.PeerView toPeer(PeerRow row) {
        return new MultiAgentDtos.PeerView(row.id(), row.name(), row.promptId(),
                peers.declaredTools(row), peers.declaredTerminals(row), row.modelId(), row.sort(),
                row.note(), row.avatar());
    }

    private MultiAgentDtos.ToolOption toTool(AiTool tool) {
        return new MultiAgentDtos.ToolOption(tool.name(), tool.description(), tool.writes(),
                tool.domain().key(), tool.domain().label());
    }

    /**
     * 发一个 SSE 事件（data 为 JSON 字符串）；写失败即客户端已断开，标记连接作废并返回 false，
     * 不再抛错——断开不打断生成，本轮照常跑完落库。
     */
    private boolean sendQuiet(SseEmitter emitter, AtomicBoolean alive, String name, Object payload) {
        if (!alive.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(om.writeValueAsString(payload)));
            return true;
        } catch (Exception e) {
            alive.set(false);
            return false;
        }
    }

    /** 客户端断开导致写流失败：常态，静默收尾（否则 tomcat 会记一整条 ERROR 堆栈） */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("圆桌流客户端已断开，写失败按断开收尾：{}", e.getMessage());
    }
}
