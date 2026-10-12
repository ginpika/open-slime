package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.chat.store.HubSessionStore;
import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.ai.core.tool.plan.WriteTodosTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 待办清单的落点（STEP 4）：把 {@link WriteTodosTool} 的一次调用写进 {@code chat_todo}，回一句可读回显。
 *
 * <p>为什么执行不放在工具定义里：工具定义在 {@code ai.core.tool}（最底层），只表达「长什么样」；
 * 落库要碰会话与存储，属编排层的事——所以像计划 / 子代理那样由 {@code ChatService} 分发时转交过来，
 * 底层不必反向依赖会话存储（见 {@code LayeringTest}）。
 *
 * <p>首页会话的清单落库后额外推一条会话事件，供会话页 TODO 卡片即时刷新；圆桌会话（成员各自的
 * 个人清单）不推。归属判断按会话模式（{@code mode='ai'} 为首页），与 owner 是谁无关。
 */
@Service
public class TodoService {

    /** 由本服务执行的工具名（ChatService 分发时据此转交） */
    public static final Set<String> TOOLS = Set.of(WriteTodosTool.NAME);

    private final TodoStore todos;
    private final HubSessionStore sessions;
    private final ObjectMapper om;
    private final SessionEventBus events;

    public TodoService(TodoStore todos, HubSessionStore sessions, ObjectMapper om,
                       SessionEventBus events) {
        this.todos = todos;
        this.sessions = sessions;
        this.om = om;
        this.events = events;
    }

    /**
     * 执行一次清单工具调用：写入「该会话内、该 Agent」的清单，返回给模型的 tool 结果（可读回显）。
     *
     * @param owner 归属 Agent（见 {@code AgentScope}）：同会话里各成员各持一份，互不覆盖
     */
    public String runTool(JsonNode call, String hubId, String owner) {
        String name = call.path("function").path("name").asText("");
        if (!WriteTodosTool.NAME.equals(name)) {
            return "未知工具: " + name;
        }
        if (hubId == null || hubId.isBlank()) {
            return "待办清单需要一个会话归属，本次没有，未写入。";
        }
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        List<TodoStore.TodoItem> items = parse(args.path("todos"));
        if (items.isEmpty()) {
            return "todos 为空，未写入。请给出当前完整清单（至少一条有内容的步骤）。";
        }
        /* 覆盖式写入的软护栏：先取旧清单，写入后比对出「未完成却消失」的步骤回显给模型 */
        List<TodoStore.TodoItem> before = todos.list(hubId, owner);
        todos.replace(hubId, owner, items);
        /* 首页会话（mode='ai'）才推：会话页 TODO 卡片据此即时刷新，与归属 Agent 是谁无关。
           圆桌会话（mode='multi-agent'）的成员个人清单不对首页可见，不推（避免无谓推送） */
        if (sessions.findById(hubId).map(r -> "ai".equals(r.mode())).orElse(false)) {
            events.publish(new SessionEventBus.Event(hubId, SessionEventBus.KIND_TODO, false, null));
        }
        return echo(items, dropped(before, items));
    }

    /** 读取某会话、某归属 Agent 的个人清单（首页会话 TODO 卡片用）：转成界面 DTO */
    public List<ChatDtos.TodoItem> list(String hubId, String owner) {
        return todos.list(hubId, owner).stream()
                .map(i -> new ChatDtos.TodoItem(i.seq(), i.content(), i.status(), i.note()))
                .toList();
    }

    /** 删除会话时一并清掉它的清单（所有 owner）——会话没了，清单不该留成孤儿行 */
    public void clear(String hubId) {
        todos.deleteBySession(hubId);
    }

    /**
     * 一次生成收尾：把该会话该 Agent 遗留的「进行中」收敛为「未开始」（见
     * {@link TodoStore#convergeInProgress}）。中断 / 超限导致的停滞因此不会把清单卡在 in_progress。
     */
    public void settle(String hubId, String owner) {
        if (hubId == null || hubId.isBlank()) {
            return;
        }
        todos.convergeInProgress(hubId, owner);
    }

    /**
     * 解析清单：跳过 content 为空的条目（模型偶尔给半截数据），超过 {@link WriteTodosTool#MAX_ITEMS}
     * 只取前若干条（与工具自检一致，双保险）；单条超长截断（自检已挡，这里再兜一道）。
     * seq 按最终顺序重排，保证落库顺序稳定。
     */
    private static List<TodoStore.TodoItem> parse(JsonNode arr) {
        List<TodoStore.TodoItem> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (out.size() >= WriteTodosTool.MAX_ITEMS) {
                break;
            }
            String content = clip(n.path("content").asText("").trim());
            if (content.isEmpty()) {
                continue;
            }
            String note = n.path("note").asText("").trim();
            out.add(new TodoStore.TodoItem(out.size(), content, n.path("status").asText(""),
                    note.isEmpty() ? null : note));
        }
        return out;
    }

    /** 单条内容截断到工具上限（自检线之外的兜底，避免超长正文进库、进而撑大每轮回灌的 system） */
    private static String clip(String s) {
        return s.length() <= WriteTodosTool.MAX_CONTENT_LEN
                ? s : s.substring(0, WriteTodosTool.MAX_CONTENT_LEN);
    }

    /**
     * 覆盖式写入的软护栏：上一次清单里「未完成（pending / in_progress）却从本次清单消失」的步骤。
     * 覆盖式靠模型给全量，漏带即丢；把这些内容回显给模型，请它自纠（不替它保留——那会破坏覆盖语义）。
     */
    private static List<String> dropped(List<TodoStore.TodoItem> before, List<TodoStore.TodoItem> after) {
        Set<String> now = after.stream().map(TodoStore.TodoItem::content)
                .collect(Collectors.toSet());
        return before.stream()
                .filter(i -> !TodoStore.ST_DONE.equals(i.status()) && !TodoStore.ST_FAILED.equals(i.status()))
                .map(TodoStore.TodoItem::content)
                .filter(c -> !now.contains(c))
                .toList();
    }

    /** 回显：把落库后的清单念回给模型（它的下一轮上下文里还会再看到同一份，这里给的是「写成了」的确认） */
    private static String echo(List<TodoStore.TodoItem> items, List<String> dropped) {
        long failed = items.stream().filter(i -> TodoStore.ST_FAILED.equals(i.status())).count();
        long done = items.stream().filter(i -> TodoStore.ST_DONE.equals(i.status())).count();
        StringBuilder sb = new StringBuilder("清单已更新：共 ").append(items.size()).append(" 步")
                .append("，已完成 ").append(done).append(" 步");
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 步");
        }
        sb.append("。\n").append(TodoStore.render(items));
        if (!dropped.isEmpty()) {
            sb.append("注意：以下未完成的步骤在本次清单里消失了——若非有意删除，请补齐后重写整份清单：\n");
            for (String c : dropped) {
                sb.append("  - ").append(c).append('\n');
            }
        }
        return sb.toString();
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
