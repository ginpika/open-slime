package cc.ginpika.minis.web.controller;

import cc.ginpika.minis.agent.chat.ChatTurnRunner;
import cc.ginpika.minis.agent.chat.ChatContextService;
import cc.ginpika.minis.agent.chat.ChatSessionService;
import cc.ginpika.minis.agent.chat.ChatLiveStore;
import cc.ginpika.minis.agent.chat.ChatStream;
import cc.ginpika.minis.agent.chat.TodoService;
import cc.ginpika.minis.agent.chat.dto.ChatDtos;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 会话接口（B4）：/api/sessions*、/api/chat（SSO 门禁见 WebConfig/SsoInterceptor）。
 *
 * <p>传输层负责 HTTP / SSE，生成、会话管理与在途续传由对应应用服务处理。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 包含 ComfyUI 等长工具等待；覆盖最长 3600s 工作流并为模型收尾留余量。 */
    private static final long STREAM_TIMEOUT_MS = 3_900_000L;

    private final ChatTurnRunner turns;
    private final ChatContextService context;
    private final ChatSessionService sessions;
    private final ChatLiveStore live;
    private final TodoService todo;
    private final ObjectMapper om;
    /** 流式会话在独立线程里跑（虚拟线程）：边收 LLM 边推前端，不占请求线程 */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ChatTurnRunner turns, ChatContextService context, ChatSessionService sessions,
                          ChatLiveStore live, TodoService todo, ObjectMapper om) {
        this.turns = turns;
        this.context = context;
        this.sessions = sessions;
        this.live = live;
        this.todo = todo;
        this.om = om;
    }

    @PostMapping("/api/chat")
    public ChatDtos.ChatReply chat(@RequestBody ChatDtos.ChatRequest req, jakarta.servlet.http.HttpServletRequest http) {
        String input = req.input() == null ? "" : req.input().trim();
        if (input.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 为空");
        }
        try (var actor = cc.ginpika.minis.ai.core.UserScope.bind(codingOwner(http))) {
            return turns.chat(blank(req.sessionId()) ? null : req.sessionId(), input,
                    req.modelId(), req.promptId());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /**
     * 流式会话：SSE 依次推送 meta（user 落库 id）/ round（工具轮边界）/
     * reasoning、content（增量片段）/ tool（一次工具调用：name+args），
     * 结束推 done（完整回复），异常推 error。
     *
     * <p>每一片增量同时写进在途生成仓（经 {@link ChatLiveStore}）：发起方这条连接断了（刷新页面等）
     * 不再中断生成，结果照常落库、在途状态也让后来者能续看（见下面的续传流）。
     */
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatDtos.ChatRequest req, jakarta.servlet.http.HttpServletRequest http) {
        String owner = codingOwner(http);
        String input = req.input() == null ? "" : req.input().trim();
        if (input.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input 为空");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        streamExecutor.execute(() -> {
            /* 本次生成实际落入的会话 id：由 meta 事件给出（New Chat 时由后端新建），收尾按它落定在途状态 */
            String[] liveSid = {null};
            try (var actor = cc.ginpika.minis.ai.core.UserScope.bind(owner)) {
                ChatDtos.ChatReply reply = turns.chatStream(
                        blank(req.sessionId()) ? null : req.sessionId(), input,
                        req.modelId(), req.promptId(), new ChatStream() {
                            @Override
                            public void meta(String sessionId, long messageId) {
                                liveSid[0] = sessionId;
                                live.start(sessionId, messageId, input);
                                sendQuiet(emitter, alive, "meta",
                                        Map.of("sessionId", sessionId, "messageId", messageId));
                            }

                            @Override
                            public void round(int round) {
                                if (liveSid[0] != null) {
                                    live.round(liveSid[0], round);
                                }
                                sendQuiet(emitter, alive, "round", Map.of("round", round));
                            }

                            @Override
                            public void delta(String reasoning, String content) {
                                if (liveSid[0] != null) {
                                    live.delta(liveSid[0], reasoning, content);
                                }
                                if (reasoning != null) {
                                    sendQuiet(emitter, alive, "reasoning", Map.of("delta", reasoning));
                                }
                                if (content != null) {
                                    sendQuiet(emitter, alive, "content", Map.of("delta", content));
                                }
                            }

                            @Override
                            public void tool(String name, String args) {
                                if (liveSid[0] != null) {
                                    live.tool(liveSid[0], name, args);
                                }
                                sendQuiet(emitter, alive, "tool", Map.of("name", name, "args", args));
                            }
                        });
                if (liveSid[0] != null) {
                    live.finish(liveSid[0], reply.message());
                }
                sendQuiet(emitter, alive, "done", reply);
                emitter.complete();
            } catch (ChatStream.ChatCancelledException e) {
                /* 用户主动停止且无任何正文产出：在途状态落定（有正文的部分回答已由 service 落库），静默收尾不推 error */
                if (liveSid[0] != null) {
                    live.finish(liveSid[0], null);
                }
                emitter.complete();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                /* 出错即落日志（含堆栈），便于回溯「请求被打断」的具体成因 */
                log.warn("流式会话失败：sessionId={} error={}", req.sessionId(), msg, e);
                if (liveSid[0] != null) {
                    live.fail(liveSid[0], msg);
                }
                sendQuiet(emitter, alive, "error", Map.of("error", msg));
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 该会话此刻的在途生成快照（前端刷新后先查它：running=true 才去订阅下面的续传流） */
    @GetMapping("/api/sessions/{id}/live")
    public ChatDtos.LiveRun live(@PathVariable String id) {
        return live.snapshot(id);
    }

    /**
     * 续传流：先推一份完整快照（snapshot），再接着推后续 round / delta / tool，
     * 收尾推 done / error（payload 同为快照，running 已是 false）。
     *
     * <p>该会话没有在途生成时只推一份 snapshot（running=false）即收尾，前端据此照常走历史加载。
     * 生成超过本流的最长保持时长时连接会被掐断，前端重新查一次快照再挂上即可（增量不会丢：快照即全量）。
     */
    @GetMapping(value = "/api/sessions/{id}/live/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveStream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean alive = new AtomicBoolean(true);
        boolean attached = live.attach(id, (name, payload) -> {
            boolean sent = sendQuiet(emitter, alive, name, payload);
            if ("done".equals(name) || "error".equals(name)) {
                emitter.complete();
                return false;
            }
            return sent;
        });
        if (!attached && alive.get()) {
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 停止该会话进行中的流式生成（前端发送按钮在等待期间变成的「停止」按钮调用）：
     * 通知后端当前等待的响应不再需要——生成线程停止等待/消费 LLM 输出，
     * 已产出的部分回答照常落库。stopped=false 表示该会话当前没有进行中的流。
     */
    @PostMapping("/api/chat/stop")
    public ChatDtos.StopResult stop(@RequestBody ChatDtos.StopRequest req) {
        return new ChatDtos.StopResult(turns.requestStop(req == null ? null : req.sessionId()));
    }

    @GetMapping("/api/sessions")
    public List<ChatDtos.SessionItem> sessions() {
        return sessions.list();
    }

    /**
     * 新建会话。可带 {@code projectId} 绑定项目（memory-v6 §15.2）——项目决定记忆归属：
     * 有项目 → 提炼落 {@code PROJECT×L1} 且跨项目隔离；无项目 → 落 {@code AGENT×L2}。
     */
    @PostMapping("/api/sessions")
    public ChatDtos.NewSession createSession(@RequestBody(required = false) ChatDtos.ProjectBind req) {
        return sessions.create(req == null ? null : req.projectId());
    }

    /** 绑定 / 解绑会话所属项目（记忆作用域切换：旧项目条目不删除、只是不再注入） */
    @PutMapping("/api/sessions/{id}/project")
    public ChatDtos.NewSession bindProject(@PathVariable String id,
                                          @RequestBody(required = false) ChatDtos.ProjectBind req) {
        return sessions.setProject(id, req == null ? null : req.projectId());
    }

    /** 删除会话（含其消息正本）：最近会话列表的删除按钮调用 */
    @DeleteMapping("/api/sessions/{id}")
    public ChatDtos.SessionDeleted deleteSession(@PathVariable String id) {
        return sessions.delete(id);
    }

    @GetMapping("/api/sessions/{id}/messages")
    public List<ChatDtos.MessageRow> messages(@PathVariable String id) {
        try {
            return sessions.messages(id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /** 会话的个人待办清单（plan-of-record，由 write_todos 覆盖式写入）：会话页 TODO 卡片读当前进度。
     *  实时性由会话事件（kind=todo）驱动——清单一变，后端推一条，前端据此重拉本端点 */
    @GetMapping("/api/sessions/{id}/todos")
    public List<ChatDtos.TodoItem> todos(@PathVariable String id) {
        return todo.list(id, sessions.owner(id));
    }

    /** 会话的原始上下文预览：按与真实发送完全一致的规则组装的请求体（model + messages + tools），
     *  并附各 Turn 在 messages 中的切片；会话页「…」菜单的「查看原始上下文」调用（不发送、不落库） */
    @GetMapping("/api/sessions/{id}/raw-context")
    public ChatDtos.RawContext rawContext(@PathVariable String id) {
        return context.rawContext(id);
    }

    /** 某条回答（一个 Turn）的 LLM 调用留痕：每一轮真正发出的请求体与收到的响应体。
     *  「查看原始上下文」里每个 Turn 的「请求 / 响应」按钮调用（replyMessageId 取自 raw-context 的切片） */
    @GetMapping("/api/sessions/{id}/turns/{replyMessageId}/llm-calls")
    public ChatDtos.TurnCalls turnCalls(@PathVariable String id, @PathVariable long replyMessageId) {
        return context.turnCalls(id, replyMessageId);
    }

    /** 会话配置（「…」菜单在该会话的选择：模型 / 提示词 / 是否显示 CoT）：仅更新传入的字段 */
    @PutMapping("/api/sessions/{id}/config")
    public ChatDtos.SessionConfig updateConfig(@PathVariable String id,
                                               @RequestBody ChatDtos.ConfigRequest req) {
        return sessions.updateConfig(id, req);
    }

    /** 回退到某条提问之前：该条及其后的消息一并删除 */
    @PostMapping("/api/sessions/{id}/rewind")
    public ChatDtos.Rewind rewind(@PathVariable String id, @RequestBody ChatDtos.RewindRequest req) {
        return sessions.rewind(id,
                req == null ? null : req.messageId(),
                req == null ? null : req.keep());
    }

    /** 分支：以某条消息为界，创建一个副本会话——源会话到此为止的转录整段复制进新会话 */
    @PostMapping("/api/sessions/{id}/branch")
    public ChatDtos.NewSession branch(@PathVariable String id,
                                      @RequestBody ChatDtos.BranchRequest req) {
        return sessions.branch(id, req == null ? null : req.messageId());
    }

    /**
     * 发送一个 SSE 事件（data 为 JSON 字符串）；写失败即客户端已断开，此时只把这条连接标记作废并返回
     * false，不再抛错。
     *
     * <p>断开不再中断生成：回答照常产出并落库，在途状态留在在途生成仓里，用户刷新回来
     * 还能续看（断点续传）；要真正打断由「停止」按钮走 {@code /api/chat/stop}。
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

    /**
     * 向已断开的客户端写流而失败：这不是业务错误，收下即可。
     *
     * <p>Spring 会把流写失败当成异步请求错误再派发回来走异常解析，若无人处理，tomcat 便以
     * ERROR 记一整条堆栈（Windows 上是「你的主机中的软件中止了一个已建立的连接」）。
     * 用户切页、停止生成时都会发生，属常态，故按断开静默收尾，不打 ERROR 刷屏。
     */
    @ExceptionHandler(IOException.class)
    public void clientGone(IOException e) {
        log.debug("会话流客户端已断开，写失败按断开收尾：{}", e.getMessage());
    }

    private static String codingOwner(jakarta.servlet.http.HttpServletRequest request) {
        Object actor=request.getAttribute(cc.ginpika.minis.web.sso.SsoInterceptor.ATTR);
        return actor instanceof cc.ginpika.minis.web.sso.SsoUser user && cc.ginpika.minis.coding.CodingFacade.allowsActorAt(user.getId(),request.getRemoteAddr()) ? user.getId() : null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
