package cc.ginpika.minis.agent.roundtable;

import cc.ginpika.minis.agent.member.AgentDirectory;
import cc.ginpika.minis.agent.runtime.event.RuntimeEvent;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventBus;
import cc.ginpika.minis.agent.runtime.event.RuntimeEventType;
import cc.ginpika.minis.agent.member.AgentStore.AgentRow;
import cc.ginpika.minis.ai.core.tool.multiagent.AddTodoTool;
import cc.ginpika.minis.ai.core.tool.multiagent.CompleteTodoTool;
import cc.ginpika.minis.ai.core.tool.multiagent.RequestDiscussionTool;
import cc.ginpika.minis.ai.core.tool.multiagent.StartWorkTool;
import cc.ginpika.minis.ai.core.tool.multiagent.UpdateTodoTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 共享任务工具：管理依赖、负责人和受阻沟通；通过事件交给运行时调度。 */
@Component
public class RoundtableTaskTools {
    private final ObjectMapper om;
    private final RoundtableTodoStore taskBoard;
    private final AgentDirectory directory;
    private final RuntimeEventBus runtimeEvents;

    public RoundtableTaskTools(ObjectMapper om, RoundtableTodoStore taskBoard,
                               AgentDirectory directory, RuntimeEventBus runtimeEvents) {
        this.om = om;
        this.taskBoard = taskBoard;
        this.directory = directory;
        this.runtimeEvents = runtimeEvents;
    }

    public String runTool(String name, JsonNode call, String sessionId, String peerId,
                          String topicId, RoundtableTodoStore.TodoRow currentTodo) {
        if (sessionId == null || peerId == null) {
            return "此刻不在圆桌发言中，共享任务区不可用。";
        }
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        return switch (name) {
            case AddTodoTool.NAME -> addTodo(sessionId, peerId, topicId, args);
            case UpdateTodoTool.NAME -> updateTodo(sessionId, peerId, args);
            case CompleteTodoTool.NAME -> completeTodo(sessionId, peerId, args);
            case StartWorkTool.NAME -> startWork(sessionId, peerId, args);
            case RequestDiscussionTool.NAME -> requestDiscussion(sessionId, peerId, currentTodo, args);
            default -> "未知工具: " + name;
        };
    }

