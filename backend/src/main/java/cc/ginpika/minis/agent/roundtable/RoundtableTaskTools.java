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
import cc.ginpika.minis.ai.core.tool.multiagent.ReviewTodoTool;
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
    private final CollaborationStore collaboration;
    private final CollaborationIssueActions issues;
    private final CollaborationPlan plans;
    private final RoundtableMessageStore messages;
    private final CollaborationScopeGuard scopeGuard;
    private final cc.ginpika.minis.coding.CodingFacade coding;
    private final CollaborationQuality quality;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final cc.ginpika.minis.project.ProjectKnowledgeStore knowledge;

    public RoundtableTaskTools(ObjectMapper om, RoundtableTodoStore taskBoard,
                               AgentDirectory directory, RuntimeEventBus runtimeEvents,
                               CollaborationStore collaboration, CollaborationIssueActions issues,
                               CollaborationPlan plans, RoundtableMessageStore messages, CollaborationScopeGuard scopeGuard,cc.ginpika.minis.coding.CodingFacade coding,CollaborationQuality quality,
                               org.springframework.transaction.support.TransactionTemplate transactions,
                               cc.ginpika.minis.project.ProjectKnowledgeStore knowledge) {
        this.om = om;
        this.taskBoard = taskBoard;
        this.directory = directory;
        this.runtimeEvents = runtimeEvents;
        this.collaboration = collaboration;
        this.issues = issues;
        this.plans = plans;
        this.messages = messages;
        this.scopeGuard = scopeGuard;
        this.coding = coding;
        this.quality = quality;
        this.transactions = transactions;
        this.knowledge = knowledge;
    }

    public String runTool(String name, JsonNode call, String sessionId, String peerId,
                          String topicId, RoundtableTodoStore.TodoRow currentTodo) {
        if (sessionId == null || peerId == null) {
            return "此刻不在圆桌发言中，共享任务区不可用。";
        }
        JsonNode args = readTree(call.path("function").path("arguments").asText(""));
        if (Set.of("read_project_knowledge","write_project_knowledge").contains(name))
            return projectKnowledge(name,args,sessionId,peerId,currentTodo);
        try {
            if(args.has("contract"))CollaborationQuality.validateContract(args.get("contract"));
            if(ReviewTodoTool.NAME.equals(name))CollaborationQuality.validateFindings(args.get("findings"),args.path("accept").asBoolean());
        }catch(IllegalArgumentException error){return error.getMessage()+"；本次未修改任务。";}
        var localIssue = collaboration.issueBySession(sessionId).orElse(null);
        if (localIssue != null && "resolved".equals(localIssue.status())) return "该议题已有结论，请回到主任务继续。";
        if (localIssue != null && !Set.of("resolve_issue", RequestDiscussionTool.NAME).contains(name))
            return "议题用于解决主任务的局部问题，不另建执行任务板。请沟通后用 resolve_issue 回传结论，需要用户时 request_discussion。";
        if ("resolve_issue".equals(name)) {
            var issue = localIssue;
            if (issue == null || currentTodo != null) return "请仅在议题讨论内提交结论。";
            if (directory.peers(sessionId).stream().noneMatch(p -> p.id().equals(peerId))) return "你不是本议题成员。";
            if (!issues.heardAnotherMember(sessionId, peerId)) return "请先听取另一位成员的回应，再形成议题结论。";
            String conclusion = args.path("conclusion").asText("").strip();
            if (conclusion.isEmpty() || conclusion.length() > 8000) return "结论须为 1–8000 字。";
            issues.resolve(issue, conclusion);
            return "议题结论已回传主任务，关联待办将依据结论继续执行。";
        }
        if (currentTodo != null && "checking".equals(currentTodo.status()) && !ReviewTodoTool.NAME.equals(name))
            return "当前是成果检查，只能用 review_todo 提交检查结论。";
        var plan = plans.find(sessionId).orElse(null);
        var assignment = currentTodo == null ? null : plans.assignment(sessionId,currentTodo.id()).orElse(null);
        if (plan != null && currentTodo != null && (assignment == null
                || !("checking".equals(currentTodo.status()) ? "checking".equals(assignment.phase()) && peerId.equals(assignment.reviewerId())
                : "executing".equals(assignment.phase()) && peerId.equals(assignment.executorId()))))
            return "此派工已失效，请将结果交回主控，不能继续修改任务板。";
        if (plan != null && currentTodo == null && RequestDiscussionTool.NAME.equals(name) && !plans.coordinator(sessionId, peerId))
            return "请将意见交给主控，只有主控决定是否发起议题。";
        if (plan != null && Set.of("add_todo", "start_work", "manage_plan").contains(name)
                && (currentTodo != null || !plan.coordinatorId().equals(peerId)))
            return "只有主控在规划时能修改计划或派工；请将新发现写入成果或受阻原因，交回主控。";
        if (plan != null && Set.of("add_todo", "manage_plan", "start_work").contains(name)
                && !"planning".equals(plan.phase())) return "当前派工尚未回报或任务已交付，请等待检查结果，用户新要求可以重新开启规划。";
        if (plan != null && currentTodo == null && UpdateTodoTool.NAME.equals(name) && !"planning".equals(plan.phase()))
            return "请等当前派工回报后调整计划。";
        if (plan != null && UpdateTodoTool.NAME.equals(name) && (currentTodo != null || !plan.coordinatorId().equals(peerId))) {
            if (currentTodo == null || !currentTodo.id().equals(args.path("id").asText())
                    || !"blocked".equals(args.path("status").asText()) || args.has("owner") || args.has("detail") || args.has("title") || args.has("depends_on") || args.has("contract"))
                return "执行者只能将当前条目标记受阻；计划变更由主控决定。";
        }
        return switch (name) {
            case "manage_plan" -> managePlan(sessionId, peerId, args);
            case AddTodoTool.NAME -> addTodo(sessionId, peerId, topicId, args);
            case UpdateTodoTool.NAME -> updateTodo(sessionId, peerId, args);
            case CompleteTodoTool.NAME -> completeTodo(sessionId, peerId, currentTodo, args);
            case ReviewTodoTool.NAME -> reviewTodo(sessionId, peerId, currentTodo, args);
            case StartWorkTool.NAME -> startWork(sessionId, peerId, args);
            case RequestDiscussionTool.NAME -> requestDiscussion(sessionId, peerId, currentTodo, args);
            default -> "未知工具: " + name;
        };
    }

    private String projectKnowledge(String name, JsonNode args, String session, String peer, RoundtableTodoStore.TodoRow todo) {
        if(directory.peers(session).stream().noneMatch(p->peer.equals(p.id()))) return "你不是当前协作会话的成员。";
        String project=knowledge.projectForSession(session);
        if(project==null) return "当前会话尚未归属项目，请先由用户整理到项目。";
        try {
            Object result;
            String key=args.path("key").asText("");
            if("read_project_knowledge".equals(name)) {
                result=switch(args.path("action").asText("list")) {
                    case "list" -> knowledge.list(project,args.path("query").asText(""),args.path("include_retired").asBoolean(),args.path("offset").asInt(0),args.path("limit").asInt(20));
                    case "read" -> knowledge.read(project,key,args.has("version")?args.path("version").asInt():null);
                    case "history" -> knowledge.history(project,key);
                    case "progress" -> knowledge.progress(project,args.path("offset").asInt(0),args.path("limit").asInt(20));
                    default -> throw new IllegalArgumentException("action 使用 list/read/history/progress");
                };
            } else {
                if(todo!=null || !plans.coordinator(session,peer) || collaboration.issueBySession(session).isPresent()) return "只有主会话的主控可维护项目资料；请将新发现交回主控。";
                if(!List.of("overview","decision","reference").contains(args.path("kind").asText())) return "请选择 overview、decision 或 reference；delivery 由交付流程保存。";
                var request=new cc.ginpika.minis.project.ProjectKnowledgeStore.Write(args.path("kind").asText(),args.path("title").asText(),args.path("content").asText(),
                        args.has("expected_version")?args.path("expected_version").asInt():null,args.path("retired").asBoolean());
                result=knowledge.save(project,key,request,session,peer);
            }
            return om.writeValueAsString(result);
        } catch(org.springframework.web.server.ResponseStatusException error) { return error.getReason(); }
        catch(Exception error) { return "项目资料操作失败："+error.getMessage(); }
    }

    /** add_todo：往共享任务板加一条 */
    private String addTodo(String sessionId, String peerId, String topicId, JsonNode args) {
        String title = args.path("title").asText("").strip();
        if (title.isEmpty()) {
            return "title 不能为空：写清这一步要做什么。";
        }
        if (plans.find(sessionId).isPresent()) {
            if(plans.scope(sessionId).isEmpty()) return "请先用 manage_plan 保存本次交付物、范围外事项与现状依据，再创建工作。";
            if (args.path("detail").asText("").isBlank()) return "detail 不能为空：写明工作边界、产出和验收方法。";
            var duplicate = taskBoard.list(sessionId).stream().filter(t -> !"dropped".equals(t.status()) && title.equals(t.title())).findFirst();
            if (duplicate.isPresent()) return "已有同名工作（id=" + duplicate.get().id() + "），请修订原条目，避免重复创建。";
        }
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员：owner 要么写本桌成员名，要么留空（公共）。";
        }
        /* 前置依赖：声明了就先等它们完成，未完成前没人会开工这条（下游不必在没有上游产出时白跑） */
        List<RoundtableTodoStore.TodoRow> deps = resolveDeps(sessionId, args.path("depends_on"));
        if (deps == null) return "depends_on 包含无效或不存在的依赖，请使用本任务已有条目的 id。";
        if(plans.find(sessionId).isPresent()) {
            String refusal=scopeGuard.check(sessionId,"具体 TODO",title+"\n"+args.path("detail").asText()+"\n"+args.path("contract"));
            if(!refusal.isEmpty()) return refusal;
        }
        String id = transactions.execute(tx->{
            String created=taskBoard.add(sessionId, topicId, owner, title,
                    blankToNull(args.path("detail").asText("")), peerId, depsJson(deps));
            if(args.hasNonNull("contract"))quality.contract(created,args.get("contract"));
            return created;
        });
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
        if (arg == null || arg.isMissingNode() || arg.isNull()) {
            return List.of();
        }
        if (!arg.isArray()) return null;
        List<RoundtableTodoStore.TodoRow> board = taskBoard.list(sessionId);
        List<RoundtableTodoStore.TodoRow> out = new ArrayList<>();
        for (JsonNode n : arg) {
            String raw = n.asText("").strip();
            if (raw.isEmpty()) return null;
            RoundtableTodoStore.TodoRow hit = board.stream()
                    .filter(t -> raw.equals(t.id()) || raw.equals(t.title()))
                    .findFirst().orElse(null);
            if (hit == null) return null;
            if (out.stream().noneMatch(t -> t.id().equals(hit.id()))) {
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
        if (existing == null || !sessionId.equals(existing.sessionId())) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String status = blankToNull(args.path("status").asText(""));
        var plan = plans.find(sessionId).orElse(null);
        if(plan!=null && (args.hasNonNull("title") || args.hasNonNull("detail") || args.hasNonNull("contract"))) {
            String refusal=scopeGuard.check(sessionId,"具体 TODO",args.path("title").asText(existing.title())+"\n"+args.path("detail").asText(existing.detail())+"\n"+args.path("contract"));
            if(!refusal.isEmpty()) return refusal;
        }
        if (plan != null && plans.coordinator(sessionId, peerId) && !"doing".equals(existing.status())
                && !"planning".equals(plan.phase())) return "请等当前派工回报后调整计划。";
        if (status != null && !Set.of("pending", "blocked", "dropped").contains(status))
            return "状态只能改为 pending、blocked 或 dropped；完成须提交成果并由另一成员检查。";
        boolean reopen = plan != null && "planning".equals(plan.phase()) && "pending".equals(status)
                && Set.of("done", "blocked", "dropped").contains(existing.status());
        if (Set.of("done", "dropped", "reviewing", "checking").contains(existing.status()) && !reopen)
            return "该条目已提交检查或结束，不能用 update_todo 覆盖。";
        if ("doing".equals(existing.status()) && !peerId.equals(existing.ownerPeer()))
            return "条目正在由其他成员执行，请先沟通，不能改写在途工作。";
        if ("doing".equals(existing.status()) && ("pending".equals(status) || !args.path("owner").asText("").isBlank()))
            return "执行中不能重新排队或换人；请先标 blocked 并说明原因。";
        String ownerName = args.path("owner").asText("").strip();
        String owner = ownerName.isEmpty() ? null : resolveOwner(sessionId, ownerName);
        if (!ownerName.isEmpty() && owner == null) {
            return "桌上没有叫「" + ownerName + "」的成员。";
        }
        String reason = blankToNull(args.path("reason").asText(""));
        if (reopen && reason == null) return "重新派工需要 reason，说明返工或重开依据。";
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
        var deps = resolveDeps(sessionId, args.path("depends_on"));
        if (deps == null || deps.stream().anyMatch(t -> id.equals(t.id()) || dependsOn(t, id, new java.util.HashSet<>())))
            return "依赖无效或形成循环，本次修改未生效。";
        transactions.executeWithoutResult(tx->{
            if (reopen && !taskBoard.transition(id, sessionId, existing.status(), "pending", null, null))
                throw new IllegalStateException("条目状态已经变化，请核对后重新操作。");
            if (reopen) taskBoard.setBlockWith(id, null);
            taskBoard.update(id, status, null, reopen ? null : reason, owner, blankToNull(args.path("detail").asText("")));
            taskBoard.revise(id, blankToNull(args.path("title").asText("")), args.has("depends_on") ? (deps.isEmpty() ? "[]" : depsJson(deps)) : null);
            if(args.hasNonNull("contract"))quality.contract(id,args.get("contract"));
        });
        if (plan != null && plans.coordinator(sessionId, peerId)) record(sessionId, "主控修订「" + existing.title() + "」：" + args);
        if (RoundtableTodoStore.STATUS_BLOCKED.equals(status)) {
            /* 记下这次「找谁」的决策（执行运行时据它决定定向 / 等用户 / 全桌），再发事件、带上条目标题 */
            String csv = String.join(",", targets);
            taskBoard.setBlockWith(id, csv.isEmpty() ? null : csv);
            runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED, sessionId, peerId, csv, id, null,
                    Map.of("todoId", id, "title", existing.title(),
                            "reason", reason == null ? "" : reason,
                            "with", csv)));
        }
        return "已更新任务区（id=" + id + "）" + (status == null ? "。" : "：状态 → " + status + "。");
    }

    /** complete_todo：标记完成并记结论 */
    private String completeTodo(String sessionId, String peerId, RoundtableTodoStore.TodoRow current, JsonNode args) {
        String id = args.path("id").asText("").strip();
        if (id.isEmpty()) {
            return "id 不能为空。";
        }
        var todo = taskBoard.todo(id).orElse(null);
        if (todo == null || !sessionId.equals(todo.sessionId())) {
            return "任务区里没有这条（id=" + id + "）。";
        }
        String result = blankToNull(args.path("result").asText(""));
        if (current == null || !id.equals(current.id()) || !peerId.equals(todo.ownerPeer()))
            return "只能提交当前由你执行的条目。";
        if (result == null) return "result 不能为空：请给出实际产出位置、验证方法和结果，供另一位成员检查。";
        if(plans.find(sessionId).isPresent()) {
            try {String blocked=coding.collaborationSubmission(cc.ginpika.minis.ai.core.UserScope.get(),sessionId,peerId,id);if(!blocked.isEmpty())return blocked;}
            catch(RuntimeException error){return "编码成果尚未满足提交条件："+error.getMessage();}
        }
        if (!taskBoard.transition(id, sessionId, "doing", "reviewing", result, null))
            return "条目状态已改变，本次提交未生效。";
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_SUBMITTED, sessionId, peerId,
                Map.of("todoId", id, "result", result)));
        return "成果已提交检查；另一位成员确认后才会完成并解锁下游。";
    }

    private String reviewTodo(String sessionId, String peerId, RoundtableTodoStore.TodoRow current, JsonNode args) {
        String id = args.path("id").asText("");
        var plan = plans.find(sessionId).orElse(null);
        var assignment=plans.assignment(sessionId,id).orElse(null);
        if (plan != null && (assignment==null || !"checking".equals(assignment.phase()) || !peerId.equals(assignment.reviewerId())))
            return "你不是本次派工的检查者。";
        if (current == null || !"checking".equals(current.status()) || !id.equals(current.id())
                || !sessionId.equals(current.sessionId()) || peerId.equals(current.ownerPeer()))
            return "只能检查系统分配给你的他人成果。";
        String evidence = args.path("evidence").asText("").strip();
        if (!args.path("accept").isBoolean() || evidence.isEmpty()) return "请提供 accept 和实际检查证据 evidence。";
        boolean accept = args.path("accept").asBoolean();
        if(!accept && quality.find(id).contract().isObject() && (!args.path("findings").isArray() || args.path("findings").isEmpty()))
            return "此工作有明确验收契约；拒绝时请提供 findings，说明具体阻塞位置、期望结果和验收依据。一般建议不阻碍交付。";
        var artifacts=plan==null?java.util.List.<java.util.Map<String,String>>of():coding.collaborationArtifacts(cc.ginpika.minis.ai.core.UserScope.get(),sessionId,id);
        String artifact=artifacts.stream().filter(a->!a.get("artifactHash").isBlank()).map(a->a.get("runId")+"="+a.get("artifactHash")).sorted().collect(java.util.stream.Collectors.joining(";"));
        if(!accept && quality.repeatsBlockers(id,args.get("findings"),artifact))
            return diagnose(sessionId,peerId,current,"NEEDS_DIAGNOSIS","同一产物反复收到相同阻塞意见，请核对指令回执和实际修改落点，再决定返工策略。",artifacts.isEmpty()?"":artifacts.getLast().get("runId"),artifacts.isEmpty()?"":artifacts.getLast().get("artifactHash"));
        if(accept && plan!=null) {
            try {String blocked=coding.applyCollaboration(cc.ginpika.minis.ai.core.UserScope.get(),sessionId,peerId,id);if(!blocked.isEmpty())return blocked;}
            catch(RuntimeException error){return diagnose(sessionId,peerId,current,"APPLICATION_CONFLICT","检查后应用未确认："+error.getMessage(),artifacts.isEmpty()?"":artifacts.getLast().get("runId"),artifacts.isEmpty()?"":artifacts.getLast().get("artifactHash"));}
        }
        String result = accept ? current.result() + "\n\n检查人：" + directory.nameOf(peerId) + "\n检查证据：" + evidence : null;
        boolean saved=Boolean.TRUE.equals(transactions.execute(tx->{
            if(!taskBoard.transition(id, sessionId, "checking", accept ? "done" : "pending", result,
                    accept ? null : "检查未通过：" + evidence))return false;
            quality.review(id,args.get("findings"),accept,plan==null?0:plan.version(),evidence,artifact);
            return true;
        }));
        if(!saved)return "检查状态已改变，本次结论未生效。";
        runtimeEvents.publish(RuntimeEvent.of(accept ? RuntimeEventType.TASK_COMPLETED : RuntimeEventType.TASK_REVISED,
                sessionId, peerId, Map.of("todoId", id, "evidence", evidence)));
        return accept ? "检查通过，已完成并解锁下游。" : "已附具体检查意见交回负责人返工。";
    }

    /** start_work：讨论定案、开工（发 WORK_STARTED，由执行运行时接住） */
    private String startWork(String sessionId, String peerId, JsonNode args) {
        if (plans.find(sessionId).isPresent()) return dispatchWork(sessionId, peerId, args);
        if (taskBoard.openOnes(sessionId).isEmpty()) {
            return "任务区里还没有待办：先把要做的事用 " + AddTodoTool.NAME + " 落成条目，再说开工。";
        }
        String note = args.path("note").asText("").strip();
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.WORK_STARTED, sessionId, peerId,
                Map.of("note", note, "by", peerId)));
        return "已宣布开工：各成员的执行线程开始按顺序完成自己名下的条目。讨论不停，你仍可继续发言。";
    }

    private boolean dependsOn(RoundtableTodoStore.TodoRow todo, String target, Set<String> seen) {
        if (!seen.add(todo.id())) return false;
        for (JsonNode dep : readTree(todo.dependsOn())) {
            if (target.equals(dep.asText()) || taskBoard.todo(dep.asText()).map(t -> dependsOn(t, target, seen)).orElse(false)) return true;
        }
        return false;
    }

    private String managePlan(String session, String peer, JsonNode args) {
        if (!plans.coordinator(session, peer)) return "仅主控可管理计划。";
        String action = args.path("action").asText("");
        if ("revise".equals(action)) {
            String criteria = args.path("criteria").asText("").strip(), note = args.path("note").asText("").strip();
            if (criteria.isEmpty() || note.isEmpty()) return "请提供 criteria（整体验收标准）与 note（计划依据）。";
            var scope=new CollaborationPlan.Scope(args.path("deliverables").asText("").strip(),args.path("exclusions").asText("").strip(),
                    args.path("baseline").asText("").strip(),args.path("roadmap").asText("").strip());
            if(scope.deliverables().isBlank() || scope.exclusions().isBlank() || scope.baseline().isBlank())return "请提供 deliverables（本次交付物）、exclusions（范围外事项）、baseline（实查现状与证据），不要只复制项目方向。";
            String refusal=scopeGuard.check(session,"会话计划","本次交付物："+scope.deliverables()+"\n本次不包含："+scope.exclusions()
                    +"\n现状依据："+scope.baseline()+"\n未来路线图（不执行）："+scope.roadmap()+"\n验收："+criteria);
            if(!refusal.isEmpty())return refusal;
            if (!plans.revise(session, criteria, note, scope)) return "当前不能调整计划，请等待派工回报。";
            record(session, "本次交付物："+scope.deliverables()+"\n本次不包含："+scope.exclusions()+"\n现状依据："+scope.baseline()
                    +"\n后续方向（不自动执行）："+scope.roadmap()+"\n主控验收标准：" + criteria + "\n计划依据：" + note);
            return "已保存验收标准和计划依据，请创建或修订具体工作并明确派工。";
        }
        if (!"finish".equals(action)) return "action 只能为 revise 或 finish。";
        String evidence = args.path("evidence").asText("").strip();
        if (evidence.isEmpty()) return "请提供实际整体验证证据 evidence。";
        if(plans.scope(session).isEmpty())return "尚未明确本次范围，请先修订计划。";
        if (!taskBoard.openOnes(session).isEmpty() || collaboration.issues().stream().anyMatch(i -> session.equals(i.parentSessionId()) && "open".equals(i.status())))
            return "仍有未完成工作或开放议题，请先处理，再核对整体目标。";
        String refusal=scopeGuard.check(session,"会话交付","验收与交付证据："+evidence);
        if(!refusal.isEmpty())return refusal;
        if (!plans.finish(session, evidence)) return "尚未设置验收标准或派工仍在进行，不能交付。";
        record(session, "主控确认整体交付：\n" + evidence);
        return "已确认整体交付。请向用户说明成果、实际验证及限制。";
    }

    private String dispatchWork(String session, String peer, JsonNode args) {
        if(plans.scope(session).isEmpty())return "请先明确本次交付物、范围外事项与现状依据，不能按历史大纲直接派工。";
        if (collaboration.issues().stream().anyMatch(i -> session.equals(i.parentSessionId()) && "open".equals(i.status())))
            return "请先收取开放议题的结论，再决定派工。";
        String note = args.path("note").asText("").strip();
        if (note.isEmpty()) return "note 不能为空：说明本次派工或返工依据。";
        java.util.List<JsonNode> requests=new java.util.ArrayList<>();
        if(args.has("assignments")) {
            if(args.has("id") || args.has("reviewer"))return "assignments 与单项 id/reviewer 不能混用。";
            if(!args.path("assignments").isArray() || args.path("assignments").isEmpty() || args.path("assignments").size()>8)return "每批派工为 1–8 项。";
            args.path("assignments").forEach(requests::add);
        } else requests.add(args);
        java.util.List<CollaborationPlan.Dispatch> dispatches=new java.util.ArrayList<>();
        for(var request:requests) {
        var todo = taskBoard.todo(request.path("id").asText("")).filter(t -> session.equals(t.sessionId())).orElse(null);
        if (todo == null || !Set.of("pending", "reviewing").contains(todo.status())) return "id 必须是本任务待执行或待检查条目；整批未派工。";
        if(dispatches.stream().anyMatch(d->d.todoId().equals(todo.id())))return "同一条目不能重复派工；整批未派工。";
        String executor = todo.ownerPeer();
        if (executor == null) return "请先用 update_todo 明确执行负责人，再派工。";
        String reviewer = resolveOwner(session, request.path("reviewer").asText(""));
        if (reviewer == null || reviewer.equals(executor)) return "reviewer 必须是本任务内另一位成员，不能由执行者自检通过。";
        if (directory.peers(session).stream().noneMatch(p -> p.id().equals(todo.ownerPeer()))) return "执行负责人已不在本任务，请先改派。";
        if ("pending".equals(todo.status()) && taskBoard.claimable(session, executor).stream().noneMatch(t -> t.id().equals(todo.id()))) return "前置依赖尚未完成，请先解决上游。";
        String refusal=scopeGuard.check(session,"具体 TODO",todo.title()+"\n"+todo.detail()+quality.context(todo.id()));
        if(!refusal.isEmpty()) return refusal;
        dispatches.add(new CollaborationPlan.Dispatch(todo.id(),executor,reviewer,"reviewing".equals(todo.status())?"checking":"executing",note));
        }
        boolean dispatched=Boolean.TRUE.equals(transactions.execute(tx->{
            // Scope review above can take time: validate live ownership/status/dependencies again in the short transaction.
            for(var d:dispatches) {
                var live=taskBoard.todo(d.todoId()).orElse(null);
                if(live==null || !session.equals(live.sessionId()) || !d.executorId().equals(live.ownerPeer())
                        || !("checking".equals(d.phase())?"reviewing":"pending").equals(live.status())
                        || ("executing".equals(d.phase()) && taskBoard.claimable(session,d.executorId()).stream().noneMatch(t->d.todoId().equals(t.id()))))return false;
            }
            if(!plans.dispatchBatch(session,dispatches,note))return false;
            dispatches.forEach(d->quality.reassigned(d.todoId(),note));return true;
        }));
        if (!dispatched)
            return "请先保存整体验收标准，并等待上一项派工回报。";
        record(session, "主控派工 "+dispatches.size()+" 项：\n"+dispatches.stream().map(d->d.todoId()+"；执行："+directory.nameOf(d.executorId())+"；检查："+directory.nameOf(d.reviewerId())).collect(java.util.stream.Collectors.joining("\n"))+"\n依据："+note);
        return "已授权这批 "+dispatches.size()+" 项工作；本次发言结束后各自用独立副本并行执行，逐项独立检查，整批结束回报主控。请结束发言并等待。";
    }

    private void record(String session, String text) { messages.insertNotice(session, null, text, "plan_decision"); }

    public String diagnose(String session,String peer,RoundtableTodoStore.TodoRow todo,String code,String reason,String run,String hash){
        if(todo==null || !session.equals(todo.sessionId()) || !Set.of("doing","checking").contains(todo.status()))return "当前工作不存在或已回报，未修改。";
        var plan=plans.find(session).orElse(null);
        var assignment=plans.assignment(session,todo.id()).orElse(null);
        if(plan!=null && (assignment==null || !("checking".equals(todo.status()) ? "checking".equals(assignment.phase()) && peer.equals(assignment.reviewerId()) : "executing".equals(assignment.phase()) && peer.equals(assignment.executorId()))))return "派工已改变，诊断未写入。";
        boolean saved=Boolean.TRUE.equals(transactions.execute(tx->{
            if(!taskBoard.transition(todo.id(),session,todo.status(),"blocked",null,"["+code+"] "+reason))return false;
            quality.diagnose(todo.id(),code,reason,run,hash);return true;
        }));
        if(!saved)return "工作状态已改变，请核对最新状态。";
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.TASK_BLOCKED,session,peer,"",todo.id(),null,Map.of("reason",reason,"code",code,"runId",run,"artifactHash",hash)));
        return "已交回主控诊断（"+code+"），保留现有产物，不自动重做："+reason;
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
        if (plans.find(sessionId).isPresent() && currentTodo == null && !targets.contains("user")) {
            if (!"planning".equals(plans.find(sessionId).orElseThrow().phase())) return "请等待当前派工回报再讨论计划。";
            if (targets.isEmpty()) targets = directory.peers(sessionId).stream().map(AgentRow::id).filter(id -> !id.equals(peerId)).toList();
            else targets = targets.stream().filter(id -> !id.equals(peerId)).toList();
            if (targets.isEmpty()) return "请指定至少一位其他成员参与议题。";
            if (collaboration.issues().stream().anyMatch(i -> sessionId.equals(i.parentSessionId()) && "open".equals(i.status()))) return "已有开放议题，请先等待结论，不重复创建。";
        }
        /* 执行态：把这次决策记到当前待办上（受阻走的是 update_todo(blocked) → 执行运行时兜底上报，
           运行时读的就是这条 block_with）。只写不覆盖空值——update_todo 已经写过的以它为准 */
        RoundtableTodoStore.TodoRow cur = currentTodo;
        if (cur != null && sessionId.equals(cur.sessionId())) {
            if (!taskBoard.transition(cur.id(), sessionId, "doing", "blocked", null, reason))
                return "当前工作状态已改变，请依据最新任务板处理。";
            taskBoard.setBlockWith(cur.id(), String.join(",", targets));
        }
        runtimeEvents.publish(RuntimeEvent.of(RuntimeEventType.DISCUSSION_REQUESTED, sessionId, peerId,
                String.join(",", targets), cur == null ? null : cur.id(), null,
                Map.of("reason", reason, "by", peerId, "with", String.join(",", targets))));
        if (plans.find(sessionId).isPresent() && !targets.contains("user")) return currentTodo == null
                ? "已请求相关成员在独立议题中提供意见，结论将回报主控。请结束发言并等待。"
                : "已记录受阻原因与建议沟通对象，检查结束后由主控统一决定下一步。";
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
                .filter(p -> target.equals(p.name()) || target.equals(p.id()))
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
