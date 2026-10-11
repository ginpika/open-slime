package cc.ginpika.minis.agent.chat;

import cc.ginpika.minis.agent.chat.dto.ChatDtos;
import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.TodoStore;
import cc.ginpika.minis.ai.core.tool.plan.WriteTodosTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 待办清单的落点（STEP 4）：把 {@link WriteTodosTool} 的一次调用写进 {@code chat_todo}，回一句可读回显。
 *
 * <p>为什么执行不放在工具定义里：工具定义在 {@code ai.core.tool}（最底层），只表达「长什么样」；
 * 落库要碰会话与存储，属编排层的事——所以像计划 / 子代理那样由 {@code ChatService} 分发时转交过来，
 * 底层不必反向依赖会话存储（见 {@code LayeringTest}）。
 *
 * <p>首页会话的清单（owner=内置 Agent）落库后额外推一条会话事件，供会话页 TODO 卡片即时刷新。
 */
@Service
public class TodoService {

    /** 由本服务执行的工具名（ChatService 分发时据此转交） */
    public static final Set<String> TOOLS = Set.of(WriteTodosTool.NAME);

    private final TodoStore todos;
    private final ObjectMapper om;
    private final SessionEventBus events;

    public TodoService(TodoStore todos, ObjectMapper om, SessionEventBus events) {
        this.todos = todos;
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
        todos.replace(hubId, owner, items);
        /* 首页会话的清单（owner=内置 Agent）更新后推一条事件：会话页 TODO 卡片据此即时刷新。
           圆桌成员的个人清单暂不对界面可见，不发（避免无谓推送） */
        if (AgentDirectory.BUILTIN_ID.equals(owner)) {
            events.publish(new SessionEventBus.Event(hubId, SessionEventBus.KIND_TODO, false, null));
        }
        return echo(items);
    }

    /** 读取某会话「内置 Agent」的个人清单（首页会话 TODO 卡片用）：转成界面 DTO */
    public List<ChatDtos.TodoItem> list(String hubId) {
        return todos.list(hubId, AgentDirectory.BUILTIN_ID).stream()
                .map(i -> new ChatDtos.TodoItem(i.seq(), i.content(), i.status(), i.note()))
                .toList();
    }

    /**
     * 解析清单：跳过 content 为空的条目（模型偶尔给半截数据），超过 {@link WriteTodosTool#MAX_ITEMS}
     * 只取前若干条（与工具自检一致，双保险）。seq 按最终顺序重排，保证落库顺序稳定。
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
            String content = n.path("content").asText("").trim();
            if (content.isEmpty()) {
                continue;
            }
            String note = n.path("note").asText("").trim();
            out.add(new TodoStore.TodoItem(out.size(), content, n.path("status").asText(""),
                    note.isEmpty() ? null : note));
        }
        return out;
    }

    /** 回显：把落库后的清单念回给模型（它的下一轮上下文里还会再看到同一份，这里给的是「写成了」的确认） */
    private static String echo(List<TodoStore.TodoItem> items) {
        long failed = items.stream().filter(i -> TodoStore.ST_FAILED.equals(i.status())).count();
        long done = items.stream().filter(i -> TodoStore.ST_DONE.equals(i.status())).count();
        StringBuilder sb = new StringBuilder("清单已更新：共 ").append(items.size()).append(" 步")
                .append("，已完成 ").append(done).append(" 步");
        if (failed > 0) {
            sb.append("，失败 ").append(failed).append(" 步");
        }
        return sb.append("。\n").append(TodoStore.render(items)).toString();
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