    /** add_todo：往共享任务板加一条 */
    private String addTodo(String sessionId, String peerId, String topicId, JsonNode args) {
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) {
            return "title 不能为空：写清这一步要做什么。";
        }
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员：owner 要么写本桌成员名，要么留空（公共）。";
        }
        /* 前置依赖：声明了就先等它们完成，未完成前没人会开工这条（下游不必在没有上游产出时白跑） */
        List<RoundtableTodoStore.TodoRow> deps = resolveDeps(sessionId, args.path("depends_on"));
        String id = taskBoard.add(sessionId, topicId, owner, title,
                blankToNull(args.path("detail").asText("")), peerId, depsJson(deps));
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_CREATED, sessionId, peerId,
                Map.of("todoId", id, "title", title, "owner", owner == null ? "" : owner)));
        return "已加到共享任务区（id=" + id + "）：" + title
                + (owner == null ? "（公共：谁有空谁做）" : "（负责人：" + directory.nameOf(owner) + "）")
                + (deps.isEmpty() ? ""
                        : "（等 " + String.join("、",
                                deps.stream().map(RoundtableTodoStore.TodoRow::title).toList())
                                + " 完成后开工）")
                + "。桌上所有人都看得见；开工后会有成员按顺序去做。";
    }

    /**
     * 解析 add_todo 的 depends_on：既接受条目 id，也接受标题（模型更记得住标题）。
     * 命中不到的忽略；返回命中的前置条目（保持给定顺序、去重）。
     */
    private List<RoundtableTodoStore.TodoRow> resolveDeps(String sessionId, JsonNode arg) {
        if (arg == null || !arg.isArray()) {
            return List.of();
        }
        List<RoundtableTodoStore.TodoRow> board = taskBoard.list(sessionId);
        List<RoundtableTodoStore.TodoRow> out = new ArrayList<>();
        for (JsonNode n : arg) {
            String raw = n.asText("").strip();
            if (raw.isEmpty()) {
                continue;
            }
            RoundtableTodoStore.TodoRow hit = board.stream()
                    .filter(t -> raw.equals(t.id()) || raw.equals(t.title()))
                    .findFirst().orElse(null);
            if (hit != null && out.stream().noneMatch(t -> t.id().equals(hit.id()))) {
                out.add(hit);
            }
        }
        return out;
    }

    /** 前置清单 → JSON 数组文本；空清单落 null（读侧按「无依赖」处理） */
    private String depsJson(List<RoundtableTodoStore.TodoRow> deps) {
        if (deps.isEmpty()) {
            return null;
        }
        try {
            return om.writeValueAsString(deps.stream().map(RoundtableTodoStore.TodoRow::id).toList());
        } catch (Exception e) {
            return null;
        }
    }

    /** update_todo：改状态 / 负责人 / 说明；标记受阻时可同时做「找谁」的决策（with） */
    private String updateTodo(String sessionId, String peerId, JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) {
            return "id 不能为空：指明要改任务区里的哪一条。";
        }
        RoundtableTodoStore.TodoRow existing = taskBoard.todo(id).orElse(null);
        if (existing == null) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String status = blankToNull(args.path("status").asText(""));
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员。";
        }
        String reason = blankToNull(args.path("reason").asText(""));
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status) && reason == null) {
            return "标记 blocked 时请在 reason 里写清受阻原因（那是请大家再议的凭据）。";
        }
        /* 受阻时先做一次决策：找哪位（可多位，用「、」分隔）、还是找用户拍板、还是全桌一起议。
           名字解析不出来就别落下半截状态——先把它当一次校验失败挡回去。 */
        List<String> targets = List.of();
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status)) {
            targets = parseWith(sessionId, args.path("with").asText(""));
            if (targets == null) {
                return "with 里有认不出的名字：写成员名（可多个，用「、」分隔）、写「用户」请用户拍板，"
                        + "或留空 / 填「全员」（全桌一起议）。";
            }
        }
        taskBoard.update(id, status, null, reason, owner, blankToNull(args.path("detail").asText("")));
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status)) {
            /* 记下这次「找谁」的决策（执行运行时据它决定定向 / 等用户 / 全桌），再发事件、带上条目标题 */
            String csv = String.join(",", targets);
            taskBoard.setBlockWith(id, csv.isEmpty() ? null : csv);
            runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, sessionId, peerId, null,
                    Map.of("todoId", id, "title", existing.title(),
                            "reason", reason == null ? "" : reason,
                            "with", csv)));
        }
        return "已更新任务区（id=" + id + "）" + (status == null ? "。" : "：状态 → " + status + "。");
    }

    /** complete_todo：标记完成并记结论 */
    private String completeTodo(String sessionId, String peerId, JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) {
            return "id 不能为空。";
        }
        if (taskBoard.todo(id).isEmpty()) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String result = blankToNull(args.path("result").asText(""));
        taskBoard.update(id, RoundtableTodoStore.STATUS_DONE, result, null, null, null);
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_COMPLETED, sessionId, peerId,
                Map.of("todoId", id, "result", result == null ? "" : result)));
        return "已完成（id=" + id + "）" + (result == null ? "。" : "：结论已记进任务板。");
    }

    /** start_work：讨论定案、开工（发 WORK_STARTED，由执行运行时接住） */
    private String startWork(String sessionId, String peerId, JsonNode args) {
        if (taskBoard.openOnes(sessionId).isEmpty()) {
            return "任务区里还没有待办：先把要做的事用 " + AddTodoTool.NAME + " 落成条目，再说开工。";
        }
        String note = args.path("note").asText("").strip();
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, sessionId, peerId,
                Map.of("note", note, "by", peerId)));
        return "已宣布开工：各成员的执行线程开始按顺序完成自己名下的条目。讨论不停，你仍可继续发言。";
    }

    /** request_discussion：执行遇阻、请大家再议（发 DISCUSSION_REQUESTED，由讨论运行时接住） */
    private String requestDiscussion(String sessionId, String peerId, RoundtableTodoStore.TodoRow currentTodo, JsonNode args) {
        String reason = args.path("reason").asText("").strip();
        if (reason.isEmpty()) {
            return "reason 不能为空：写清卡在哪、已经试过什么、需要什么决定。";
        }
        /* 同样带着「找谁」的决策：写成员名 = 只和它们定向沟通一轮；写「用户」= 全桌暂停等用户；
           留空 / 「全员」= 全桌一起议 */
        List<String> targets = parseWith(sessionId, args.path("with").asText(""));
        if (targets == null) {
            return "with 里有认不出的名字：写成员名（可多个，用「、」分隔）、写「用户」请用户拍板，"
                    + "或留空 / 填「全员」（全桌一起议）。";
        }
        /* 执行态：把这次决策记到当前待办上（受阻走的是 update_todo(blocked) → 执行运行时兜底上报，
           运行时读的就是这条 block_with）。只写不覆盖空值——update_todo 已经写过的以它为准 */
        RoundtableTodoStore.TodoRow cur = currentTodo;
        if (cur != null && !targets.isEmpty()) {
            taskBoard.setBlockWith(cur.id(), String.join(",", targets));
        }
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.DISCUSSION_REQUESTED, sessionId, peerId,
                Map.of("reason", reason, "by", peerId, "with", String.join(",", targets))));
        if (targets.isEmpty()) {
            return "已把问题抛回桌面：全桌会被激活，一起决定怎么办。";
        }
        if (targets.contains(RoundtableTodoStore.BLOCK_WITH_USER)) {
            return "已把问题交给用户拍板：全桌暂停，等用户发言后继续。";
        }
        return "已把问题定向抛给「" + namesOf(targets) + "」：只你们沟通一轮，其余成员不受打扰。";
    }

    /**
     * 解析 {@code with}（受阻成员自己做的「找谁」决策）：
     * 返回目标列表——成员 id，或 {@link RoundtableTodoStore#BLOCK_WITH_USER}（用户）。
     * <b>空列表 = 全桌</b>（留空 / 「全员」）；<b>null = 无效</b>（有认不出的名字，由调用方报错）。
     *
     * <p>多个目标用「、」「,」「，」等分隔；{@code @名字} 里的 @ 会被忽略，方便直接抄界面上的写法。
     */
    private List<String> parseWith(String sessionId, String raw) {
        List<String> targets = new ArrayList<>();
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty() || isEveryone(s)) {
            return targets;   // 全桌
        }
        for (String token : s.split("[、,，;；/\\s]+")) {
            String name = token.replace("@", "").strip();
            if (name.isEmpty() || isEveryone(name)) {
                continue;
            }
            if (isUser(name)) {
                if (!targets.contains(RoundtableTodoStore.BLOCK_WITH_USER)) {
                    targets.add(RoundtableTodoStore.BLOCK_WITH_USER);
                }
                continue;
            }
            String id = resolveOwner(sessionId, name);
            if (id == null) {
                return null;   // 有认不出的名字：整体判为无效，别落下半截决策
            }
            if (!targets.contains(id)) {
                targets.add(id);
            }
        }
        return targets;
    }

    /** {@code with} 里表示「找用户拍板」的说法（成员 id 是 uuid，不会与它撞上） */
    private static boolean isUser(String with) {
        return Set.of("用户", "user", "人类").contains(with.strip());
    }

    /** 把目标列表拼成展示名（用户 → 「用户」） */
    private String namesOf(List<String> targets) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(RoundtableTodoStore.BLOCK_WITH_USER.equals(t) ? "用户" : directory.nameOf(t));
        }
        return sb.toString();
    }

    /** {@code with} 里表示「全桌一起议」的说法（留空也算）：不是这些、又解析不出成员名时按无效处理 */
    private static boolean isEveryone(String with) {
        if (with == null || with.isBlank()) {
            return true;
        }
        return Set.of("全员", "全桌", "所有人", "全体", "全部", "大家", "all").contains(with.strip());
    }

    /** 按名字在会话成员里找 id（找不到返回 null） */
    private String resolveOwner(String sessionId, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.strip();
        return directory.peers(sessionId).stream()
                .filter(p -> target.equals(p.name()))
                .map(AgentRow::id)
                .findFirst()
                .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private JsonNode readTree(String json) {
        try {
            return om.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

}
