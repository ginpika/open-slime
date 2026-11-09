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

    /** 一次在途生成的可续传状态。写入端持有此实例，收尾时可区分同一会话后续启动的生成。 */
    public static final class Run {
        private final String sessionId;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final List<ChatDtos.TraceSegment> trace = new ArrayList<>();
        private final List<ChatDtos.SteerMessage> steers = new ArrayList<>();
        private final List<ChatDtos.InterimReply> interims = new ArrayList<>();
        /** 当前订阅者（SSE 消费者），按订阅先后保序 */
        private final Set<LiveListener> listeners = new LinkedHashSet<>();
        private final long userMessageId;
        private final String userText;
        private int round;
        private int reasoningBeforeRound;
        private volatile boolean running = true;
        private String error;

        private Run(String sessionId, long userMessageId, String userText) {
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
            this.userText = userText;
        }
    }

    public void delta(String sessionId, String reasoning, String content) {
        Run run = runs.get(sessionId);
        if (run != null) {
            delta(run, reasoning, content);
        }
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /** 一次生成开始（user 消息已落库）：登记该会话的在途状态 */
    public Run start(String sessionId, long userMessageId, String userText) {
        Run run = new Run(sessionId, userMessageId, userText);
        runs.put(sessionId, run);
        return run;
    }

    /** 模型产出的增量片段：reasoning（思维链）/ content（正文）各自可能为 null */
    public void delta(Run run, String reasoning, String content) {
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
    public void round(Run run, int round) {
        synchronized (run) {
            run.round = round;
            run.reasoningBeforeRound = run.reasoning.length();
            if (round > 0) {
                run.content.setLength(0);
            }
            broadcast(run, "round", Map.of("round", round));
        }
    }

    /** 模型发起一次工具调用：落为一段 tool 分段 */
    public void tool(Run run, String name, String args) {
        synchronized (run) {
            run.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_TOOL, null, name, args, run.round));
            broadcast(run, "tool", Map.of("name", name == null ? "" : name,
                    "args", args == null ? "" : args, "round", run.round));
        }
    }

    public void steer(Run run, ChatDtos.SteerMessage message) {
        synchronized (run) {
            run.steers.add(message);
            run.content.setLength(0);
            broadcast(run, "steer", message);
        }
    }

    public void interim(Run run, ChatDtos.InterimReply reply) {
        synchronized (run) {
            run.interims.add(reply);
            broadcast(run, "interim", reply);
        }
    }

    /** 只撤销本次补全中未采纳的输出。已经执行的工具记录仍然可见。 */
    public ChatDtos.OutputReset reset(Run run, int round) {
        synchronized (run) {
            run.content.setLength(0);
            run.reasoning.setLength(run.reasoningBeforeRound);
            run.trace.removeIf(segment -> ChatTrace.SEG_REASONING.equals(segment.type())
                    && Integer.valueOf(round).equals(segment.round()));
            var reset = new ChatDtos.OutputReset(run.reasoning.toString(), List.copyOf(run.trace));
            broadcast(run, "reset", reset);
            return reset;
        }
    }

    /** 生成收尾（正常完成、或用户停止后部分回答已落库）：落定最终正文并广播整份快照，随后移除在途状态。
     *  finalContent 为 null 表示以此刻已产出的部分为准（停止时正文可能没有、也可能只有半截） */
    public ChatDtos.LiveRun finish(Run run, String finalContent) {
        ChatDtos.LiveRun snapshot;
        synchronized (run) {
            if (finalContent != null) {
                run.content.setLength(0);
                run.content.append(finalContent);
            }
            run.running = false;
            snapshot = snapshotOf(run);
            broadcast(run, "done", snapshot);
        }
        runs.remove(run.sessionId, run);
        return snapshot;
    }

    /** 生成失败：落定 running=false 与原因后广播（前端保留已产出的半截输出，便于回退重发），随后移除 */
    public void fail(Run run, String error) {
        synchronized (run) {
            run.running = false;
            run.error = error;
            broadcast(run, "error", snapshotOf(run));
        }
        runs.remove(run.sessionId, run);
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
        if (prev != null && ChatTrace.SEG_REASONING.equals(prev.type())
                && Integer.valueOf(run.round).equals(prev.round())) {
            run.trace.set(last, new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING,
                    nullToEmpty(prev.text()) + delta, null, null, prev.round()));
            return;
        }
        run.trace.add(new ChatDtos.TraceSegment(ChatTrace.SEG_REASONING,
                delta.replaceFirst("^\\n*---\\n+", ""), null, null, run.round));
    }

    private static ChatDtos.LiveRun snapshotOf(Run run) {
        return new ChatDtos.LiveRun(run.sessionId, run.running, run.userText, run.userMessageId,
                run.content.toString(), run.reasoning.toString(), List.copyOf(run.trace), run.error,
                List.copyOf(run.steers), List.copyOf(run.interims), run.round);
    }

    private static ChatDtos.LiveRun notRunning(String sessionId) {
        return new ChatDtos.LiveRun(sessionId, false, null, null, "", "", List.of(), null,
                List.of(), List.of(), 0);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
