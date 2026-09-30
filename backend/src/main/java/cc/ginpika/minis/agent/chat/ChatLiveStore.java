package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 在途生成仓：把某会话正在流式产出的正文 / 思维链 / 分段暂存在后端，供前端刷新（或换标签页）后
 * 重新挂回来接着看——即对话的「断点续传」。
 *
 * <p><b>为什么需要它</b>：流式生成的消费端就是那条 SSE 连接，页面一刷新就断了。若在途状态只活在连接里，
 * 断开即「看不见进度、也够不到停止」。这里把生成过程与连接解耦：{@code POST /api/chat/stream} 照旧把增量
 * 推给发起方，同时每一片也写进本仓；此后任何时刻新来的订阅者（{@code GET /api/sessions/{id}/live/stream}）
 * 都能先拿到整份快照，再接着收后续增量，直到这次生成收尾。
 *
 * <p>生成线程写、HTTP 线程读，故每个 Run 上的读写都在同一把锁里；「推快照 + 挂订阅」在锁内一并完成，
 * 保证订阅者既不会漏掉也不会错序任何一片增量。
 */
@Component
public class ChatLiveStore {

    /** 续传订阅者：收到一个事件；返回 false 表示已失效（如 SSE 客户端断开），由仓就地摘除 */
    @FunctionalInterface
    public interface LiveListener {
        boolean onEvent(String name, Object payload);
    }

    /** 一次在途生成的可续传状态 */
    private static final class Run {
        final String sessionId;
        final StringBuilder content = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<ChatDtos.TraceSegment> trace = new ArrayList<>();
        /** 当前订阅者（SSE 消费者），按订阅先后保序 */
        final Set<LiveListener> listeners = new LinkedHashSet<>();
        long userMessageId;
        String userText;
        int round;
        boolean running = true;
        String error;

        Run(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /** 一次生成开始（user 消息已落库）：登记该会话的在途状态 */
    public void start(String sessionId, long userMessageId, String userText) {
        Run run = new Run(sessionId);
        run.userMessageId = userMessageId;
        run.userText = userText;
        runs.put(sessionId, run);
    }

    /** 模型产出的增量片段：reasoning（思维链）/ content（正文）各自可能为 null */
    public void delta(String sessionId, String reasoning, String content) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (reasoning != null) {
                run.reasoning.append(reasoning);
                appendReasoning(run, reasoning);
            }
            if (content != null) {
                run.content.append(content);
            }
            broadcast(run, "delta", Map.of("reasoning", nullToEmpty(reasoning),
                    "content", nullToEmpty(content)));
        }
    }

    /** 进入第 round 轮补全：>0 为工具轮之后的新一轮，正文从头开始（最终答复以末轮为准）；
     *  思维链跨轮保留（与前端、与落库规则一致） */
    public void round(String sessionId, int round) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return;
        }
        synchronized (run) {
            run.round = round;
            if (round > 0) {
                run.content.setLength(0);
            }
            broadcast(run, "round", Map.of("round", round));
        }
    }

    /** 模型发起一次工具调用：落为一段 tool 分段 */
    public void tool(String sessionId, String name, String args) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return;
        }
        synchronized (run) {
            run.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_TOOL, null, name, args, run.round));
            broadcast(run, "tool", Map.of("name", name == null ? "" : name,
                    "args", args == null ? "" : args, "round", run.round));
        }
    }

    /** 生成收尾（正常完成、或用户停止后部分回答已落库）：落定最终正文并广播整份快照，随后移除在途状态。
     *  finalContent 为 null 表示以此刻已产出的部分为准（停止时正文可能没有、也可能只有半截） */
    public void finish(String sessionId, String finalContent) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return;
        }
        synchronized (run) {
            if (finalContent != null) {
                run.content.setLength(0);
                run.content.append(finalContent);
            }
            run.running = false;
            broadcast(run, "done", snapshotOf(run));
        }
        runs.remove(sessionId);
    }

    /** 生成失败：落定 running=false 与原因后广播（前端保留已产出的半截输出，便于回退重发），随后移除 */
    public void fail(String sessionId, String error) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return;
        }
        synchronized (run) {
            run.running = false;
            run.error = error;
            broadcast(run, "error", snapshotOf(run));
        }
        runs.remove(sessionId);
    }

    /**
     * 原子地「先推整份快照，再挂上订阅」：返回 true=确有在途生成（快照与订阅都已办妥），
     * false=该会话没有在途生成（只推了一份 running=false 的快照，调用方据此收尾）。
     *
     * <p>快照与订阅在同一把锁里完成，订阅者不会漏掉锁外新产生的增量，也不会把增量排到快照之前。
     */
    public boolean attach(String sessionId, LiveListener listener) {
        Run run = runs.get(sessionId);
        if (run == null) {
            listener.onEvent("snapshot", notRunning(sessionId));
            return false;
        }
        synchronized (run) {
            if (!run.running) {
                listener.onEvent("snapshot", notRunning(sessionId));
                return false;
            }
            if (!listener.onEvent("snapshot", snapshotOf(run))) {
                return false; // 订阅者当场就发不出去（连接已断），不必挂上
            }
            run.listeners.add(listener);
            return true;
        }
    }

    /** 该会话此刻是否有在途生成 */
    public boolean isRunning(String sessionId) {
        Run run = runs.get(sessionId);
        return run != null && run.running;
    }

    /** 该会话的在途快照；没有在途生成时返回 running=false 的空快照（前端据此照常走历史加载） */
    public ChatDtos.LiveRun snapshot(String sessionId) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return notRunning(sessionId);
        }
        synchronized (run) {
            return snapshotOf(run);
        }
    }

    /** 把事件推给全部订阅者；返回 false 的那些（客户端已断开、或已收尾）就地摘掉 */
    private static void broadcast(Run run, String name, Object payload) {
        run.listeners.removeIf((l) -> !l.onEvent(name, payload));
    }

    /** 把一片思维链增量并入分段（与前端 appendReasoning 同一规则）：接续上一段思考；
     *  上一段是工具调用时另起一段（工具轮后的首片思考后端会补分隔线，分段展示时去掉） */
    private static void appendReasoning(Run run, String delta) {
        int last = run.trace.size() - 1;
        ChatDtos.TraceSegment prev = last < 0 ? null : run.trace.get(last);
        if (prev != null && ChatTrace.SEG_REASONING.equals(prev.type())) {
            run.trace.set(last, new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING,
                    nullToEmpty(prev.text()) + delta, null, null, prev.round()));
            return;
        }
        run.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING,
                delta.replaceFirst("^\\n*---\\n+", ""), null, null, run.round));
    }

    private static ChatDtos.LiveRun snapshotOf(Run run) {
        return new ChatDtos.LiveRun(run.sessionId, run.running, run.userText, run.userMessageId,
                run.content.toString(), run.reasoning.toString(), List.copyOf(run.trace), run.error);
    }

    private static ChatDtos.LiveRun notRunning(String sessionId) {
        return new ChatDtos.LiveRun(sessionId, false, null, null, "", "", List.of(), null);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
